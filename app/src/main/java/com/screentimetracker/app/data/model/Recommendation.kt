package com.screentimetracker.app.data.model

/**
 * Data class representing a smart recommendation for the user.
 *
 * Designed for AI-ready architecture: the structured model supports future ML/LLM enhancement.
 * The metadata field allows passing arbitrary context without model changes.
 *
 * @param type The type of recommendation (determines category and priority)
 * @param insightText The insight text shown to the user (e.g., "You spend 45% more...")
 * @param ctaText The call-to-action button text (e.g., "Start Focus Mode at 2 PM")
 * @param ctaAction The action to perform when CTA is tapped
 * @param priority Higher priority recommendations are shown first (0 = lowest)
 * @param metadata Flexible data for action parameters and future AI context
 */
data class Recommendation(
    val type: RecommendationType,
    val insightText: String,
    val ctaText: String,
    val ctaAction: RecommendationAction,
    val priority: Int = 0,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Types of recommendations the system can generate.
 * Each type corresponds to a specific usage pattern and actionable response.
 */
enum class RecommendationType {
    /** Detected high usage in a specific 2-hour time window */
    PEAK_HOUR,

    /** One app dominates screen time (50%+ of total) */
    DOMINANT_APP,

    /** Significant usage after 8 PM */
    EVENING_USAGE,

    /** Exceeded daily limit multiple consecutive days */
    OVER_LIMIT_STREAK,

    /** User returns to same app within 30 minutes frequently */
    QUICK_RETURN,

    /** Weekend usage significantly higher than weekdays */
    WEEKEND_SPIKE,

    /** Usage is decreasing compared to previous period */
    IMPROVING
}

/**
 * Sealed class representing the action to perform when user taps the CTA.
 * Each action type contains the data needed to execute it.
 */
sealed class RecommendationAction {

    /**
     * Create a new Focus Mode schedule with suggested parameters.
     *
     * @param suggestedName Human-readable name for the schedule (e.g., "Afternoon Focus")
     * @param startTimeMinutes Schedule start time in minutes from midnight (e.g., 840 for 2 PM)
     * @param endTimeMinutes Schedule end time in minutes from midnight (e.g., 960 for 4 PM)
     * @param daysOfWeekMask Bitmask of active days (1=Sun, 2=Mon, 4=Tue, ... 64=Sat)
     * @param blockedPackages List of package names to block during this schedule
     */
    data class CreateFocusSchedule(
        val suggestedName: String,
        val startTimeMinutes: Int,
        val endTimeMinutes: Int,
        val daysOfWeekMask: Int,
        val blockedPackages: List<String>
    ) : RecommendationAction()

    /**
     * Open the Settings screen, optionally with a suggested daily limit.
     *
     * @param suggestedLimitMillis Optional suggested daily limit in milliseconds
     */
    data class OpenSettings(
        val suggestedLimitMillis: Long? = null
    ) : RecommendationAction()

    /**
     * Start a temporary break from a specific app.
     *
     * @param packageName The app to take a break from
     * @param durationMillis How long the break should last
     */
    data class StartBreak(
        val packageName: String,
        val durationMillis: Long
    ) : RecommendationAction()

    /**
     * Celebratory action for positive patterns (e.g., improving usage).
     * Simply dismisses the card with a nice animation - no further action needed.
     */
    object Celebratory : RecommendationAction()
}
