package dev.mooner.lucent.tools

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

/** 알림 하나에서 기록할 글을 읽어 낸다. 글은 [NotificationLogRules]의 상한까지 자른다. */
object NoticeDigest {

    private val labels = ConcurrentHashMap<String, String>()

    fun draft(context: Context, pkg: String, key: String, notification: Notification, postedAt: Long): NoticeDraft {
        val extras = notification.extras ?: Bundle.EMPTY
        fun text(name: String) = extras.getCharSequence(name)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification) }.getOrNull()
        val messages = style?.messages.orEmpty().takeLast(NotificationLogRules.MESSAGES).mapNotNull { m ->
            val body = m.text?.toString()?.trim().orEmpty()
            if (body.isEmpty()) null
            else NoticeMessage(
                from = m.person?.name?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                text = body.clip(NotificationLogRules.MESSAGE_CHARS),
                at = m.timestamp,
            )
        }
        // 메신저 알림이면 마지막 메시지가, 받은편지함 모양(여러 줄)이면 줄들이 본문이다.
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        val body = messages.lastOrNull()?.text
            ?: text(Notification.EXTRA_BIG_TEXT)
            ?: lines?.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: text(Notification.EXTRA_TEXT)
            ?: ""
        return NoticeDraft(
            key = key,
            pkg = pkg,
            app = label(context, pkg),
            title = (text(Notification.EXTRA_TITLE_BIG) ?: text(Notification.EXTRA_TITLE) ?: "").clip(NotificationLogRules.TITLE_CHARS),
            text = body.clip(NotificationLogRules.TEXT_CHARS),
            // 카카오톡은 단톡방 이름을 작은 글에 둔다(옛 판은 요약 글. StarLight의 AndroidRParserSpec·DefaultParserSpec).
            sub = (text(Notification.EXTRA_SUB_TEXT) ?: text(Notification.EXTRA_SUMMARY_TEXT))?.clip(NotificationLogRules.TITLE_CHARS),
            conversation = style?.conversationTitle?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.clip(NotificationLogRules.TITLE_CHARS),
            messages = messages,
            category = notification.category,
            postedAt = postedAt,
        )
    }

    /**
     * 답장 단추: 자유롭게 글을 받는 입력([RemoteInput.getAllowFreeFormInput])이 있는 동작. 손목시계용 동작에만 두는 앱도 있어
     * 거기서도 찾는다. 없으면 답장할 수 없는 알림이다.
     */
    fun replyTarget(notification: Notification): ReplyTarget? {
        @Suppress("DEPRECATION")
        val wearable = runCatching { Notification.WearableExtender(notification).actions }.getOrNull().orEmpty()
        val action = (notification.actions.orEmpty().asList() + wearable).firstOrNull { a ->
            a.actionIntent != null && a.remoteInputs?.any { it.allowFreeFormInput } == true
        } ?: return null
        return ReplyTarget(action.actionIntent, action.remoteInputs)
    }

    /** 사람이 읽는 앱 이름. 이 앱이 볼 수 없는 앱(사양 밖의 패키지 가시성)이면 패키지 이름이다. */
    fun label(context: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim().ifEmpty { pkg }
        }.getOrDefault(pkg)
    }
}

/** 알림의 답장 단추. 시스템이 쥔 것이라 저장할 수 없고, 프로세스 안에만 둔다. */
class ReplyTarget(val intent: PendingIntent, val inputs: Array<RemoteInput>) {

    /**
     * [text]를 답장 칸에 적어 넣은 것처럼 보낸다(StarLight의 `SessionSend.text`와 같은 방법).
     *
     * @throws PendingIntent.CanceledException 그 앱이 더는 받지 않을 때
     */
    fun send(context: Context, text: String) {
        val results = Bundle()
        for (input in inputs) if (input.allowFreeFormInput) results.putCharSequence(input.resultKey, text)
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
        intent.send(context, 0, fill)
    }
}

/**
 * 떠 있는 알림의 답장 단추(알림 열쇠 → 단추). 알림이 사라지면 뺀다. 프로세스가 다시 뜨면 비어 있다가, 알림 읽기가 다시
 * 붙을 때 떠 있는 알림으로 다시 채운다([NotificationWatcher]).
 */
object NoticeReplies {

    private const val MAX = 200
    private val targets = object : LinkedHashMap<String, ReplyTarget>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReplyTarget>?) = size > MAX
    }

    @Synchronized
    fun put(key: String, target: ReplyTarget?) {
        if (target == null) targets.remove(key) else targets[key] = target
    }

    @Synchronized
    fun remove(key: String) {
        targets.remove(key)
    }

    @Synchronized
    fun get(key: String): ReplyTarget? = targets[key]

    @Synchronized
    fun clear() = targets.clear()
}
