package kr.jasan.collector

import android.content.Context
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Supabase REST + GoTrue. 라이브러리 없이 OkHttp 로만 붙는다. */
object Api {
    private val JSON = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    class ApiError(msg: String, val code: Int = 0) : Exception(msg)

    private fun base(ctx: Context) = Prefs.get(ctx, Prefs.URL).trimEnd('/')

    /** 저장된 아이디·비밀번호로 로그인해 토큰을 받는다. */
    fun signIn(ctx: Context): Boolean {
        val body = JSONObject()
            .put("email", Prefs.get(ctx, Prefs.EMAIL))
            .put("password", Prefs.get(ctx, Prefs.PASSWORD))
        val req = Request.Builder()
            .url("${base(ctx)}/auth/v1/token?grant_type=password")
            .addHeader("apikey", Prefs.get(ctx, Prefs.ANON))
            .post(body.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return false
            val j = JSONObject(r.body!!.string())
            Prefs.set(ctx, Prefs.ACCESS, j.optString("access_token"))
            Prefs.set(ctx, Prefs.REFRESH, j.optString("refresh_token"))
            return true
        }
    }

    private fun refresh(ctx: Context): Boolean {
        val rt = Prefs.get(ctx, Prefs.REFRESH)
        if (rt.isBlank()) return signIn(ctx)
        val req = Request.Builder()
            .url("${base(ctx)}/auth/v1/token?grant_type=refresh_token")
            .addHeader("apikey", Prefs.get(ctx, Prefs.ANON))
            .post(JSONObject().put("refresh_token", rt).toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return signIn(ctx)
            val j = JSONObject(r.body!!.string())
            Prefs.set(ctx, Prefs.ACCESS, j.optString("access_token"))
            Prefs.set(ctx, Prefs.REFRESH, j.optString("refresh_token"))
            return true
        }
    }

    /** 401 이면 토큰을 갱신해 한 번만 다시 시도한다. */
    private fun send(ctx: Context, build: (String) -> Request): String {
        var tok = Prefs.get(ctx, Prefs.ACCESS)
        if (tok.isBlank()) { if (!signIn(ctx)) throw ApiError("로그인 실패"); tok = Prefs.get(ctx, Prefs.ACCESS) }
        http.newCall(build(tok)).execute().use { r ->
            if (r.code == 401) {
                if (!refresh(ctx)) throw ApiError("인증 갱신 실패", 401)
                http.newCall(build(Prefs.get(ctx, Prefs.ACCESS))).execute().use { r2 ->
                    if (!r2.isSuccessful) throw ApiError("HTTP ${r2.code} ${r2.body?.string()?.take(180)}", r2.code)
                    return r2.body?.string() ?: ""
                }
            }
            if (!r.isSuccessful) throw ApiError("HTTP ${r.code} ${r.body?.string()?.take(180)}", r.code)
            return r.body?.string() ?: ""
        }
    }

    /** 알림 원문 묶음 업로드. fingerprint 중복은 서버가 무시한다. */
    fun postInbox(ctx: Context, items: List<Pending>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject()
                .put("raw", it.raw).put("pkg", it.pkg)
                .put("received_at", java.time.Instant.ofEpochMilli(it.receivedAt).toString())
                .put("fingerprint", it.fingerprint))
        }
        send(ctx) { tok ->
            Request.Builder().url("${base(ctx)}/rest/v1/inbox")
                .addHeader("apikey", Prefs.get(ctx, Prefs.ANON))
                .addHeader("Authorization", "Bearer $tok")
                .addHeader("Prefer", "resolution=ignore-duplicates,return=minimal")
                .post(arr.toString().toRequestBody(JSON)).build()
        }
    }

    /** 시세 업서트. */
    fun putQuotes(ctx: Context, quotes: Map<String, Double>) {
        if (quotes.isEmpty()) return
        val now = java.time.Instant.now().toString()
        val arr = JSONArray()
        quotes.forEach { (s, p) -> arr.put(JSONObject().put("sym", s).put("price", p).put("at", now)) }
        send(ctx) { tok ->
            Request.Builder().url("${base(ctx)}/rest/v1/quotes")
                .addHeader("apikey", Prefs.get(ctx, Prefs.ANON))
                .addHeader("Authorization", "Bearer $tok")
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(arr.toString().toRequestBody(JSON)).build()
        }
    }

    /** Yahoo 시세. 앱에서는 CORS 제약이 없어 브라우저보다 안정적으로 붙는다. */
    fun fetchQuote(symbol: String): Double? = try {
        val req = Request.Builder()
            .url("https://query1.finance.yahoo.com/v8/finance/chart/$symbol?interval=1d&range=1d")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else {
                val meta = JSONObject(r.body!!.string())
                    .getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta")
                meta.optDouble("regularMarketPrice").takeIf { it > 0 }
            }
        }
    } catch (e: Throwable) { null }

    /** 환율 보조 경로 — Yahoo 가 막히면 여기서 받는다. */
    fun fetchFxFallback(): Double? = try {
        val req = Request.Builder().url("https://api.frankfurter.app/latest?from=USD&to=KRW").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null
            else JSONObject(r.body!!.string()).getJSONObject("rates").optDouble("KRW").takeIf { it > 0 }
        }
    } catch (e: Throwable) { null }
}
