package tv.frostline.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A VOD the user stopped partway through. Positions and durations are in milliseconds. */
@Entity(tableName = "resume")
data class ResumeEntity(
    @PrimaryKey val videoId: String,
    val position: Long,
    val duration: Long,
    val title: String,
    val thumbnail: String,
    val channel: String,
    val updatedAt: Long,
)

@Dao
interface ResumeDao {
    @Query("SELECT * FROM resume ORDER BY updatedAt DESC LIMIT 30")
    fun observeAll(): Flow<List<ResumeEntity>>

    @Query("SELECT * FROM resume WHERE videoId = :videoId")
    suspend fun get(videoId: String): ResumeEntity?

    @Upsert
    suspend fun upsert(entity: ResumeEntity)

    @Query("DELETE FROM resume WHERE videoId = :videoId")
    suspend fun delete(videoId: String)
}

@Database(entities = [ResumeEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun resumeDao(): ResumeDao
}
