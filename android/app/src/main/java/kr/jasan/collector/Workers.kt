package kr.jasan.collector

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * 큐에 남은 알림을 서버로 올린다.
 * 실패하면 Result.retry() — WorkManager 가 지수 백오프로 계속 다시 시도한다.
 * 앱이 꺼져도, 재부팅해도 큐와 작업은 살아 있다. 그래서 유실이 없다.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Prefs.configured(ctx)) return Result.success()      // 아직 설정 전
        val dao = Db.get(ctx).pending()
        val batch = dao.queued()
        if (batch.isEmpty()) return Result.success()
        return try {
            Api.postInbox(ctx, batch)
            dao.markSent(batch.map { it.id })
            Prefs.setLong(ctx, Prefs.LAST_OK, System.currentTimeMillis())
            dao.prune(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
            if (dao.queued().isNotEmpty()) kick(ctx)              // 남은 게 있으면 이어서
            Result.success()
        } catch (e: Throwable) {
            dao.markFailed(batch.map { it.id }, e.message)
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "upload"
        fun kick(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
        /** 혹시 놓친 게 있으면 주워 담는 정기 점검 */
        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UploadWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("upload_sweep", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/**
 * 시세 수집기. 앱에는 CORS 제약이 없어 브라우저보다 안정적으로 받아온다.
 * 받은 값은 Supabase quotes 테이블에 올리고, 웹앱이 그걸 읽어 화면에 반영한다.
 */
class QuoteWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Prefs.configured(ctx)) return Result.success()
        val want = mapOf(
            "TQQQ" to "TQQQ",
            "SOXL" to "SOXL",
            "QLD" to "QLD",
            "418660.KS" to "418660.KS",     // ISA 레버리지
            "368590.KS" to "368590.KS",     // DC · RISE
            "442570.KS" to "442570.KS",     // DC · TDF
            "KRW=X" to "KRW=X"              // 원/달러
        )
        val got = HashMap<String, Double>()
        want.forEach { (key, sym) -> Api.fetchQuote(sym)?.let { got[key] = it } }
        if (!got.containsKey("KRW=X")) Api.fetchFxFallback()?.let { got["KRW=X"] = it }

        // 하나도 못 받았으면 다시 시도. 일부라도 받았으면 그것만 올린다
        // (웹앱은 못 받은 항목에 대해 전일 값을 임시로 쓰다가 나중에 덮어쓴다)
        if (got.isEmpty()) return Result.retry()
        return try {
            Api.putQuotes(ctx, got)
            Prefs.setLong(ctx, Prefs.LAST_QUOTE, System.currentTimeMillis())
            Result.success()
        } catch (e: Throwable) { Result.retry() }
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<QuoteWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("quotes", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
        fun once(ctx: Context) {
            WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<QuoteWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}

/** 재부팅 후에도 큐와 정기작업이 이어지도록 */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: android.content.Intent) {
        UploadWorker.kick(ctx)
        UploadWorker.schedulePeriodic(ctx)
        QuoteWorker.schedule(ctx)
    }
}
