package tech.romashov.whitelistcheck

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import tech.romashov.whitelistcheck.databinding.ActivityMainBinding
import java.text.DateFormat
import java.util.Date

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

        binding.buttonStart.setOnClickListener {
            saveFormToPrefs()
            val prefs = MonitoringPrefs(this)
            prefs.monitoringEnabled = true
            IpCheckService.start(this)
            refreshStatus()
            Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show()
        }

        binding.buttonStop.setOnClickListener {
            val prefs = MonitoringPrefs(this)
            prefs.monitoringEnabled = false
            startService(Intent(this, IpCheckService::class.java).setAction(IpCheckService.ACTION_STOP))
            refreshStatus()
            Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        }

        binding.buttonRefreshStatus.setOnClickListener { refreshStatus() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun loadFormFromPrefs() {
        val prefs = MonitoringPrefs(this)
        binding.inputUrl.setText(prefs.endpointUrl)
        binding.inputInterval.setText(prefs.intervalMinutes.toString())
        binding.inputTimeout.setText(prefs.connectTimeoutSeconds.toString())
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
}
