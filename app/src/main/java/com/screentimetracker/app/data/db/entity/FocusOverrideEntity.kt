package com.screentimetracker.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing a temporary override allowing a blocked app.
 * Created when user chooses "Allow for 5 min", "Allow for 15 min", or "Allow once".
 */
@Entity(
    tableName = "focus_override",
    indices = [
        Index(value = ["packageName"]),
        Index(value = ["expiresAt"])
    ]
)
data class FocusOverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Package name of the app being temporarily allowed */
    val packageName: String,

    /**
     * Schedule ID this override applies to.
     * null = applies to all schedules
     */
    val scheduleId: Long? = null,

    /**
     * Type of override: "ALLOW_5_MIN", "ALLOW_15_MIN", "ALLOW_ONCE"
     */
    val overrideType: String,

    /** Timestamp when this override expires and blocking resumes */
    val expiresAt: Long,

    /** Timestamp when this override was created */
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_ALLOW_5_MIN = "ALLOW_5_MIN"
        const val TYPE_ALLOW_15_MIN = "ALLOW_15_MIN"
        const val TYPE_ALLOW_ONCE = "ALLOW_ONCE"
        const val TYPE_END_TODAY = "END_TODAY"

        const val DURATION_5_MIN_MS = 5 * 60 * 1000L
        const val DURATION_15_MIN_MS = 15 * 60 * 1000L
        const val DURATION_ALLOW_ONCE_MS = 30 * 1000L  // 30 seconds
    }
}
