package com.jinsolutions.smsforward

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * One-screen setup: pick SIM, grant permissions, set groups/gateway, Save.
 * Plus a "Send Test" button and a live log so you can see exactly what happens.
 * Everything is stored on the device only.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var simGroup: RadioGroup
    private lateinit var simHint: TextView
    private lateinit var urlInput: EditText
    private lateinit var authInput: EditText
    private lateinit var deviceInput: EditText
    private lateinit var groupsInput: EditText
    private lateinit var keywordsInput: EditText
    private lateinit var enabledSwitch: Switch
    private lateinit var status: TextView
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
        urlInput = findViewById(R.id.urlInput)
        authInput = findViewById(R.id.authInput)
        deviceInput = findViewById(R.id.deviceInput)
        groupsInput = findViewById(R.id.groupsInput)
        keywordsInput = findViewById(R.id.keywordsInput)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        status = findViewById(R.id.status)
        logView = findViewById(R.id.logView)

        val cfg = Config.load(this)
        urlInput.setText(cfg.gatewayUrl)
        authInput.setText(cfg.auth)
        deviceInput.setText(cfg.deviceId)
        groupsInput.setText(cfg.groups.joinToString("\n"))
        keywordsInput.setText(cfg.keywords.joinToString(","))
        enabledSwitch.isChecked = cfg.enabled

        findViewById<Button>(R.id.grantBtn).setOnClickListener { requestPermissions() }
        findViewById<Button>(R.id.saveBtn).setOnClickListener { save() }
        findViewById<Button>(R.id.testBtn).setOnClickListener { sendTest() }
        findViewById<Button>(R.id.refreshLogBtn).setOnClickListener { updateLog() }
        findViewById<Button>(R.id.clearLogBtn).setOnClickListener {
            EventLog.clear(this); updateLog()
        }

        if (!hasAllPermissions()) requestPermissions() else refreshSims()
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

    private fun currentGroups(): List<String> =
        groupsInput.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    private fun save() {
        if (enabledSwitch.isChecked && !hasAllPermissions()) {
            Toast.makeText(this, "Grant all permissions before enabling.", Toast.LENGTH_LONG).show()
            requestPermissions()
            return
        }
        val groups = currentGroups()
        val keywords = keywordsInput.text.toString()
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

        if (enabledSwitch.isChecked && groups.isEmpty()) {
            Toast.makeText(this, "Add at least one WhatsApp group id.", Toast.LENGTH_LONG).show()
            return
        }
        if (authInput.text.toString().isBlank()) {
            Toast.makeText(this, "Tip: paste your Basic ... token, or sends will fail.", Toast.LENGTH_LONG).show()
        }

        Config.save(this, AppConfig(
            enabled = enabledSwitch.isChecked,
            subId = selectedSubId(),
            simLabel = selectedSimLabel(),
            gatewayUrl = urlInput.text.toString().trim(),
            auth = authInput.text.toString().trim(),
            deviceId = deviceInput.text.toString().trim(),
            groups = groups,
            keywords = keywords
        ))
        Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    /** Sends a test message right now to every group, using the values currently on screen. */
    private fun sendTest() {
        val groups = currentGroups()
        val url = urlInput.text.toString().trim()
        val auth = authInput.text.toString().trim()
        val device = deviceInput.text.toString().trim()

        if (groups.isEmpty()) {
            Toast.makeText(this, "Add a group id first.", Toast.LENGTH_LONG).show(); return
        }
        if (auth.isBlank()) {
            Toast.makeText(this, "Paste your Basic ... token first.", Toast.LENGTH_LONG).show(); return
        }

        EventLog.add(this, "TEST pressed — sending to ${groups.size} group(s)")
        for (group in groups) {
            val data = workDataOf(
                SendWorker.K_URL to url,
                SendWorker.K_AUTH to auth,
                SendWorker.K_DEVICE to device,
                SendWorker.K_PHONE to group,
                SendWorker.K_MESSAGE to "Test from SMS to WhatsApp app. If you see this, sending works."
            )
            WorkManager.getInstance(this)
                .enqueue(OneTimeWorkRequestBuilder<SendWorker>().setInputData(data).build())
        }
        Toast.makeText(this, "Test queued. Watch the log below.", Toast.LENGTH_SHORT).show()
        // Refresh the log a few times so the HTTP result shows without leaving the screen.
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({ updateLog() }, 2000)
        h.postDelayed({ updateLog() }, 5000)
    }

    private fun updateStatus() {
        val cfg = Config.load(this)
        status.text = buildString {
            append(if (cfg.enabled) "● RUNNING\n" else "○ Disabled\n")
            append("Permissions: ${if (hasAllPermissions()) "granted" else "MISSING"}\n")
            append("Token: ${if (cfg.auth.isBlank()) "NOT SET" else "set"}\n")
            append("Watching: ${if (cfg.simLabel.isBlank()) "Any SIM" else cfg.simLabel}\n")
            append("Keywords: ${cfg.keywords.joinToString(", ")}\n")
            append("Groups: ${cfg.groups.size}")
        }
    }

    private fun updateLog() {
        logView.text = EventLog.get(this)
    }
}
