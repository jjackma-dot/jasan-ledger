package kr.jasan.collector

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 서버 주소·키·토큰은 암호화 저장. 평문 SharedPreferences 를 쓰지 않는다. */
object Prefs {
    private const val FILE = "jasan_secure"

    @Volatile private var cached: android.content.SharedPreferences? = null

    private fun sp(ctx: Context): android.content.SharedPreferences =
        cached ?: synchronized(this) { cached ?: build(ctx).also { cached = it } }

    private fun build(ctx: Context) = try {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Throwable) {
        // 기기 키스토어 문제로 실패하면 앱이 죽지 않도록 일반 저장소로 물러난다
        ctx.getSharedPreferences(FILE + "_plain", Context.MODE_PRIVATE)
    }

    fun get(ctx: Context, k: String, d: String = "") = sp(ctx).getString(k, d) ?: d
    fun set(ctx: Context, k: String, v: String) = sp(ctx).edit().putString(k, v).apply()
    fun getLong(ctx: Context, k: String, d: Long = 0) = sp(ctx).getLong(k, d)
    fun setLong(ctx: Context, k: String, v: Long) = sp(ctx).edit().putLong(k, v).apply()

    const val URL = "url"
    const val ANON = "anon"
    const val EMAIL = "email"
    const val PASSWORD = "password"
    const val ACCESS = "access"
    const val REFRESH = "refresh"
    const val LAST_OK = "last_ok"
    const val LAST_QUOTE = "last_quote"
    const val WEBAPP = "webapp"

    fun configured(ctx: Context) = get(ctx, URL).isNotBlank() && get(ctx, ANON).isNotBlank()
}
