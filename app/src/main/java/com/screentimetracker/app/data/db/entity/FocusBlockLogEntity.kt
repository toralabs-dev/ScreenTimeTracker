package com.screentimetracker.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity for audit logging of block events.
 * Records when users attempt to open blocked apps and what action they took.
 */
@Entity(
    tableName = "focus_block_log",
    indices = [
        Index(value = ["date"]),
        Index(value = ["packageName"]),
        Index(value = ["timestamp"])
    ]
)
data class FocusBlockLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Package name of the app that was blocked */
    val packageName: String,

    /** Schedule ID that triggered the block */
    val scheduleId: Long,

    /**
     * User action taken: "GO_BACK", "ALLOW_5_MIN", "ALLOW_15_MIN", "ALLOW_ONCE"
     */
    val userAction: String,

    /** Timestamp of the block event */
    val timestamp: Long = System.currentTimeMillis(),

    /** ISO date format (YYYY-MM-DD) for easy daily querying */
    val date: String
) {
    companion object {
        const val ACTION_GO_BACK = "GO_BACK"
        const val ACTION_ALLOW_5_MIN = "ALLOW_5_MIN"
        const val ACTION_ALLOW_15_MIN = "ALLOW_15_MIN"
        const val ACTION_ALLOW_ONCE = "ALLOW_ONCE"
        const val ACTION_END_TODAY = "END_TODAY"
    }
}
