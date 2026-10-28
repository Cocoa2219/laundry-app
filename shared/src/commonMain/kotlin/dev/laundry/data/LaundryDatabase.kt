package dev.laundry.data

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Update

// Times are stored as ISO-8601 local date-times ("2026-10-29T09:00"), which sort correctly as text.
@Entity(tableName = "hangs")
internal data class HangEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fabric: String,
    val crowding: String,
    val spot: String,
    val latitude: Double,
    val longitude: Double,
    val hangTime: String,
    val predictedFinish: String?,
    val checkTime: String?,
    val result: String?,
    val actualFinish: String?,
    /** JSON list of hourly weather; see [FeedbackRepository]. */
    val weatherJson: String,
    val weatherSource: String,
)

@Dao
internal interface HangDao {
    @Insert
    suspend fun insert(hang: HangEntity): Long

    @Update
    suspend fun update(hang: HangEntity)

    @Query("DELETE FROM hangs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM hangs WHERE id = :id")
    suspend fun get(id: Long): HangEntity?

    @Query("SELECT * FROM hangs WHERE result IS NULL AND hangTime >= :since ORDER BY hangTime DESC LIMIT 1")
    suspend fun latestUnanswered(since: String): HangEntity?

    @Query("SELECT * FROM hangs WHERE result IS NOT NULL ORDER BY hangTime")
    suspend fun answered(): List<HangEntity>
}

@Database(entities = [HangEntity::class], version = 1)
@ConstructedBy(LaundryDatabaseConstructor::class)
abstract class LaundryDatabase : RoomDatabase() {
    internal abstract fun hangDao(): HangDao
}

// Room's KSP processor generates the actual for each platform.
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object LaundryDatabaseConstructor : RoomDatabaseConstructor<LaundryDatabase> {
    override fun initialize(): LaundryDatabase
}
