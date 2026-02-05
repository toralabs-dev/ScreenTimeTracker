package com.screentimetracker.app.ui.appdetail

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.google.android.material.color.MaterialColors
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.screentimetracker.app.R
import com.screentimetracker.app.data.model.AppDetailState
import com.screentimetracker.app.data.model.DailyUsageEntry
import com.screentimetracker.app.data.usage.UsageSessionRepository
import com.screentimetracker.app.databinding.ActivityAppDetailBinding
import com.screentimetracker.app.ui.chart.UsageMarkerView

/**
 * Activity displaying detailed usage information for a single app.
 *
 * Shows:
 * - Today's summary (total time, session count, longest session)
 * - Today's sessions list
 * - Usage trend chart for selected period (7/14/30 days)
 */
class AppDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppDetailBinding
    private val viewModel: AppDetailViewModel by viewModels()
    private lateinit var sessionsAdapter: SessionsAdapter

    private var currentDailyEntries: List<DailyUsageEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityAppDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: run {
            finish()
            return
        }
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: packageName

        setupWindowInsets()
        setupToolbar(appName)
        setupSessionsRecyclerView()
        setupTrendChart()
        setupTrendChipGroup()
        observeViewModel()

        // Initialize ViewModel with app info
        viewModel.init(packageName, appName)
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadTodayData()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun setupToolbar(appName: String) {
        binding.topAppBar.title = appName
        binding.topAppBar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupSessionsRecyclerView() {
        sessionsAdapter = SessionsAdapter()
        binding.sessionsRecyclerView.adapter = sessionsAdapter
    }

    private fun setupTrendChart() {
        binding.trendLineChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            setDrawGridBackground(false)
            isDragEnabled = true
            setScaleEnabled(false)
            setPinchZoom(false)
            legend.isEnabled = false

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                textColor = MaterialColors.getColor(this@AppDetailActivity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
                textSize = 10f
            }

            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = MaterialColors.getColor(this@AppDetailActivity, com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY)
                textColor = MaterialColors.getColor(this@AppDetailActivity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
                textSize = 10f
                axisMinimum = 0f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        val minutes = value.toInt()
                        return if (minutes >= 60) {
                            "${minutes / 60}h"
                        } else {
                            "${minutes}m"
                        }
                    }
                }
            }

            axisRight.isEnabled = false

            // Using top-level class with lambda to avoid Activity memory leak
            marker = UsageMarkerView(this@AppDetailActivity) { currentDailyEntries }
            setExtraOffsets(8f, 8f, 8f, 8f)
        }
    }

    private fun setupTrendChipGroup() {
        binding.trendRangeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val days = when (checkedIds[0]) {
                    R.id.chip7Days -> 7
                    R.id.chip14Days -> 14
                    R.id.chip30Days -> 30
                    else -> 7
                }
                viewModel.loadTrendData(days)
            }
        }
    }

    private fun observeViewModel() {
        viewModel.state.observe(this) { state ->
            updateUI(state)
        }
    }

    private fun updateUI(state: AppDetailState) {
        // Show/hide loading indicator
        binding.loadingIndicator.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        if (state.isLoading) {
            return
        }

        // Update summary card - Total Time (always visible)
        binding.totalTimeValue.text = state.getFormattedTotalTime()

        // Sessions column: hide if sessionCount <= 0
        val showSessions = state.sessionCountToday > 0
        binding.sessionsColumn.visibility = if (showSessions) View.VISIBLE else View.GONE
        binding.sessionsDivider.visibility = if (showSessions) View.VISIBLE else View.GONE
        if (showSessions) {
            binding.sessionCountValue.text = state.sessionCountToday.toString()
        }

        // Longest Session column: hide if < 1 minute (60_000 ms)
        val showLongest = state.longestSessionMillisToday >= 60_000L
        binding.longestSessionColumn.visibility = if (showLongest) View.VISIBLE else View.GONE
        binding.longestSessionDivider.visibility = if (showLongest) View.VISIBLE else View.GONE
        if (showLongest) {
            binding.longestSessionValue.text = state.getFormattedLongestSession()
        }

        // Update sessions list
        if (state.hasSessionData()) {
            binding.sessionsRecyclerView.visibility = View.VISIBLE
            binding.sessionsEmptyCard.visibility = View.GONE
            // Set window times for proper overlap duration display
            // Cross-midnight sessions will show "Today: Xm"
            sessionsAdapter.setWindow(
                UsageSessionRepository.getStartOfTodayMillis(),
                UsageSessionRepository.getCurrentTimeMillis()
            )
            sessionsAdapter.submitList(state.sessionsToday)
        } else {
            binding.sessionsRecyclerView.visibility = View.GONE
            binding.sessionsEmptyCard.visibility = View.VISIBLE
        }

        // Update trend section
        updateTrendSection(state)
    }

    private fun updateTrendSection(state: AppDetailState) {
        binding.trendLoadingIndicator.visibility = if (state.isTrendLoading) View.VISIBLE else View.GONE

        if (state.isTrendLoading) {
            return
        }

        if (state.hasTrendData()) {
            binding.trendCard.visibility = View.VISIBLE
            binding.trendEmptyCard.visibility = View.GONE

            binding.trendAverageText.text = getString(
                R.string.app_detail_trend_average,
                state.getFormattedAverageUsage()
            )

            updateTrendChart(state.trendEntries)
        } else {
            binding.trendCard.visibility = View.GONE
            binding.trendEmptyCard.visibility = View.VISIBLE
        }
    }

    private fun updateTrendChart(dailyEntries: List<DailyUsageEntry>) {
        currentDailyEntries = dailyEntries

        val entries = dailyEntries.mapIndexed { index, entry ->
            Entry(index.toFloat(), (entry.totalMillis / 1000 / 60).toFloat())
        }

        val primaryColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.BLUE)
        val surfaceColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val dataSet = LineDataSet(entries, "Daily Usage").apply {
            color = primaryColor
            lineWidth = 2.5f
            setDrawCircles(true)
            circleRadius = 4f
            setCircleColor(primaryColor)
            circleHoleColor = surfaceColor
            circleHoleRadius = 2f
            setDrawValues(false)
            setDrawFilled(true)
            fillColor = primaryColor
            fillAlpha = 30
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.2f
            setDrawHighlightIndicators(true)
            highLightColor = primaryColor
            highlightLineWidth = 1f
        }

        binding.trendLineChart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val index = value.toInt()
                return if (index in dailyEntries.indices) {
                    dailyEntries[index].getFormattedDate()
                } else {
                    ""
                }
            }
        }

        val labelCount = when {
            dailyEntries.size <= 7 -> dailyEntries.size
            dailyEntries.size <= 14 -> 7
            else -> 6
        }
        binding.trendLineChart.xAxis.labelCount = labelCount

        binding.trendLineChart.data = LineData(dataSet)
        binding.trendLineChart.invalidate()
        binding.trendLineChart.animateX(500)
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_APP_NAME = "extra_app_name"
    }
}
