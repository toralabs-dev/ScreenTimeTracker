package com.screentimetracker.app.data.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.util.Log
import com.screentimetracker.app.BuildConfig
import com.screentimetracker.app.data.model.SessionInfo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Repository for extracting usage sessions from UsageEvents with proper cross-midnight handling.
 *
 * KEY DESIGN PRINCIPLES:
 * 1. Sessions preserve REAL start/end times (never clipped to day boundaries)
 * 2. Sessions that overlap the target window are returned (even if started yesterday)
 * 3. "Today total" and "longest session" are computed using OVERLAP duration
 * 4. No synthetic sessions with fabricated timestamps
 * 5. Canonical event stream: prefer ACTIVITY_* over MOVE_TO_* to avoid double-counting
 * 6. Post-processing normalization to merge overlaps and remove duplicates
 *
 * For a session that started at 11:58pm yesterday and ended at 12:05am today:
 * - startTimeMillis = 11:58pm yesterday (REAL)
 * - endTimeMillis = 12:05am today (REAL)
 * - overlapDuration([midnight, now]) = 5 minutes
 * - Full duration = 7 minutes
 */
object UsageSessionRepository {

    private const val TAG = "SessionValidate"

    /** Buffer to query before window start (6 hours) */
    private const val DEFAULT_QUERY_BUFFER_MS = 6 * 60 * 60 * 1000L

    /** Default minimum session duration (1 minute) */
    const val MIN_SESSION_MILLIS = 60_000L

    /** Jitter threshold for merging nearby sessions (2 seconds) */
    private const val JITTER_THRESHOLD_MS = 2000L

    /** Event type constants - note: ACTIVITY_RESUMED == MOVE_TO_FOREGROUND == 1 */
    private const val ACTIVITY_RESUMED = 1  // UsageEvents.Event.ACTIVITY_RESUMED
    private const val ACTIVITY_PAUSED = 2   // UsageEvents.Event.ACTIVITY_PAUSED
    private const val ACTIVITY_STOPPED = 23
    private const val MOVE_TO_FOREGROUND = 1
    private const val MOVE_TO_BACKGROUND = 2

    /**
     * Get the start of today in local timezone (midnight).
     */
    fun getStartOfTodayMillis(): Long {
        return LocalDate.now(ZoneId.systemDefault())
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Get current time in milliseconds.
     */
    fun getCurrentTimeMillis(): Long = System.currentTimeMillis()

    /**
     * Get start of a specific day in local timezone.
     */
    fun getStartOfDayMillis(dateMillis: Long): Long {
        return Instant.ofEpochMilli(dateMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Get end of a specific day in local timezone (23:59:59.999).
     */
    fun getEndOfDayMillis(dateMillis: Long): Long {
        return Instant.ofEpochMilli(dateMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli() - 1
    }

    /**
     * Queries sessions that OVERLAP with the given time window.
     *
     * Returns sessions with REAL start/end times. Sessions that started before
     * windowStart but ended after windowStart are included (cross-midnight sessions).
     *
     * Uses canonical event stream selection to avoid double-counting:
     * - For each package, prefers ACTIVITY_RESUMED/PAUSED/STOPPED events
     * - Falls back to MOVE_TO_FOREGROUND/BACKGROUND only if no ACTIVITY events exist
     *
     * Post-processes sessions to normalize overlaps, merge jitter gaps, and remove duplicates.
     *
     * @param usageStatsManager The UsageStatsManager to query events from
     * @param windowStart Start of the target window (e.g., midnight)
     * @param windowEnd End of the target window (e.g., now)
     * @param trackedPackages Set of package names to include (null = all packages)
     * @param appNameResolver Function to resolve package name to display name
     * @param minOverlapMillis Minimum overlap with window to include session
     * @param queryBufferMillis Buffer time to query before windowStart
     * @return List of SessionInfo with REAL timestamps, sorted by start time descending
     */
    /**
     * Simple data class to store event data since UsageEvents.Event is reused.
     */
    private data class EventData(
        val packageName: String,
        val eventType: Int,
        val timeStamp: Long
    )

    fun getSessionsOverlappingWindow(
        usageStatsManager: UsageStatsManager,
        windowStart: Long,
        windowEnd: Long,
        trackedPackages: Set<String>?,
        appNameResolver: (String) -> String,
        minOverlapMillis: Long = MIN_SESSION_MILLIS,
        queryBufferMillis: Long = DEFAULT_QUERY_BUFFER_MS
    ): List<SessionInfo> {
        // Query events starting from buffer time before window
        val queryStart = windowStart - queryBufferMillis
        val usageEvents = usageStatsManager.queryEvents(queryStart, windowEnd)

        // First pass: collect all events grouped by package as EventData
        // (UsageEvents.Event is reused, so we must copy data immediately)
        val eventsByPackage = mutableMapOf<String, MutableList<EventData>>()

        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val packageName = event.packageName

            // Filter by tracked packages if specified
            if (trackedPackages != null && !trackedPackages.contains(packageName)) {
                continue
            }

            // Only collect foreground/background related events
            if (isRelevantEventType(event.eventType)) {
                val events = eventsByPackage.getOrPut(packageName) { mutableListOf() }
                // Store as EventData since Event object is reused
                events.add(EventData(
                    packageName = packageName,
                    eventType = event.eventType,
                    timeStamp = event.timeStamp
                ))
            }
        }

        // Second pass: reconstruct sessions per package using canonical stream
        val allSessions = mutableListOf<SessionInfo>()

        for ((packageName, eventDataList) in eventsByPackage) {
            val appName = appNameResolver(packageName)
            val rawSessions = reconstructSessionsForPackage(
                packageName = packageName,
                appName = appName,
                eventDataList = eventDataList,
                queryStart = queryStart,
                windowEnd = windowEnd
            )

            // Normalize sessions (merge overlaps, remove duplicates, etc.)
            val normalizedSessions = normalizeSessions(rawSessions, windowEnd)

            // Debug validation (only in debug builds)
            if (BuildConfig.DEBUG) {
                validateSessions(packageName, normalizedSessions)
            }

            // Filter by overlap with window and minimum duration
            for (session in normalizedSessions) {
                if (session.overlapsWindow(windowStart, windowEnd)) {
                    val overlap = session.overlapDuration(windowStart, windowEnd)
                    if (overlap >= minOverlapMillis) {
                        allSessions.add(session)
                    }
                }
            }
        }

        return allSessions.sortedByDescending { it.startTimeMillis }
    }

    /**
     * Checks if event type is relevant for session reconstruction.
     */
    private fun isRelevantEventType(eventType: Int): Boolean {
        return eventType == ACTIVITY_RESUMED ||
                eventType == ACTIVITY_PAUSED ||
                eventType == ACTIVITY_STOPPED ||
                eventType == MOVE_TO_FOREGROUND ||
                eventType == MOVE_TO_BACKGROUND
    }

    /**
     * Reconstructs sessions for a single package using canonical event stream.
     *
     * Strategy:
     * - Check if ACTIVITY_STOPPED events exist (unique to ACTIVITY stream)
     * - If yes: use ACTIVITY_RESUMED as start, ACTIVITY_PAUSED/STOPPED as end
     * - If no: use MOVE_TO_FOREGROUND as start, MOVE_TO_BACKGROUND as end
     *
     * This avoids double-counting from mixed event streams.
     */
    private fun reconstructSessionsForPackage(
        packageName: String,
        appName: String,
        eventDataList: List<EventData>,
        queryStart: Long,
        windowEnd: Long
    ): List<SessionInfo> {
        if (eventDataList.isEmpty()) return emptyList()

        // Sort by timestamp
        val sortedEvents = eventDataList.sortedBy { it.timeStamp }

        // Determine which event stream to use
        // We use ACTIVITY_STOPPED (23) as the discriminator since it's unique to ACTIVITY stream
        val hasActivityStream = sortedEvents.any { it.eventType == ACTIVITY_STOPPED }

        // Define which event types to use for start/end
        val startTypes: Set<Int>
        val endTypes: Set<Int>

        if (hasActivityStream) {
            // Use ACTIVITY stream: ACTIVITY_RESUMED (1) -> ACTIVITY_PAUSED (2) or ACTIVITY_STOPPED (23)
            startTypes = setOf(ACTIVITY_RESUMED)
            endTypes = setOf(ACTIVITY_PAUSED, ACTIVITY_STOPPED)

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "[$packageName] Using ACTIVITY stream (found ACTIVITY_STOPPED events)")
            }
        } else {
            // Use MOVE_TO stream: MOVE_TO_FOREGROUND (1) -> MOVE_TO_BACKGROUND (2)
            startTypes = setOf(MOVE_TO_FOREGROUND)
            endTypes = setOf(MOVE_TO_BACKGROUND)

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "[$packageName] Using MOVE_TO stream (no ACTIVITY_STOPPED events)")
            }
        }

        // Reconstruct sessions
        val sessions = mutableListOf<SessionInfo>()
        var activeSessionStart: Long? = null
        var hasSeenStart = false

        for (eventData in eventDataList) {
            when {
                eventData.eventType in startTypes -> {
                    // Start of a new session
                    activeSessionStart = eventData.timeStamp
                    hasSeenStart = true
                }
                eventData.eventType in endTypes -> {
                    if (activeSessionStart != null) {
                        // Normal case: matched start and end
                        sessions.add(
                            SessionInfo(
                                packageName = packageName,
                                appName = appName,
                                startTimeMillis = activeSessionStart,
                                endTimeMillis = eventData.timeStamp,
                                isInferredStart = false
                            )
                        )
                        activeSessionStart = null
                    } else if (!hasSeenStart) {
                        // Orphan end event: app was active before query started
                        // Infer start as queryStart
                        sessions.add(
                            SessionInfo(
                                packageName = packageName,
                                appName = appName,
                                startTimeMillis = queryStart,
                                endTimeMillis = eventData.timeStamp,
                                isInferredStart = true
                            )
                        )
                        hasSeenStart = true // Prevent multiple orphan sessions
                    }
                    // Else: duplicate end event, ignore
                }
            }
        }

        // Handle still-active session (no end event)
        if (activeSessionStart != null) {
            sessions.add(
                SessionInfo(
                    packageName = packageName,
                    appName = appName,
                    startTimeMillis = activeSessionStart,
                    endTimeMillis = windowEnd,
                    isInferredStart = false
                )
            )
        }

        return sessions
    }

    /**
     * Normalizes a list of sessions for a single package:
     * 1. Sort by start time ascending
     * 2. Remove zero/negative duration sessions
     * 3. Clamp end times to windowEnd
     * 4. Merge overlapping sessions
     * 5. Merge jitter gaps (sessions < 2 seconds apart)
     * 6. Remove exact duplicates
     *
     * @param sessions Raw sessions for a single package
     * @param windowEnd Maximum end time (for clamping)
     * @return Normalized session list with no overlaps or duplicates
     */
    private fun normalizeSessions(
        sessions: List<SessionInfo>,
        windowEnd: Long
    ): List<SessionInfo> {
        if (sessions.isEmpty()) return emptyList()

        // Step 1: Sort by start time
        val sorted = sessions.sortedBy { it.startTimeMillis }.toMutableList()

        // Step 2 & 3: Filter and clamp
        val filtered = sorted.mapNotNull { session ->
            val clampedEnd = minOf(session.endTimeMillis, windowEnd)
            if (clampedEnd <= session.startTimeMillis) {
                null // Zero or negative duration, remove
            } else {
                if (clampedEnd != session.endTimeMillis) {
                    session.copy(endTimeMillis = clampedEnd)
                } else {
                    session
                }
            }
        }.toMutableList()

        if (filtered.isEmpty()) return emptyList()

        // Step 4 & 5: Merge overlaps and jitter gaps
        val merged = mutableListOf<SessionInfo>()
        var current = filtered[0]

        for (i in 1 until filtered.size) {
            val next = filtered[i]
            val gap = next.startTimeMillis - current.endTimeMillis

            when {
                // Overlap: next starts before or at current end
                gap <= 0 -> {
                    // Merge: extend current's end if next ends later
                    current = current.copy(
                        endTimeMillis = maxOf(current.endTimeMillis, next.endTimeMillis),
                        // Keep inferred flag if either was inferred
                        isInferredStart = current.isInferredStart || next.isInferredStart
                    )
                }
                // Jitter gap: small gap, merge as continuous
                gap <= JITTER_THRESHOLD_MS -> {
                    current = current.copy(
                        endTimeMillis = next.endTimeMillis,
                        isInferredStart = current.isInferredStart || next.isInferredStart
                    )
                }
                // Normal gap: save current and start new
                else -> {
                    merged.add(current)
                    current = next
                }
            }
        }
        merged.add(current)

        // Step 6: Remove exact duplicates (shouldn't be any after merge, but be safe)
        return merged.distinctBy { Pair(it.startTimeMillis, it.endTimeMillis) }
    }

    /**
     * Debug validator: logs any issues with the session list.
     * Only called in debug builds.
     */
    private fun validateSessions(packageName: String, sessions: List<SessionInfo>) {
        if (sessions.size < 2) return

        val sorted = sessions.sortedBy { it.startTimeMillis }
        var duplicateCount = 0
        var overlapCount = 0
        var suspiciousGapCount = 0

        for (i in 0 until sorted.size - 1) {
            val curr = sorted[i]
            val next = sorted[i + 1]

            // Check exact duplicate
            if (curr.startTimeMillis == next.startTimeMillis &&
                curr.endTimeMillis == next.endTimeMillis) {
                duplicateCount++
                Log.w(TAG, "[$packageName] DUPLICATE: ${formatTime(curr.startTimeMillis)} - ${formatTime(curr.endTimeMillis)}")
            }

            // Check overlap
            if (next.startTimeMillis < curr.endTimeMillis) {
                overlapCount++
                Log.w(TAG, "[$packageName] OVERLAP: " +
                        "curr=${formatTime(curr.startTimeMillis)}-${formatTime(curr.endTimeMillis)}, " +
                        "next=${formatTime(next.startTimeMillis)}-${formatTime(next.endTimeMillis)}")
            }

            // Check suspicious gap (0-5 seconds)
            val gap = next.startTimeMillis - curr.endTimeMillis
            if (gap in 1..5000) {
                suspiciousGapCount++
                Log.d(TAG, "[$packageName] SUSPICIOUS_GAP: ${gap}ms between " +
                        "${formatTime(curr.endTimeMillis)} and ${formatTime(next.startTimeMillis)}")
            }
        }

        // Summary log
        if (duplicateCount > 0 || overlapCount > 0) {
            Log.w(TAG, "[$packageName] VALIDATION: ${sessions.size} sessions, " +
                    "$duplicateCount duplicates, $overlapCount overlaps, $suspiciousGapCount suspicious gaps")
        } else {
            Log.d(TAG, "[$packageName] VALIDATION: ${sessions.size} sessions OK " +
                    "(0 duplicates, 0 overlaps, $suspiciousGapCount suspicious gaps)")
        }
    }

    /**
     * Formats timestamp for debug logging.
     */
    private fun formatTime(millis: Long): String {
        val instant = Instant.ofEpochMilli(millis)
        val zoned = instant.atZone(ZoneId.systemDefault())
        return String.format("%02d:%02d:%02d",
            zoned.hour, zoned.minute, zoned.second)
    }

    /**
     * Gets sessions for a specific app that overlap with the given window.
     */
    fun getSessionsForAppOverlappingWindow(
        usageStatsManager: UsageStatsManager,
        packageName: String,
        appName: String,
        windowStart: Long,
        windowEnd: Long,
        minOverlapMillis: Long = MIN_SESSION_MILLIS,
        queryBufferMillis: Long = DEFAULT_QUERY_BUFFER_MS
    ): List<SessionInfo> {
        return getSessionsOverlappingWindow(
            usageStatsManager = usageStatsManager,
            windowStart = windowStart,
            windowEnd = windowEnd,
            trackedPackages = setOf(packageName),
            appNameResolver = { appName },
            minOverlapMillis = minOverlapMillis,
            queryBufferMillis = queryBufferMillis
        )
    }

    /**
     * Computes total usage time within a window from sessions.
     * Uses OVERLAP duration (not full session duration).
     */
    fun computeTotalOverlapFromSessions(
        sessions: List<SessionInfo>,
        windowStart: Long,
        windowEnd: Long
    ): Long {
        return sessions.sumOf { it.overlapDuration(windowStart, windowEnd) }
    }

    /**
     * Finds the session with the longest overlap in the given window.
     * Returns null if no sessions overlap.
     */
    fun findLongestSessionByOverlap(
        sessions: List<SessionInfo>,
        windowStart: Long,
        windowEnd: Long
    ): SessionInfo? {
        return sessions.maxByOrNull { it.overlapDuration(windowStart, windowEnd) }
    }

    /**
     * Gets the longest overlap duration from the sessions.
     */
    fun getLongestOverlapDuration(
        sessions: List<SessionInfo>,
        windowStart: Long,
        windowEnd: Long
    ): Long {
        return sessions.maxOfOrNull { it.overlapDuration(windowStart, windowEnd) } ?: 0L
    }

    // ========== Multi-day Session Queries ==========

    /**
     * Data class representing sessions for a single day.
     */
    data class DaySessionData(
        val date: LocalDate,
        val windowStart: Long,
        val windowEnd: Long,
        val sessions: List<SessionInfo>,
        val totalOverlapMillis: Long
    )

    /**
     * Queries sessions for a date range (inclusive).
     * Returns a map of LocalDate to session data for each day.
     *
     * This is used for multi-day pattern analysis in recommendations.
     * Each day's sessions are computed with proper overlap handling.
     *
     * @param usageStatsManager The UsageStatsManager to query events from
     * @param startDate Start of the date range (inclusive)
     * @param endDate End of the date range (inclusive)
     * @param trackedPackages Set of package names to include
     * @param appNameResolver Function to resolve package name to display name
     * @param minOverlapMillis Minimum overlap with day to include session
     * @return Map of LocalDate to DaySessionData, sorted by date ascending
     */
    fun getSessionsForDateRange(
        usageStatsManager: UsageStatsManager,
        startDate: LocalDate,
        endDate: LocalDate,
        trackedPackages: Set<String>,
        appNameResolver: (String) -> String,
        minOverlapMillis: Long = MIN_SESSION_MILLIS
    ): Map<LocalDate, DaySessionData> {
        val result = mutableMapOf<LocalDate, DaySessionData>()
        var currentDate = startDate

        while (!currentDate.isAfter(endDate)) {
            val dayStart = currentDate.atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()

            // For today, use current time as end; for past days, use end of day
            val isToday = currentDate == LocalDate.now(ZoneId.systemDefault())
            val dayEnd = if (isToday) {
                System.currentTimeMillis()
            } else {
                currentDate.plusDays(1).atStartOfDay(ZoneId.systemDefault())
                    .toInstant().toEpochMilli() - 1
            }

            val sessions = getSessionsOverlappingWindow(
                usageStatsManager = usageStatsManager,
                windowStart = dayStart,
                windowEnd = dayEnd,
                trackedPackages = trackedPackages,
                appNameResolver = appNameResolver,
                minOverlapMillis = minOverlapMillis
            )

            val totalOverlap = computeTotalOverlapFromSessions(sessions, dayStart, dayEnd)

            result[currentDate] = DaySessionData(
                date = currentDate,
                windowStart = dayStart,
                windowEnd = dayEnd,
                sessions = sessions,
                totalOverlapMillis = totalOverlap
            )

            currentDate = currentDate.plusDays(1)
        }

        return result.toSortedMap()
    }

    /**
     * Gets sessions for the past N days including today.
     * Convenience method for multi-day recommendation analysis.
     *
     * @param usageStatsManager The UsageStatsManager to query events from
     * @param days Number of days to include (1 = today only, 3 = today + 2 previous)
     * @param trackedPackages Set of package names to include
     * @param appNameResolver Function to resolve package name to display name
     * @return Map of LocalDate to DaySessionData
     */
    fun getSessionsForPastDays(
        usageStatsManager: UsageStatsManager,
        days: Int,
        trackedPackages: Set<String>,
        appNameResolver: (String) -> String
    ): Map<LocalDate, DaySessionData> {
        val today = LocalDate.now(ZoneId.systemDefault())
        val startDate = today.minusDays((days - 1).toLong())

        return getSessionsForDateRange(
            usageStatsManager = usageStatsManager,
            startDate = startDate,
            endDate = today,
            trackedPackages = trackedPackages,
            appNameResolver = appNameResolver
        )
    }

    // ========== Legacy method names for backward compatibility ==========

    /**
     * @deprecated Use getSessionsOverlappingWindow instead
     */
    fun getSessionsForWindow(
        usageStatsManager: UsageStatsManager,
        windowStart: Long,
        windowEnd: Long,
        trackedPackages: Set<String>?,
        appNameResolver: (String) -> String,
        minSessionMillis: Long = MIN_SESSION_MILLIS,
        queryBufferMillis: Long = DEFAULT_QUERY_BUFFER_MS
    ): List<SessionInfo> = getSessionsOverlappingWindow(
        usageStatsManager, windowStart, windowEnd, trackedPackages,
        appNameResolver, minSessionMillis, queryBufferMillis
    )

    /**
     * @deprecated Use getSessionsForAppOverlappingWindow instead
     */
    fun getSessionsForAppInWindow(
        usageStatsManager: UsageStatsManager,
        packageName: String,
        appName: String,
        windowStart: Long,
        windowEnd: Long,
        minSessionMillis: Long = MIN_SESSION_MILLIS,
        queryBufferMillis: Long = DEFAULT_QUERY_BUFFER_MS
    ): List<SessionInfo> = getSessionsForAppOverlappingWindow(
        usageStatsManager, packageName, appName, windowStart, windowEnd,
        minSessionMillis, queryBufferMillis
    )

    /**
     * @deprecated Use computeTotalOverlapFromSessions instead
     */
    fun computeTotalFromSessions(sessions: List<SessionInfo>): Long {
        // For backward compatibility, compute overlap with a very wide window
        // This effectively returns full duration for most cases
        val windowStart = getStartOfTodayMillis()
        val windowEnd = getCurrentTimeMillis()
        return computeTotalOverlapFromSessions(sessions, windowStart, windowEnd)
    }
}
