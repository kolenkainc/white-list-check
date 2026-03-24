package tech.romashov.whitelistcheck

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import tech.romashov.whitelistcheck.databinding.ActivityMainBinding
import java.io.File
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.toast_notify_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        loadFormFromPrefs()
        refreshAppVersionLabel()
        UtcWindowScheduler.scheduleNextWindow(this)

        binding.buttonStart.setOnClickListener {
            saveFormToPrefs()
            val prefs = MonitoringPrefs(this)
            prefs.monitoringFromSchedule = false
            prefs.monitoringEnabled = true
            IpCheckService.start(this)
            refreshStatus()
            Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show()
        }

        binding.buttonStop.setOnClickListener {
            val prefs = MonitoringPrefs(this)
            prefs.monitoringFromSchedule = false
            prefs.monitoringEnabled = false
            startService(Intent(this, IpCheckService::class.java).setAction(IpCheckService.ACTION_STOP))
            refreshStatus()
            Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        }

        binding.buttonRefreshStatus.setOnClickListener { refreshStatus() }

        binding.buttonCheckUpdate.setOnClickListener { checkForGithubUpdate() }

        maybeAutoCheckForUpdate()
    }

    override fun onResume() {
        super.onResume()
        UtcWindowScheduler.scheduleNextWindow(this)
        refreshStatus()
    }

    private fun refreshAppVersionLabel() {
        binding.textAppVersion.text = getString(
            R.string.label_current_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
    }

    private fun loadFormFromPrefs() {
        val prefs = MonitoringPrefs(this)
        binding.inputUrl.setText(prefs.endpointUrl)
        binding.inputInterval.setText(prefs.intervalMinutes.toString())
        binding.inputTimeout.setText(prefs.connectTimeoutSeconds.toString())
        binding.switchScheduledUtc.setOnCheckedChangeListener(null)
        binding.switchScheduledUtc.isChecked = prefs.scheduledUtcWindowEnabled
        binding.switchScheduledUtc.setOnCheckedChangeListener { _, checked ->
            MonitoringPrefs(this).scheduledUtcWindowEnabled = checked
            UtcWindowScheduler.scheduleNextWindow(this)
        }
    }

    private fun saveFormToPrefs() {
        val prefs = MonitoringPrefs(this)
        prefs.endpointUrl = binding.inputUrl.text?.toString()?.trim().orEmpty()
            .ifBlank { MonitoringPrefs.DEFAULT_URL }
        prefs.intervalMinutes = binding.inputInterval.text?.toString()?.toIntOrNull() ?: 15
        prefs.connectTimeoutSeconds = binding.inputTimeout.text?.toString()?.toIntOrNull() ?: 5
    }

    private fun refreshStatus() {
        val prefs = MonitoringPrefs(this)
        val monitoring = prefs.monitoringEnabled
        binding.textMonitoringState.text = if (monitoring) {
            getString(R.string.status_monitoring_on)
        } else {
            getString(R.string.status_monitoring_off)
        }

        val time = prefs.lastCheckTimeMillis
        binding.textLastTime.text = if (time > 0L) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time))
        } else {
            getString(R.string.status_never)
        }

        binding.textLastIp.text = prefs.lastTargetIp ?: "—"
        val ok = prefs.lastReachable
        binding.textLastReachable.text = when (ok) {
            null -> "—"
            true -> getString(R.string.reachable_yes)
            false -> getString(R.string.reachable_no)
        }
        binding.textLastMessage.text = prefs.lastMessage ?: "—"
    }

    private fun maybeAutoCheckForUpdate() {
        if (BuildConfig.GITHUB_OWNER == "YOUR_GITHUB_OWNER") return
        val up = UpdatePrefs(this)
        if (!up.shouldRunAutoCheck(UpdatePrefs.AUTO_CHECK_INTERVAL_MS)) return

        lifecycleScope.launch {
            try {
                val result = GithubReleaseUpdate.fetchLatestRelease(
                    BuildConfig.GITHUB_OWNER,
                    BuildConfig.GITHUB_REPO,
                )
                result.fold(
                    onSuccess = { info -> offerUpdateIfNewer(info) },
                    onFailure = { },
                )
            } finally {
                UpdatePrefs(this@MainActivity).markUpdateCheckDone()
            }
        }
    }

    private fun checkForGithubUpdate() {
        if (BuildConfig.GITHUB_OWNER == "YOUR_GITHUB_OWNER") {
            Toast.makeText(this, R.string.update_configure_repo, Toast.LENGTH_LONG).show()
            return
        }

        val checking = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_checking)
            .setView(ProgressBar(this))
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = GithubReleaseUpdate.fetchLatestRelease(
                BuildConfig.GITHUB_OWNER,
                BuildConfig.GITHUB_REPO,
            )
            checking.dismiss()
            result.fold(
                onSuccess = { info ->
                    UpdatePrefs(this@MainActivity).markUpdateCheckDone()
                    val local = SemVer.parse(BuildConfig.VERSION_NAME) ?: SemVer(0, 0, 0)
                    if (info.version <= local) {
                        Toast.makeText(this@MainActivity, R.string.update_latest, Toast.LENGTH_LONG).show()
                        return@fold
                    }
                    showUpdateAvailableDialog(info)
                },
                onFailure = { e ->
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_error, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    private fun offerUpdateIfNewer(info: GithubReleaseInfo) {
        val local = SemVer.parse(BuildConfig.VERSION_NAME) ?: SemVer(0, 0, 0)
        if (info.version <= local) return
        showUpdateAvailableDialog(info)
    }

    private fun showUpdateAvailableDialog(info: GithubReleaseInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(
                getString(
                    R.string.update_available_message,
                    info.tagName,
                    BuildConfig.VERSION_NAME,
                ),
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.update_download) { _, _ ->
                downloadAndInstall(info)
            }
            .show()
    }

    private fun formatByteCount(value: Long): String {
        val nf = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru-RU"))
        return getString(R.string.update_bytes, nf.format(value))
    }

    private fun downloadAndInstall(info: GithubReleaseInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.update_need_install_permission)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.update_open_settings) { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    )
                    startActivity(intent)
                }
                .show()
            return
        }

        val density = resources.displayMetrics.density
        val padH = (24 * density).roundToInt()
        val padV = (12 * density).roundToInt()

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV)
        }
        val progressText = TextView(this).apply {
            textSize = 14f
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        wrap.addView(
            progressText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        wrap.addView(
            bar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = padV / 2 },
        )

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_downloading)
            .setView(wrap)
            .setCancelable(false)
            .show()

        fun applyProgress(read: Long, total: Long) {
            progressText.text = if (total >= 0L) {
                getString(
                    R.string.update_download_progress_known,
                    formatByteCount(read),
                    formatByteCount(total),
                )
            } else {
                getString(
                    R.string.update_download_progress_unknown,
                    formatByteCount(read),
                )
            }
            if (total >= 0L) {
                bar.isIndeterminate = false
                bar.max = 10_000
                bar.progress = ((read * 10_000L) / total).toInt().coerceIn(0, 10_000)
            } else {
                bar.isIndeterminate = true
            }
        }

        lifecycleScope.launch {
            val apkFile = File(cacheDir, "update-install.apk")
            if (apkFile.exists()) apkFile.delete()
            val dl = GithubReleaseUpdate.downloadApk(
                downloadUrl = info.apkDownloadUrl,
                targetFile = apkFile,
                onProgress = { read, total -> applyProgress(read, total) },
            )
            dialog.dismiss()
            dl.fold(
                onSuccess = {
                    runCatching {
                        startActivity(GithubReleaseUpdate.installIntent(this@MainActivity, apkFile))
                    }.onFailure { e ->
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.update_error, e.message ?: e.javaClass.simpleName),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                onFailure = { e ->
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_error, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }
}
