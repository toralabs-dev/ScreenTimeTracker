package com.screentimetracker.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.screentimetracker.app.data.db.entity.DailySummaryEntity
import com.screentimetracker.app.data.db.entity.DailyUsageEntity
import com.screentimetracker.app.data.db.entity.FocusBlockLogEntity
import com.screentimetracker.app.data.db.entity.FocusBlockedAppEntity
import com.screentimetracker.app.data.db.entity.FocusOverrideEntity
import com.screentimetracker.app.data.db.entity.FocusScheduleEntity

/**
 * Room database for persisting daily usage data and Focus Mode data.
 *
 * Stores:
 * - Per-app daily usage metrics (DailyUsageEntity)
 * - Aggregated daily summaries (DailySummaryEntity)
 * - Focus schedules and blocked apps (FocusScheduleEntity, FocusBlockedAppEntity)
 * - Focus overrides and block logs (FocusOverrideEntity, FocusBlockLogEntity)
 *
 * This enables reliable access to historical data even when
 * Android's UsageStatsManager data has been purged.
 */
@Database(
    entities = [
        DailyUsageEntity::class,
        DailySummaryEntity::class,
        FocusScheduleEntity::class,
        FocusBlockedAppEntity::class,
        FocusOverrideEntity::class,
        FocusBlockLogEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun dailyUsageDao(): DailyUsageDao
    abstract fun focusDao(): FocusDao

    companion object {
        private const val DATABASE_NAME = "screentime_db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration from version 1 to 2.
         * Adds Focus Mode tables: focus_schedule, focus_blocked_app, focus_override, focus_block_log
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create focus_schedule table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS focus_schedule (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL DEFAULT 1,
                        daysOfWeekMask INTEGER NOT NULL,
                        startTimeMinutes INTEGER NOT NULL,
                        endTimeMinutes INTEGER NOT NULL,
                        timezoneId TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())

                // Create focus_blocked_app table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS focus_blocked_app (
                        scheduleId INTEGER NOT NULL,
                        packageName TEXT NOT NULL,
                        addedAt INTEGER NOT NULL,
                        PRIMARY KEY(scheduleId, packageName),
                        FOREIGN KEY(scheduleId) REFERENCES focus_schedule(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                // Create indices for focus_blocked_app
                db.execSQL("CREATE INDEX IF NOT EXISTS index_focus_blocked_app_scheduleId ON focus_blocked_app(scheduleId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_focus_blocked_app_packageName ON focus_blocked_app(packageName)")

                // Create focus_override table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS focus_override (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        scheduleId INTEGER,
                        overrideType TEXT NOT NULL,
                        expiresAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())

                // Create indices for focus_override
                db.execSQL("CREATE INDEX IF NOT EXISTS index_focus_override_packageName ON focus_override(packageName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_focus_override_expiresAt ON focus_override(expiresAt)")

                // Create focus_block_log table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS focus_block_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        scheduleId INTEGER NOT NULL,
                        userAction TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        date TEXT NOT NULL
                    )
                """.trimIndent())

                // Create indices for focus_block_log
                db.execSQL("CREATE INDEX IF NOT EXISTS index_focus_block_log_date ON focus_block_log(date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_focus_block_log_packageName ON focus_block_log(packageName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_focus_block_log_timestamp ON focus_block_log(timestamp)")
            }
        }

        /**
         * Get the singleton database instance.
         * Creates the database on first access using double-checked locking.
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2)
                .build()
        }

        /**
         * Close the database instance.
         * Should only be called when the app is terminating.
         */
        fun closeDatabase() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
