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
import androidx.core.os.bundleOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.mooner.lucent.R
import dev.mooner.lucent.data.AppSettings
import dev.mooner.lucent.data.ExpressionText
import dev.mooner.lucent.data.Kind
import dev.mooner.lucent.data.StoredItem
import dev.mooner.lucent.protocol.ServerFrame
import dev.mooner.lucent.ui.chat.ChatActivity
import dev.mooner.lucent.ui.common.Plain

/**
 * 알림 채널과 앱이 직접 띄우는 알림.
 *
 * 푸시(FCM)로 깨우는 경우는 서버 메시지가 알림을 띄우므로 앱이 같은 것을 또 띄우지 않는다. 앱이 직접 띄우는 것은 푸시를
 * 쓸 수 없을 때(자격 증명이 없거나 이 앱의 푸시 토큰이 없을 때) 뒤에 있는 앱이 연결로 받은 글이다.
 * 글 앞부분은 설정에서 켠 때만 싣는다(사양 10.3의 "푸시에 글 앞부분 싣기"와 같은 기본값).
 *
 * 다른 기기에서 본 것은 치운다(`seen{seq}`, 푸시 `clear`, 사양 12.6). 앱이 띄운 알림에는 그 프레임의 번호를 실어 두어
 * 번호로 치운다. 서버 푸시가 띄운 알림은 앱이 번호를 알 수 없으므로(데이터는 누를 때의 인텐트에만 있고, Play 서비스가
 * 앱을 깨우지 않고 띄우기도 한다) 이 기기에서 울렸을 수 있는 것을 다 봤다고 알 때만 모두 치운다([clearUpTo]).
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

    /** 알림의 제목: 보이는 이름(결정 0064). 정하지 않았으면 앱의 기본 이름이다. */
    var title: () -> String = { context.getString(R.string.assistant_name) }

    /** 마지막으로 글·파일을 알린 때. */
    private var lastMessageAt = -STICKER_QUIET_MS

    fun ensureChannels() {
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

    /** 뒤에 있는 앱이 받은 프레임을 알린다. `note`·`card_closed`·`sent`·`typing`은 알리지 않는다(10.2와 같은 규칙). */
    fun notifyFrame(frame: ServerFrame.Numbered) {
        when (frame) {
            is ServerFrame.Text -> post(Shown.MESSAGE, frame.body, frame.seq)
            is ServerFrame.Files -> post(Shown.FILES, frame.body, frame.seq)
            is ServerFrame.Sticker -> post(Shown.STICKER, "", frame.seq)
            is ServerFrame.CardOpen -> post(Shown.CARD, frame.card.headline, frame.seq)
            else -> Unit
        }
    }

    /** 놓아 두었던 줄을 지금 알린다(`alert{seq}`, 5.5). 저장된 루시아의 줄만 온다. */
    fun notifyStored(item: StoredItem) {
        when {
            item.kind == Kind.CARD -> post(Shown.CARD, item.card?.headline ?: item.body, item.seq)
            item.files.isNotEmpty() -> post(Shown.FILES, item.body, item.seq)
            item.body.isBlank() && item.sticker != null -> post(Shown.STICKER, "", item.seq)
            else -> post(Shown.MESSAGE, item.body, item.seq)
        }
    }

    private enum class Shown { MESSAGE, FILES, STICKER, CARD }

    @SuppressLint("MissingPermission") // canPost()가 POST_NOTIFICATIONS를 확인한다.
    private fun post(shown: Shown, body: String, seq: Long) {
        if (!canPost()) return
        val approval = shown == Shown.CARD
        // 스티커는 같은 답의 글 바로 뒤에 온다. 방금 글을 알렸으면 따로 알리지 않는다(글 알림을 덮어쓰지 않게, app-expressions.md 10장).
        val now = android.os.SystemClock.elapsedRealtime()
        if (shown == Shown.STICKER && now - lastMessageAt < STICKER_QUIET_MS) return
        if (shown == Shown.MESSAGE || shown == Shown.FILES) lastMessageAt = now
        val (text, id) = when (shown) {
            Shown.MESSAGE -> preview(body, R.string.notify_new_message) to ID_MESSAGES
            Shown.FILES -> preview(body, R.string.notify_new_files) to ID_MESSAGES
            Shown.STICKER -> context.getString(R.string.notify_new_sticker) to ID_MESSAGES
            Shown.CARD -> preview(Plain.line(body), R.string.notify_new_card) to (ID_CARD_BASE + (seq % 1000).toInt())
        }
        val name = title()
        val builder = NotificationCompat.Builder(context, if (approval) CHANNEL_APPROVALS else CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.notification_accent))
            .setContentTitle(name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (approval) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(name)
                    .setContentText(context.getString(R.string.notify_new_message))
                    .build(),
            )
            // 다른 기기에서 본 것을 치울 때 이 번호로 가린다. 같은 자리의 글 알림은 늘 가장 새 번호를 지닌다.
            .addExtras(bundleOf(EXTRA_SEQ to seq))
            .setContentIntent(openChat())
        face()?.let(builder::setLargeIcon)
        runCatching { NotificationManagerCompat.from(context).notify(TAG_MESSAGES, id, builder.build()) }
    }

    /**
     * 다른 기기에서 [upTo]번(이 앱의 번호)까지 봤다(`seen{seq}`, 푸시 `clear`). 앱이 띄운 알림 가운데 그 번호까지의 것을 치운다.
     * [everything]이면(이 기기에서 울렸을 수 있는 프레임이 모두 그 번호 안에 있다) 서버 푸시가 띄운 알림까지 모두 치운다.
     * 더 새로 온 것이 있으면 그것은 둔다. 번호를 모르는 알림은 확실하지 않으면 두는 쪽을 고른다: 남은 알림은 앱을 열면
     * 걷히지만, 잘못 치운 승인 카드의 알림은 되돌릴 수 없다.
     */
    fun clearUpTo(upTo: Long, everything: Boolean) {
        if (everything) {
            clearAll()
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val active = runCatching { manager.activeNotifications }.getOrNull() ?: return
        for (sbn in active) {
            if (sbn.tag != TAG_MESSAGES) continue
            if (covers(sbn.notification.extras.getLong(EXTRA_SEQ, 0L), upTo)) runCatching { manager.cancel(sbn.tag, sbn.id) }
        }
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
        private const val EXTRA_SEQ = "dev.mooner.lucent.seq"
        private const val ID_MESSAGES = 1
        private const val ID_CARD_BASE = 1000
        private const val PREVIEW_CHARS = 120
        private const val STICKER_QUIET_MS = 10_000L

        /** 번호가 [seq]인 알림이 [upTo]까지 봤다는 말로 치워지는가. 번호를 모르면(0) 치우지 않는다. */
        fun covers(seq: Long, upTo: Long): Boolean = seq in 1..upTo
    }
}
