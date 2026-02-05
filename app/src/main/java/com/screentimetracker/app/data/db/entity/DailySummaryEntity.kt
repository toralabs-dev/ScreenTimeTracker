package com.screentimetracker.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing aggregated daily usage summary across all tracked apps.
 * Provides quick access to daily totals without needing to aggregate per-app data.
 */
@Entity(tableName = "daily_summary")
data class DailySummaryEntity(
    /** Date in ISO format "YYYY-MM-DD" (e.g., "2025-01-26") - unique per day */
    @PrimaryKey
    val date: String,

    /** Total foreground time across all tracked apps in milliseconds */
    val totalMillis: Long,

    /** Total number of sessions across all tracked apps */
    val sessionCount: Int,

    /** Number of distinct apps used on this day */
    val appCount: Int,

    /** Timestamp when this record was last synced/updated */
    val syncedAt: Long
)
