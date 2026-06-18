package com.jinsolutions.smsforward

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Setup screen. Everything except the keyword list, the SIM and the on/off switch is fixed
 * and baked into the app. A "Send Test" button and an on-screen log let you verify it works.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var simGroup: RadioGroup
    private lateinit var simHint: TextView
    private lateinit var keywordsInput: EditText
    private lateinit var enabledSwitch: Switch
    private lateinit var status: TextView
    private lateinit var destInfo: TextView
    private lateinit var logView: TextView

    private val basePerms = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS
    )

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshSims()
            updateStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        simGroup = findViewById(R.id.simGroup)
        simHint = findViewById(R.id.simHint)
        keywordsInput = findViewById(R.id.keywordsInput)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        status = findViewById(R.id.status)
        destInfo = findViewById(R.id.destInfo)
        logView = findViewById(R.id.logView)

        val cfg = Config.load(this)
        keywordsInput.setText(cfg.keywords.joinToString(","))
        enabledSwitch.isChecked = cfg.enabled
        destInfo.text = buildString {
            append("Gateway: ${Config.GATEWAY_URL}\n")
            append("Device : ${Config.DEVICE_ID}\n")
            append("Groups :\n")
            Config.GROUPS.forEach { append("  • $it\n") }
        }

        findViewById<Button>(R.id.grantBtn).setOnClickListener { requestPermissions() }
        findViewById<Button>(R.id.batteryBtn).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.saveBtn).setOnClickListener { save() }
        findViewById<Button>(R.id.testBtn).setOnClickListener { sendTest() }
        findViewById<Button>(R.id.refreshLogBtn).setOnClickListener { updateLog() }
        findViewById<Button>(R.id.clearLogBtn).setOnClickListener { EventLog.clear(this); updateLog() }

        if (!hasAllPermissions()) requestPermissions() else refreshSims()

        // Make sure the service is running if forwarding is already enabled.
        if (cfg.enabled) ForwardService.start(this)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateLog()
    }

    private fun requestPermissions() {
        val perms = basePerms.toMutableList()
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    private fun hasAllPermissions(): Boolean =
        basePerms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /** Opens the system dialog to stop the OS from killing this app to save battery. */
    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "Already unrestricted ✓", Toast.LENGTH_SHORT).show()
                return
            }
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Open Settings > Apps > this app > Battery > Unrestricted",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshSims() {
        simGroup.removeAllViews()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            simHint.text = "Grant permissions first to list SIMs."
            return
        }

        val sm = getSystemService(SubscriptionManager::class.java)
        val subs: List<SubscriptionInfo> = sm?.activeSubscriptionInfoList ?: emptyList()

        addSimOption(-1, "Any SIM (all cards)")
        for (info in subs) {
            val slot = info.simSlotIndex + 1
            val carrier = info.carrierName?.toString().orEmpty()
            val number = try { info.number.orEmpty() } catch (e: Exception) { "" }
            val label = buildString {
                append("SIM $slot")
                if (carrier.isNotBlank()) append(" – $carrier")
                if (number.isNotBlank()) append(" ($number)")
            }
            addSimOption(info.subscriptionId, label)
        }

        simHint.text = if (subs.isEmpty())
            "No SIMs detected. You can still pick \"Any SIM\"."
        else
            "Tap the SIM whose debit/credit alerts you want forwarded."

        val saved = Config.load(this).subId
        for (i in 0 until simGroup.childCount) {
            val rb = simGroup.getChildAt(i) as RadioButton
            if ((rb.tag as Int) == saved) rb.isChecked = true
        }
        if (simGroup.checkedRadioButtonId == -1 && simGroup.childCount > 0) {
            (simGroup.getChildAt(0) as RadioButton).isChecked = true
        }
    }

    private fun addSimOption(subId: Int, label: String) {
        val rb = RadioButton(this)
        rb.text = label
        rb.tag = subId
        rb.id = View.generateViewId()
        simGroup.addView(rb)
    }

    private fun selectedSubId(): Int {
        val id = simGroup.checkedRadioButtonId
        if (id == -1) return -1
        return (findViewById<RadioButton>(id).tag as? Int) ?: -1
    }

    private fun selectedSimLabel(): String {
        val id = simGroup.checkedRadioButtonId
        if (id == -1) return ""
        return findViewById<RadioButton>(id).text.toString()
    }

    private fun keywords(): List<String> =
        keywordsInput.text.toString().split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun save() {
        if (enabledSwitch.isChecked && !hasAllPermissions()) {
            Toast.makeText(this, "Grant all permissions before enabling.", Toast.LENGTH_LONG).show()
            requestPermissions()
            return
        }
        val kws = keywords().ifEmpty { Config.DEFAULT_KEYWORDS }
        Config.save(this, enabledSwitch.isChecked, selectedSubId(), selectedSimLabel(), kws)
        keywordsInput.setText(kws.joinToString(","))

        // Start/stop the always-on background service + watchdog.
        if (enabledSwitch.isChecked) {
            ForwardService.start(this)
            val work = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork("watchdog", ExistingPeriodicWorkPolicy.UPDATE, work)
        } else {
            ForwardService.stop(this)
            WorkManager.getInstance(this).cancelUniqueWork("watchdog")
        }

        Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    /** Sends a test message right now to every group, using the fixed built-in values. */
    private fun sendTest() {
        if (Config.AUTH.isBlank()) {
            Toast.makeText(this,
                "Token not built in. Add the GATEWAY_AUTH secret on GitHub and rebuild.",
                Toast.LENGTH_LONG).show()
            EventLog.add(this, "TEST blocked — token not built into this APK")
            updateLog()
            return
        }
        EventLog.add(this, "TEST pressed — sending to ${Config.GROUPS.size} group(s)")
        for (group in Config.GROUPS) {
            val data = workDataOf(
                SendWorker.K_URL to Config.GATEWAY_URL,
                SendWorker.K_AUTH to Config.AUTH,
                SendWorker.K_DEVICE to Config.DEVICE_ID,
                SendWorker.K_PHONE to group,
                SendWorker.K_MESSAGE to "Test from SMS to WhatsApp app. If you see this, sending works."
            )
            WorkManager.getInstance(this)
                .enqueue(OneTimeWorkRequestBuilder<SendWorker>().setInputData(data).build())
        }
        Toast.makeText(this, "Test queued. Watch the log below.", Toast.LENGTH_SHORT).show()
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({ updateLog() }, 2000)
        h.postDelayed({ updateLog() }, 5000)
    }

    private fun updateStatus() {
        val cfg = Config.load(this)
        status.text = buildString {
            append(if (cfg.enabled) "● RUNNING\n" else "○ Disabled\n")
            append("Permissions: ${if (hasAllPermissions()) "granted" else "MISSING"}\n")
            append("Token built in: ${if (Config.AUTH.isBlank()) "NO — add GATEWAY_AUTH secret" else "yes"}\n")
            append("Watching: ${if (cfg.simLabel.isBlank()) "Any SIM" else cfg.simLabel}\n")
            append("Keywords: ${cfg.keywords.joinToString(", ")}")
        }
    }

    private fun updateLog() {
        logView.text = EventLog.get(this)
    }
}
