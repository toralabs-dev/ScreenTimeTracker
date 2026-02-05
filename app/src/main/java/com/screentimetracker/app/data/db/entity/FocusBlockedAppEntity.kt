package com.screentimetracker.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Entity representing an app that is blocked during a Focus schedule.
 * Each schedule can have multiple blocked apps.
 */
@Entity(
    tableName = "focus_blocked_app",
    primaryKeys = ["scheduleId", "packageName"],
    foreignKeys = [
        ForeignKey(
            entity = FocusScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["scheduleId"]),
        Index(value = ["packageName"])
    ]
)
data class FocusBlockedAppEntity(
    /** The schedule this blocked app belongs to */
    val scheduleId: Long,

    /** Package name of the blocked app (e.g., "com.instagram.android") */
    val packageName: String,

    /** Timestamp when this app was added to the block list */
    val addedAt: Long = System.currentTimeMillis()
)
