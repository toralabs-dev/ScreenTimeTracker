package com.screentimetracker.app.ui.main

import android.view.View
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.screentimetracker.app.data.model.SessionInfo
import com.screentimetracker.app.data.usage.UsageSessionRepository

/**
 * Controller responsible for managing session cards in the dashboard.
 * Extracted from MainActivity to reduce god class complexity.
 */
class SessionCardsController(
    private val titleView: View,
    private val card1: MaterialCardView,
    private val card2: MaterialCardView,
    private val card3: MaterialCardView,
    private val session1Name: MaterialTextView,
    private val session1Duration: MaterialTextView,
    private val session1TimeRange: MaterialTextView,
    private val session2Name: MaterialTextView,
    private val session2Duration: MaterialTextView,
    private val session2TimeRange: MaterialTextView,
    private val session3Name: MaterialTextView,
    private val session3Duration: MaterialTextView,
    private val session3TimeRange: MaterialTextView
) {

    /**
     * Updates the longest sessions cards with session data.
     */
    fun updateSessions(sessions: List<SessionInfo>) {
        // Hide all session cards initially
        card1.visibility = View.GONE
        card2.visibility = View.GONE
        card3.visibility = View.GONE

        // Show and populate cards for available sessions
        if (sessions.isNotEmpty()) {
            titleView.visibility = View.VISIBLE
            bindSession(0, sessions.getOrNull(0))
            bindSession(1, sessions.getOrNull(1))
            bindSession(2, sessions.getOrNull(2))
        } else {
            titleView.visibility = View.GONE
        }
    }

    /**
     * Binds session data to the appropriate card view.
     * For cross-midnight sessions, shows "Today: Xm" to indicate only today's portion.
     */
    private fun bindSession(index: Int, session: SessionInfo?) {
        if (session == null) return

        // Get window times for overlap calculation
        val windowStart = UsageSessionRepository.getStartOfTodayMillis()
        val windowEnd = UsageSessionRepository.getCurrentTimeMillis()

        // For cross-midnight sessions, show "Today: Xm"
        val durationText = if (session.startedBeforeWindow(windowStart)) {
            "Today: ${session.getFormattedOverlapDuration(windowStart, windowEnd)}"
        } else {
            session.getFormattedDuration()
        }

        when (index) {
            0 -> {
                card1.visibility = View.VISIBLE
                session1Name.text = session.appName
                session1Duration.text = durationText
                session1TimeRange.text = session.getFormattedTimeRange()
            }
            1 -> {
                card2.visibility = View.VISIBLE
                session2Name.text = session.appName
                session2Duration.text = durationText
                session2TimeRange.text = session.getFormattedTimeRange()
            }
            2 -> {
                card3.visibility = View.VISIBLE
                session3Name.text = session.appName
                session3Duration.text = durationText
                session3TimeRange.text = session.getFormattedTimeRange()
            }
        }
    }
}
