package com.screentimetracker.app.focus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.screentimetracker.app.data.db.AppDatabase
import com.screentimetracker.app.data.db.entity.FocusBlockLogEntity
import com.screentimetracker.app.data.db.entity.FocusOverrideEntity
import com.screentimetracker.app.focus.data.FocusRepository
import kotlinx.coroutines.launch

/**
 * ViewModel for the FocusBlockActivity.
 *
 * Handles user actions when a blocked app is intercepted:
 * - Go Back: Log the block and navigate home
 * - Allow once: Grant short-lived override (30 seconds) and log the action
 * - End schedule for today: Pause the schedule until midnight
 */
class FocusBlockViewModel(application: Application) : AndroidViewModel(application) {

    private val focusRepository: FocusRepository

    init {
        val db = AppDatabase.getInstance(application)
        focusRepository = FocusRepository(db.focusDao())
    }

    /**
     * Sealed class representing the result of a user action.
     */
    sealed class ActionResult {
        /** User chose to go back - navigate to home */
        object GoBack : ActionResult()

        /** User requested temporary override - allow the blocked app */
        object OverrideGranted : ActionResult()

        /** An error occurred during the action */
        data class Error(val message: String) : ActionResult()
    }

    private val _actionResult = MutableLiveData<ActionResult>()
    val actionResult: LiveData<ActionResult> = _actionResult

    /**
     * Handle "Go Back" button click.
     * Logs the block event and signals to navigate home.
     */
    fun onGoBack(packageName: String, scheduleId: Long) {
        viewModelScope.launch {
            try {
                focusRepository.logBlock(
                    packageName = packageName,
                    scheduleId = scheduleId,
                    userAction = FocusBlockLogEntity.ACTION_GO_BACK
                )
                _actionResult.value = ActionResult.GoBack
            } catch (e: Exception) {
                _actionResult.value = ActionResult.Error(e.message ?: "Failed to log block")
            }
        }
    }

    /**
     * Handle "Allow once" button click.
     * Grants a short-lived override (30 seconds) and logs the action.
     */
    fun onAllowOnce(packageName: String, scheduleId: Long) {
        viewModelScope.launch {
            try {
                focusRepository.grantOverride(
                    packageName = packageName,
                    overrideType = FocusOverrideEntity.TYPE_ALLOW_ONCE,
                    scheduleId = scheduleId
                )
                focusRepository.logBlock(
                    packageName = packageName,
                    scheduleId = scheduleId,
                    userAction = FocusBlockLogEntity.ACTION_ALLOW_ONCE
                )
                _actionResult.value = ActionResult.OverrideGranted
            } catch (e: Exception) {
                _actionResult.value = ActionResult.Error(e.message ?: "Failed to grant override")
            }
        }
    }

    /**
     * Handle "Allow 15 min" button click.
     * Grants a 15-minute override and logs the action.
     */
    fun onAllow15Min(packageName: String, scheduleId: Long) {
        viewModelScope.launch {
            try {
                focusRepository.grantOverride(
                    packageName = packageName,
                    overrideType = FocusOverrideEntity.TYPE_ALLOW_15_MIN,
                    scheduleId = scheduleId
                )
                focusRepository.logBlock(
                    packageName = packageName,
                    scheduleId = scheduleId,
                    userAction = FocusBlockLogEntity.ACTION_ALLOW_15_MIN
                )
                _actionResult.value = ActionResult.OverrideGranted
            } catch (e: Exception) {
                _actionResult.value = ActionResult.Error(e.message ?: "Failed to grant override")
            }
        }
    }

    /**
     * Handle "End [schedule] for today" button click.
     * Pauses the schedule until midnight by creating overrides for all its blocked apps.
     */
    fun onEndScheduleToday(packageName: String, scheduleId: Long) {
        viewModelScope.launch {
            try {
                focusRepository.pauseScheduleForToday(scheduleId)
                focusRepository.logBlock(
                    packageName = packageName,
                    scheduleId = scheduleId,
                    userAction = FocusBlockLogEntity.ACTION_END_TODAY
                )
                _actionResult.value = ActionResult.OverrideGranted
            } catch (e: Exception) {
                _actionResult.value = ActionResult.Error(e.message ?: "Failed to pause schedule")
            }
        }
    }
}
