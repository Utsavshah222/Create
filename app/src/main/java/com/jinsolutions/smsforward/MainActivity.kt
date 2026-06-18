package com.jinsolutions.smsforward

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

/**
 * One-screen setup, in the order you asked for:
 *   1) pick the SIM,
 *   2) grant the permissions,
 *   3) review the WhatsApp groups + gateway,
 *   then flip "Enabled" on and Save. After that the app runs by itself in the background.
 *
 * Everything is saved on the device only.
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

    private val basePerms = buildList {
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_PHONE_NUMBERS)
    }.toTypedArray()

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

        val cfg = Config.load(this)
        urlInput.setText(cfg.gatewayUrl)
        authInput.setText(cfg.auth)
        deviceInput.setText(cfg.deviceId)
        groupsInput.setText(cfg.groups.joinToString("\n"))
        keywordsInput.setText(cfg.keywords.joinToString(","))
        enabledSwitch.isChecked = cfg.enabled

        findViewById<Button>(R.id.grantBtn).setOnClickListener { requestPermissions() }
        findViewById<Button>(R.id.saveBtn).setOnClickListener { save() }

        if (!hasAllPermissions()) requestPermissions() else refreshSims()
        updateStatus()
    }

    private fun requestPermissions() {
        val perms = basePerms.toMutableList()
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
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

        // "Any SIM" option first.
        addSimOption(-1, "Any SIM (all cards)")

        for (info in subs) {
            val slot = info.simSlotIndex + 1
            val carrier = info.carrierName?.toString().orEmpty()
            val name = info.displayName?.toString().orEmpty()
            val number = try { info.number.orEmpty() } catch (e: Exception) { "" }
            val label = buildString {
                append("SIM $slot")
                if (carrier.isNotBlank()) append(" – $carrier")
                else if (name.isNotBlank()) append(" – $name")
                if (number.isNotBlank()) append(" ($number)")
            }
            addSimOption(info.subscriptionId, label)
        }

        simHint.text = if (subs.isEmpty())
            "No SIMs detected. You can still pick \"Any SIM\"."
        else
            "Tap the SIM whose debit/credit alerts you want forwarded."

        // Restore previous selection.
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
        val rb = findViewById<RadioButton>(id)
        return (rb.tag as? Int) ?: -1
    }

    private fun selectedSimLabel(): String {
        val id = simGroup.checkedRadioButtonId
        if (id == -1) return ""
        return findViewById<RadioButton>(id).text.toString()
    }

    private fun save() {
        if (enabledSwitch.isChecked && !hasAllPermissions()) {
            Toast.makeText(this, "Grant all permissions before enabling.", Toast.LENGTH_LONG).show()
            requestPermissions()
            return
        }

        val groups = groupsInput.text.toString()
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val keywords = keywordsInput.text.toString()
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

        if (enabledSwitch.isChecked && groups.isEmpty()) {
            Toast.makeText(this, "Add at least one WhatsApp group id.", Toast.LENGTH_LONG).show()
            return
        }

        val cfg = AppConfig(
            enabled = enabledSwitch.isChecked,
            subId = selectedSubId(),
            simLabel = selectedSimLabel(),
            gatewayUrl = urlInput.text.toString().trim(),
            auth = authInput.text.toString().trim(),
            deviceId = deviceInput.text.toString().trim(),
            groups = groups,
            keywords = keywords
        )
        Config.save(this, cfg)
        Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun updateStatus() {
        val cfg = Config.load(this)
        status.text = buildString {
            append(if (cfg.enabled) "● RUNNING\n" else "○ Disabled\n")
            append("Permissions: ${if (hasAllPermissions()) "granted" else "MISSING"}\n")
            append("Watching: ${if (cfg.simLabel.isBlank()) "Any SIM" else cfg.simLabel}\n")
            append("Forward when body contains: ${cfg.keywords.joinToString(", ")}\n")
            append("Groups: ${cfg.groups.size}")
        }
    }
}
