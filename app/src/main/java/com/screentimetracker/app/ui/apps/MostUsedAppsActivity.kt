package com.screentimetracker.app.ui.apps

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.screentimetracker.app.databinding.ActivityMostUsedAppsBinding
import com.screentimetracker.app.ui.appdetail.AppDetailActivity

/**
 * Activity displaying the full ranked list of tracked apps for today.
 * Allows navigation to individual app detail screens.
 */
class MostUsedAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMostUsedAppsBinding
    private val viewModel: MostUsedAppsViewModel by viewModels()
    private lateinit var adapter: MostUsedAppsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMostUsedAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindowInsets()
        setupToolbar()
        setupRecyclerView()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadTodayUsage()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun setupToolbar() {
        binding.topAppBar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        adapter = MostUsedAppsAdapter { packageName, appName ->
            openAppDetail(packageName, appName)
        }
        binding.appsRecyclerView.adapter = adapter
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { isLoading ->
            binding.loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.appUsageList.observe(this) { appList ->
            adapter.submitList(appList)

            // Show empty state if no apps
            if (appList.isEmpty()) {
                binding.emptyStateCard.visibility = View.VISIBLE
                binding.appsRecyclerView.visibility = View.GONE
            } else {
                binding.emptyStateCard.visibility = View.GONE
                binding.appsRecyclerView.visibility = View.VISIBLE
            }
        }
    }

    private fun openAppDetail(packageName: String, appName: String) {
        val intent = Intent(this, AppDetailActivity::class.java).apply {
            putExtra(AppDetailActivity.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AppDetailActivity.EXTRA_APP_NAME, appName)
        }
        startActivity(intent)
    }
}
