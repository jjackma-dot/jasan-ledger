package kr.jasan.collector

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 알림을 잡는 즉시 여기에 넣는다. 전송 성공 전까지 절대 사라지지 않는다.
 * 이 파일이 신뢰도의 핵심 — 네트워크·앱 종료·재부팅과 무관하게 큐가 살아남는다.
 */
@Entity(tableName = "pending", indices = [Index(value = ["fingerprint"], unique = true)])
data class Pending(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val raw: String,
    val pkg: String,
    val receivedAt: Long,
    val fingerprint: String,
    val status: String = "QUEUED",      // QUEUED | SENT
    val tries: Int = 0,
    val lastError: String? = null
)

@Dao
interface PendingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(p: Pending): Long

    @Query("SELECT * FROM pending WHERE status = 'QUEUED' ORDER BY receivedAt ASC LIMIT 50")
    suspend fun queued(): List<Pending>

    @Query("UPDATE pending SET status = 'SENT' WHERE id IN (:ids)")
    suspend fun markSent(ids: List<Long>)

    @Query("UPDATE pending SET tries = tries + 1, lastError = :err WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<Long>, err: String?)

    @Query("SELECT COUNT(*) FROM pending WHERE status = 'QUEUED'")
    fun queuedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending WHERE status = 'SENT'")
    fun sentCount(): Flow<Int>

    /** 오래된 전송완료 기록만 정리. 대기 중인 항목은 절대 지우지 않는다. */
    @Query("DELETE FROM pending WHERE status = 'SENT' AND receivedAt < :before")
    suspend fun prune(before: Long)
}

@Database(entities = [Pending::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun pending(): PendingDao

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, Db::class.java, "collector.db")
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
