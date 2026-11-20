package dev.mooner.lucent.tools

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dev.mooner.lucent.data.AppSettings
import java.util.concurrent.Executors

/**
 * 휴대폰에 온 알림을 기록한다(`notifications.recent`). 사용자가 시스템 설정에서 알림 접근을 허용하면 시스템이 붙인다.
 *
 * 설정의 "알림 기록"이 꺼져 있으면 아무것도 남기지 않고 스스로 떨어진다([requestUnbind]). 켜면 다시 붙여 달라고 한다
 * ([start]). 알림의 글은 기록에만 들어가고 로그에는 남기지 않는다.
 */
class NotificationWatcher : NotificationListenerService() {

    private val settings by lazy { AppSettings(this) }
    private fun enabled() = settings.toolNotifications

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = this
        if (!enabled()) {
            requestUnbind()
            return
        }
        // 떠 있는 알림으로 기록과 답장 단추를 맞춘다(프로세스가 다시 떴으면 답장 단추가 비어 있다).
        val shown = runCatching { activeNotifications }.getOrNull().orEmpty()
        work.execute {
            runCatching {
                val now = System.currentTimeMillis()
                for (sbn in shown) record(this, sbn.packageName, sbn.key, sbn.notification, sbn.postTime, now)
                NotificationLog.get(this).reconcile(shown.mapTo(HashSet()) { it.key }, now)
            }.onFailure { Log.w(TAG, "떠 있는 알림을 맞추지 못함", it) }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (connected === this) connected = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (!enabled()) return
        work.execute {
            runCatching { record(this, sbn.packageName, sbn.key, sbn.notification, sbn.postTime, System.currentTimeMillis()) }
                .onFailure { Log.w(TAG, "알림을 기록하지 못함(${sbn.packageName})", it) }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        if (!enabled()) return
        val key = sbn.key
        work.execute {
            NoticeReplies.remove(key)
            runCatching { NotificationLog.get(this).removed(key, System.currentTimeMillis()) }
        }
    }

    companion object {
        private const val TAG = "NotificationWatcher"

        /** 기록은 한 줄로 차례대로 한다(올라옴과 사라짐의 순서를 지킨다). */
        private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "lucent-notices") }

        @Volatile private var connected: NotificationWatcher? = null

        /** 사용자가 시스템 설정에서 이 앱의 알림 접근을 허용했는가. */
        fun isAllowed(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                context.getSystemService(NotificationManager::class.java)
                    ?.isNotificationListenerAccessGranted(ComponentName(context, NotificationWatcher::class.java)) == true
            } else {
                context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
            }

        /** "알림 기록"을 켰다. 허용돼 있으면 시스템에 다시 붙여 달라고 한다. */
        fun start(context: Context) {
            if (!isAllowed(context)) return
            runCatching { requestRebind(ComponentName(context, NotificationWatcher::class.java)) }
        }

        /** "알림 기록"을 껐다. 붙어 있으면 떨어진다. 기록과 답장 단추는 부르는 쪽이 지운다. */
        fun stop() {
            runCatching { connected?.requestUnbind() }
        }

        /**
         * 알림 하나를 기록하고 답장 단추를 둔다. 기록하지 않는 알림이면(이 앱의 것, 진행 중 표시 등) 아무것도 하지 않는다.
         * 기록한 줄의 id를 돌려준다.
         */
        internal fun record(context: Context, pkg: String, key: String, notification: Notification, postedAt: Long, now: Long): Long? {
            if (!NoticeFilter.records(pkg, context.packageName, notification.flags, notification.category)) return null
            val draft = NoticeDigest.draft(context, pkg, key, notification, postedAt)
            if (!NoticeFilter.hasContent(draft)) return null
            NoticeReplies.put(key, NoticeDigest.replyTarget(notification))
            return NotificationLog.get(context).posted(draft, now)
        }
    }
}
