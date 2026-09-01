package kr.jasan.collector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * 화면은 이 한 장뿐이다. 설정하고, 권한 켜고, 큐가 잘 빠지는지 확인하는 용도.
 * 실제 자산 화면은 웹앱이 담당하므로 이 앱은 거의 손댈 일이 없다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var url: EditText
    private lateinit var anon: EditText
    private lateinit var mail: EditText
    private lateinit var pw: EditText
    private lateinit var web: EditText
    private lateinit var status: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)

        url = findViewById(R.id.url); anon = findViewById(R.id.anon)
        mail = findViewById(R.id.mail); pw = findViewById(R.id.pw)
        web = findViewById(R.id.web); status = findViewById(R.id.status)

        url.setText(Prefs.get(this, Prefs.URL))
        anon.setText(Prefs.get(this, Prefs.ANON))
        mail.setText(Prefs.get(this, Prefs.EMAIL))
        pw.setText(Prefs.get(this, Prefs.PASSWORD))
        web.setText(Prefs.get(this, Prefs.WEBAPP))

        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.perm).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.battery).setOnClickListener { askBattery() }
        findViewById<Button>(R.id.openWeb).setOnClickListener {
            val u = Prefs.get(this, Prefs.WEBAPP)
            if (u.isBlank()) toast("웹앱 주소를 먼저 저장해 주세요")
            else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        }
        findViewById<Button>(R.id.push).setOnClickListener {
            UploadWorker.kick(this); QuoteWorker.once(this); toast("전송과 시세 조회를 시작했습니다")
        }

        UploadWorker.schedulePeriodic(this)
        QuoteWorker.schedule(this)
        watchStatus()
    }

    override fun onResume() { super.onResume(); render() }

    private fun save() {
        Prefs.set(this, Prefs.URL, url.text.toString().trim().trimEnd('/'))
        Prefs.set(this, Prefs.ANON, anon.text.toString().trim())
        Prefs.set(this, Prefs.EMAIL, mail.text.toString().trim())
        Prefs.set(this, Prefs.PASSWORD, pw.text.toString())
        Prefs.set(this, Prefs.WEBAPP, web.text.toString().trim())
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { Api.signIn(this@MainActivity) }.getOrDefault(false) }
            toast(if (ok) "서버에 연결되었습니다" else "로그인에 실패했습니다. 주소·키·계정을 확인해 주세요")
            if (ok) { UploadWorker.kick(this@MainActivity); QuoteWorker.once(this@MainActivity) }
            render()
        }
    }

    private fun askBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("이미 제외되어 있습니다"); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        } catch (e: Throwable) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun watchStatus() {
        val dao = Db.get(this).pending()
        lifecycleScope.launch { dao.queuedCount().collectLatest { render(it) } }
    }

    private var lastQueued = 0
    private fun render(queued: Int = lastQueued) {
        lastQueued = queued
        val listenerOn = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val f = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
        val ok = Prefs.getLong(this, Prefs.LAST_OK)
        val q = Prefs.getLong(this, Prefs.LAST_QUOTE)

        status.text = buildString {
            appendLine(if (listenerOn) "✓ 알림 접근 허용됨" else "✗ 알림 접근 꺼짐 — 아래 버튼으로 켜주세요")
            appendLine(if (batteryOk) "✓ 배터리 최적화 제외됨" else "✗ 배터리 최적화가 켜져 있음 — 알림을 놓칠 수 있습니다")
            appendLine(if (Prefs.configured(this@MainActivity)) "✓ 서버 설정 완료" else "✗ 서버 미설정")
            appendLine()
            appendLine("전송 대기: ${queued}건")
            appendLine("마지막 전송: " + if (ok > 0) f.format(Date(ok)) else "없음")
            append("마지막 시세: " + if (q > 0) f.format(Date(q)) else "없음")
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
