package dev.mooner.lucent.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.mooner.lucent.R
import dev.mooner.lucent.data.AppSettings
import dev.mooner.lucent.data.ExpressionText
import dev.mooner.lucent.protocol.ServerFrame
import dev.mooner.lucent.ui.chat.ChatActivity

/**
 * 알림 채널과 앱이 직접 띄우는 알림.
 *
 * 푸시(FCM)로 깨우는 경우는 서버 메시지가 알림을 띄우므로 앱이 같은 것을 또 띄우지 않는다. 앱이 직접 띄우는 것은 푸시를
 * 쓸 수 없을 때(자격 증명이 없거나 이 앱의 푸시 토큰이 없을 때) 뒤에 있는 앱이 연결로 받은 글이다.
 * 글 앞부분은 설정에서 켠 때만 싣는다(사양 10.3의 "푸시에 글 앞부분 싣기"와 같은 기본값).
 */
class Notifier(private val context: Context, private val settings: AppSettings) {

    /**
     * 이모지 이름 → 보이는 이름. 알림에는 그림이 없으므로 미리보기의 `:name:`을 보이는 이름으로 바꾼다
     * (app-expressions.md 4.2). 목록에 없거나 보이는 이름이 없으면 글 그대로 둔다.
     */
    var emojiLabel: (String) -> String? = { null }

    /**
     * 루시아가 정한 프로필 그림(받아 둔 것). 있으면 알림의 큰 아이콘으로 쓴다. 서버의 푸시가 띄우는 알림에는 그림이
     * 실리지 않으므로 앱이 직접 띄우는 알림에만 붙는다.
     */
    var face: () -> android.graphics.Bitmap? = { null }

    /** 마지막으로 글·파일을 알린 때. */
    private var lastMessageAt = -STICKER_QUIET_MS

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_messages_desc)
                lightColor = context.getColor(R.color.sky)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVALS, context.getString(R.string.channel_approvals), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_approvals_desc)
                lightColor = context.getColor(R.color.sky)
            },
        )
    }

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // canPost()가 POST_NOTIFICATIONS를 확인한다.
    /** 뒤에 있는 앱이 받은 프레임을 알린다. `note`·`card_closed`·`typing`은 알리지 않는다(10.2와 같은 규칙). */
    fun notifyFrame(frame: ServerFrame.Numbered) {
        if (!canPost()) return
        val approval = frame is ServerFrame.CardOpen
        // 스티커는 같은 답의 글 바로 뒤에 온다. 방금 글을 알렸으면 따로 알리지 않는다(글 알림을 덮어쓰지 않게, app-expressions.md 10장).
        val now = android.os.SystemClock.elapsedRealtime()
        if (frame is ServerFrame.Sticker && now - lastMessageAt < STICKER_QUIET_MS) return
        if (frame is ServerFrame.Text || frame is ServerFrame.Files) lastMessageAt = now
        val (text, id) = when (frame) {
            is ServerFrame.Text -> preview(frame.body, R.string.notify_new_message) to ID_MESSAGES
            is ServerFrame.Files -> preview(frame.body, R.string.notify_new_files) to ID_MESSAGES
            is ServerFrame.Sticker -> context.getString(R.string.notify_new_sticker) to ID_MESSAGES
            is ServerFrame.CardOpen -> preview(dev.mooner.lucent.ui.common.Plain.text(frame.card.headline), R.string.notify_new_card) to (ID_CARD_BASE + (frame.seq % 1000).toInt())
            else -> return
        }
        val builder = NotificationCompat.Builder(context, if (approval) CHANNEL_APPROVALS else CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.notification_accent))
            .setContentTitle(context.getString(R.string.assistant_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(if (approval) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (approval) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.assistant_name))
                    .setContentText(context.getString(R.string.notify_new_message))
                    .build(),
            )
            .setContentIntent(openChat())
        face()?.let(builder::setLargeIcon)
        runCatching { NotificationManagerCompat.from(context).notify(TAG_MESSAGES, id, builder.build()) }
    }

    /** 대화 화면이 앞에 오면 쌓인 알림을 걷는다. */
    fun clearAll() {
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
    }

    private fun preview(body: String, fallback: Int): String =
        if (settings.previewInNotifications && body.isNotBlank()) labelled(body).take(PREVIEW_CHARS) else context.getString(fallback)

    private fun labelled(body: String): String {
        val tokens = ExpressionText.tokens(body)
        if (tokens.isEmpty()) return body
        val out = StringBuilder(body.length)
        var at = 0
        for (token in tokens) {
            val label = emojiLabel(token.name)?.takeIf { it.isNotBlank() } ?: continue
            out.append(body, at, token.start).append(label)
            at = token.end
        }
        return out.append(body, at, body.length).toString()
    }

    private fun openChat(): PendingIntent {
        val intent = Intent(context, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        /** 서버 푸시의 기본 채널도 이 ID다(`AndroidManifest`의 default_notification_channel_id). */
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_APPROVALS = "approvals"
        private const val TAG_MESSAGES = "lucia"
        private const val ID_MESSAGES = 1
        private const val ID_CARD_BASE = 1000
        private const val PREVIEW_CHARS = 120
        private const val STICKER_QUIET_MS = 10_000L
    }
}
