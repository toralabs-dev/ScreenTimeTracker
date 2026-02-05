package com.screentimetracker.app.data.db.entity

import androidx.room.Entity

/**
 * Entity representing daily usage data for a single app.
 * Stores aggregated usage metrics for each tracked app per day.
 *
 * Primary key is composite: (date, packageName) to ensure one entry per app per day.
 */
@Entity(
    tableName = "daily_usage",
    primaryKeys = ["date", "packageName"]
)
data class DailyUsageEntity(
    /** Date in ISO format "YYYY-MM-DD" (e.g., "2025-01-26") */
    val date: String,

    /** Package name of the app (e.g., "com.instagram.android") */
    val packageName: String,

    /** Total foreground time in milliseconds for this day */
    val totalMillis: Long,

    /** Number of distinct sessions for this day */
    val sessionCount: Int,

    /** Duration of the longest single session in milliseconds */
    val longestSessionMillis: Long,

    /** Timestamp when this record was last synced/updated */
    val syncedAt: Long
)
