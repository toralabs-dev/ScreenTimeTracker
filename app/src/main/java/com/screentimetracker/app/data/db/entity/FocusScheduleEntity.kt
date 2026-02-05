package com.screentimetracker.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a Focus Mode schedule.
 * Users can create multiple schedules (e.g., "Work Hours", "Study Time")
 * with different days and time windows.
 */
@Entity(tableName = "focus_schedule")
data class FocusScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** User-defined name for the schedule (e.g., "Work Hours", "Study Time") */
    val name: String,

    /** Whether this schedule is currently active */
    val isEnabled: Boolean = true,

    /**
     * Bitmask for days of week when this schedule applies.
     * Sun=1, Mon=2, Tue=4, Wed=8, Thu=16, Fri=32, Sat=64
     * Example: 62 = Mon-Fri (2+4+8+16+32)
     */
    val daysOfWeekMask: Int,

    /** Start time in minutes from midnight (0-1439). Example: 540 = 9:00 AM */
    val startTimeMinutes: Int,

    /**
     * End time in minutes from midnight (0-1439).
     * If less than startTimeMinutes, the schedule wraps past midnight.
     */
    val endTimeMinutes: Int,

    /** Timezone ID when schedule was created (for DST handling) */
    val timezoneId: String,

    /** Timestamp when this schedule was created */
    val createdAt: Long,

    /** Timestamp when this schedule was last updated */
    val updatedAt: Long
)
