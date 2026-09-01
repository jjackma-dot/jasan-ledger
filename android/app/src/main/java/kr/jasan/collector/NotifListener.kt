package kr.jasan.collector

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * 알림을 가로채 곧바로 로컬 DB에 넣는다.
 *
 * 여기서 웹뷰나 다른 화면에 직접 말을 걸지 않는 것이 핵심이다.
 * 화면이 떠 있든 아니든, 네트워크가 있든 없든, 알림은 일단 디스크에 남는다.
 * 서버로 보내는 일은 WorkManager 가 성공할 때까지 맡는다.
 */
class NotifListener : NotificationListenerService() {

    companion object {
        /** 감시할 앱. 카카오톡 외에 증권사 앱·문자도 필요하면 추가하면 된다. */
        val WATCH = setOf(
            "com.kakao.talk",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging"
        )
        /** 체결 알림으로 볼 최소 조건 */
        private val MUST = listOf("체결", "계좌번호")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WATCH) return
        val e = sbn.notification?.extras ?: return

        // 요약본(text)은 잘려 있을 수 있으므로 항상 bigText 를 우선으로 본다
        val big = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val txt = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() }

        val body = listOfNotNull(big, lines, txt).maxByOrNull { it.length } ?: return
        val raw = listOfNotNull(title, body).joinToString("\n")

        if (MUST.none { raw.contains(it) }) return          // 관계없는 알림은 버린다
        if (!raw.contains("체결")) return

        val fp = sha1(raw.replace(Regex("\\s+"), " ").trim())

        CoroutineScope(Dispatchers.IO).launch {
            Db.get(applicationContext).pending().add(
                Pending(
                    raw = raw,
                    pkg = sbn.packageName,
                    receivedAt = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    fingerprint = fp
                )
            )
            UploadWorker.kick(applicationContext)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // 서비스가 (재)연결될 때마다 밀린 큐를 밀어낸다
        UploadWorker.kick(applicationContext)
        QuoteWorker.schedule(applicationContext)
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
