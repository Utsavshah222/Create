package com.jinsolutions.smsforward

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var destInfo: TextView
    private lateinit var logView: TextView
    private lateinit var messagesContainer: LinearLayout

    // SMS section
    private lateinit var smsEnabledSwitch: Switch
    private lateinit var smsSimHint: TextView
    private lateinit var smsSimGroup: RadioGroup
    private lateinit var keywordsInput: EditText

    // Call section
    private lateinit var callEnabledSwitch: Switch
    private lateinit var callSimHint: TextView
    private lateinit var callSimGroup: RadioGroup
    private lateinit var callMissedCheck: CheckBox
    private lateinit var callRejectedCheck: CheckBox
    private lateinit var callWhatsAppCheck: CheckBox
    private lateinit var callSmsCheck: CheckBox
    private lateinit var callMessageInput: EditText
    private lateinit var callCcInput: EditText

    // Working hours
    private lateinit var autoRejectSwitch: Switch
    private lateinit var whStartInput: EditText
    private lateinit var whEndInput: EditText

    // READ_PHONE_NUMBERS only exists on Android 8+; asking for it earlier is always "denied".
    private val smsPerms = listOfNotNull(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_PHONE_STATE,
        if (Build.VERSION.SDK_INT >= 26) Manifest.permission.READ_PHONE_NUMBERS else null
    ).toTypedArray()
    private val callPerms = arrayOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE
    )

    // True while Save is waiting on a permission prompt; Save resumes once it returns.
    private var pendingSave = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            populateSims()
            updateStatus()
            if (pendingSave) {
                pendingSave = false
                val missing = missingForSave()
                if (missing.isEmpty()) save()
                else showDenied(missing)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        destInfo = findViewById(R.id.destInfo)
        logView = findViewById(R.id.logView)
        messagesContainer = findViewById(R.id.messagesContainer)

        smsEnabledSwitch = findViewById(R.id.smsEnabledSwitch)
        smsSimHint = findViewById(R.id.smsSimHint)
        smsSimGroup = findViewById(R.id.smsSimGroup)
        keywordsInput = findViewById(R.id.keywordsInput)

        callEnabledSwitch = findViewById(R.id.callEnabledSwitch)
        callSimHint = findViewById(R.id.callSimHint)
        callSimGroup = findViewById(R.id.callSimGroup)
        callMissedCheck = findViewById(R.id.callMissedCheck)
        callRejectedCheck = findViewById(R.id.callRejectedCheck)
        callWhatsAppCheck = findViewById(R.id.callWhatsAppCheck)
        callSmsCheck = findViewById(R.id.callSmsCheck)
        callMessageInput = findViewById(R.id.callMessageInput)
        callCcInput = findViewById(R.id.callCcInput)
        autoRejectSwitch = findViewById(R.id.autoRejectSwitch)
        whStartInput = findViewById(R.id.whStartInput)
        whEndInput = findViewById(R.id.whEndInput)

        val sms = Config.loadSms(this)
        smsEnabledSwitch.isChecked = sms.enabled
        keywordsInput.setText(sms.keywords.joinToString(","))

        val call = Config.loadCall(this)
        callEnabledSwitch.isChecked = call.enabled
        callMissedCheck.isChecked = call.onMissed
        callRejectedCheck.isChecked = call.onRejected
        callWhatsAppCheck.isChecked = call.sendWhatsApp
        callSmsCheck.isChecked = call.sendSms
        callMessageInput.setText(call.message)
        callCcInput.setText(call.countryCode)

        val wh = Config.loadWorkingHours(this)
        autoRejectSwitch.isChecked = wh.enabled
        whStartInput.setText(fmtHHMM(wh.startMin))
        whEndInput.setText(fmtHHMM(wh.endMin))

        destInfo.text = "Sends to ${Config.GROUPS.size} group(s), 1 message / 2s, offline-safe.\n" +
            Config.GROUPS.joinToString("\n") { "  • $it" }

        setupDeviceEditor(
            findViewById(R.id.smsDeviceInput), findViewById(R.id.smsDeviceEditBtn),
            { Config.getSmsDeviceId(this) }, { Config.setSmsDeviceId(this, it) }
        )
        setupDeviceEditor(
            findViewById(R.id.callDeviceInput), findViewById(R.id.callDeviceEditBtn),
            { Config.getCallDeviceId(this) }, { Config.setCallDeviceId(this, it) }
        )

        findViewById<Button>(R.id.grantBtn).setOnClickListener { requestPermissions() }
        findViewById<Button>(R.id.batteryBtn).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.saveBtn).setOnClickListener { save() }
        findViewById<Button>(R.id.testBtn).setOnClickListener { sendTest() }
        findViewById<Button>(R.id.refreshLogBtn).setOnClickListener { refreshActivity() }
        findViewById<Button>(R.id.clearLogBtn).setOnClickListener {
            EventLog.clear(this); MessageStore.clear(this); refreshActivity()
        }

        // Ask only for what the currently enabled features need (plus SIM list access).
        val startup = (neededPerms() + Manifest.permission.READ_PHONE_STATE).filterNot { granted(it) }
        if (startup.isNotEmpty()) permLauncher.launch(startup.distinct().toTypedArray()) else populateSims()

        if (Config.anyEnabled(this)) {
            ForwardService.start(this)
            scheduleWatchdog(ExistingPeriodicWorkPolicy.KEEP)
        }
    }

    private fun scheduleWatchdog(policy: ExistingPeriodicWorkPolicy) {
        val work = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("watchdog", policy, work)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        refreshActivity()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Permissions required by what is switched on in the form right now — nothing extra. */
    private fun neededPerms(): List<String> {
        val need = LinkedHashSet<String>()
        val smsOn = if (::smsEnabledSwitch.isInitialized) smsEnabledSwitch.isChecked else Config.loadSms(this).enabled
        val call = Config.loadCall(this)
        val callOn = if (::callEnabledSwitch.isInitialized) callEnabledSwitch.isChecked else call.enabled
        val rejectOn = if (::autoRejectSwitch.isInitialized) autoRejectSwitch.isChecked else Config.loadWorkingHours(this).enabled
        val replySms = if (::callSmsCheck.isInitialized) callSmsCheck.isChecked else call.sendSms

        if (smsOn) { need.add(Manifest.permission.RECEIVE_SMS); need.add(Manifest.permission.READ_PHONE_STATE) }
        if (callOn || rejectOn) { need.add(Manifest.permission.READ_CALL_LOG); need.add(Manifest.permission.READ_PHONE_STATE) }
        if (rejectOn && Build.VERSION.SDK_INT >= 28) need.add(Manifest.permission.ANSWER_PHONE_CALLS)
        if ((callOn || rejectOn) && replySms) need.add(Manifest.permission.SEND_SMS)
        if (need.isNotEmpty() && Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        return need.toList()
    }

    /** Needed permissions still missing (notifications are nice-to-have, never block Save). */
    private fun missingForSave() = neededPerms()
        .filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .filterNot { granted(it) }

    private fun showDenied(missing: List<String>) {
        val names = missing.joinToString(", ") { it.substringAfterLast('.') }
        Toast.makeText(this, "Not saved — permission denied: $names. Allow it in Settings > Apps > " +
            "this app > Permissions, or turn that option off.", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
    }

    /** "Grant" button: asks for everything the app can use, so all options are ready. */
    private fun requestPermissions() {
        val perms = (smsPerms + callPerms).toMutableSet()
        perms.add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 26) perms.add(Manifest.permission.ANSWER_PHONE_CALLS)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    // Only Android 9+ rejects via TelecomManager (needs this permission); older uses reflection.
    private fun hasAnswerCallsPerm() = Build.VERSION.SDK_INT < 28 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    private fun parseHHMM(s: String, default: Int): Int {
        val parts = s.trim().split(":")
        if (parts.size == 2) {
            val h = parts[0].toIntOrNull()
            val m = parts[1].toIntOrNull()
            if (h != null && m != null && h in 0..23 && m in 0..59) return h * 60 + m
        }
        return default
    }

    private fun fmtHHMM(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    // Essentials only: receiving SMS + knowing which SIM it came in on.
    private fun hasSmsPerms() =
        granted(Manifest.permission.RECEIVE_SMS) && granted(Manifest.permission.READ_PHONE_STATE)

    private fun hasCallPerms() = callPerms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

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
    private fun activeSims(): List<SubscriptionInfo> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) return emptyList()
        return getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList ?: emptyList()
    }

    private fun populateSims() {
        val subs = activeSims()
        fillSimGroup(smsSimGroup, smsSimHint, subs, Config.loadSms(this).subId)
        fillSimGroup(callSimGroup, callSimHint, subs, Config.loadCall(this).subId)
    }

    private fun fillSimGroup(group: RadioGroup, hint: TextView, subs: List<SubscriptionInfo>, savedSubId: Int) {
        group.removeAllViews()
        addSimOption(group, -1, "Any SIM (all cards)")
        for (info in subs) {
            val number = try { info.number.orEmpty() } catch (e: Exception) { "" }
            val carrier = info.carrierName?.toString().orEmpty()
            val label = buildString {
                append("SIM ${info.simSlotIndex + 1}")
                if (carrier.isNotBlank()) append(" – $carrier")
                if (number.isNotBlank()) append(" ($number)")
            }
            addSimOption(group, info.subscriptionId, label)
        }
        hint.text = if (subs.isEmpty()) "No SIMs detected — \"Any SIM\" still works." else ""

        for (i in 0 until group.childCount) {
            val rb = group.getChildAt(i) as RadioButton
            if ((rb.tag as Int) == savedSubId) rb.isChecked = true
        }
        if (group.checkedRadioButtonId == -1 && group.childCount > 0) {
            (group.getChildAt(0) as RadioButton).isChecked = true
        }
    }

    private fun addSimOption(group: RadioGroup, subId: Int, label: String) {
        val rb = RadioButton(this)
        rb.text = label
        rb.tag = subId
        rb.id = View.generateViewId()
        group.addView(rb)
    }

    private fun selectedSubId(group: RadioGroup): Int {
        val id = group.checkedRadioButtonId
        if (id == -1) return -1
        return (findViewById<RadioButton>(id).tag as? Int) ?: -1
    }

    private fun selectedLabel(group: RadioGroup): String {
        val id = group.checkedRadioButtonId
        if (id == -1) return ""
        return findViewById<RadioButton>(id).text.toString()
    }

    private fun save() {
        val smsOn = smsEnabledSwitch.isChecked
        val callOn = callEnabledSwitch.isChecked

        if (callOn && !callMissedCheck.isChecked && !callRejectedCheck.isChecked) {
            Toast.makeText(this, "Pick at least one call condition (missed / rejected).", Toast.LENGTH_LONG).show()
            return
        }
        if ((callOn || autoRejectSwitch.isChecked) && !callWhatsAppCheck.isChecked && !callSmsCheck.isChecked) {
            Toast.makeText(this, "Pick how to reply: WhatsApp, SMS, or both.", Toast.LENGTH_LONG).show()
            return
        }
        val savedWh = Config.loadWorkingHours(this)
        val startMin = parseHHMM(whStartInput.text.toString(), -1)
            .let { if (it < 0 && !autoRejectSwitch.isChecked) savedWh.startMin else it }
        val endMin = parseHHMM(whEndInput.text.toString(), -1)
            .let { if (it < 0 && !autoRejectSwitch.isChecked) savedWh.endMin else it }
        if (startMin < 0 || endMin < 0) {
            Toast.makeText(this, "Working hours must be HH:MM (24-hour), e.g. 10:00.", Toast.LENGTH_LONG).show()
            return
        }
        if (autoRejectSwitch.isChecked && startMin == endMin) {
            // Would mean "never inside hours" -> every call rejected.
            Toast.makeText(this, "Working-hours start and end can't be the same.", Toast.LENGTH_LONG).show()
            return
        }

        // Ask only for permissions the chosen options need; Save continues after the prompt.
        val missing = missingForSave()
        if (missing.isNotEmpty()) {
            pendingSave = true
            val ask = (missing + neededPerms().filter { it == Manifest.permission.POST_NOTIFICATIONS && !granted(it) })
            permLauncher.launch(ask.distinct().toTypedArray())
            return
        }

        val kws = keywordsInput.text.toString()
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .ifEmpty { Config.DEFAULT_KEYWORDS }
        keywordsInput.setText(kws.joinToString(","))

        Config.saveSms(this, SmsConfig(smsOn, selectedSubId(smsSimGroup), selectedLabel(smsSimGroup), kws))

        val cc = callCcInput.text.toString().filter { it.isDigit() }.ifEmpty { Config.DEFAULT_COUNTRY_CODE }
        callCcInput.setText(cc)
        val msg = callMessageInput.text.toString().ifBlank { Config.DEFAULT_CALL_MESSAGE }
        callMessageInput.setText(msg)
        Config.saveCall(this, CallConfig(
            callOn, selectedSubId(callSimGroup), selectedLabel(callSimGroup),
            callMissedCheck.isChecked, callRejectedCheck.isChecked, msg, cc,
            callWhatsAppCheck.isChecked, callSmsCheck.isChecked
        ))

        whStartInput.setText(fmtHHMM(startMin))
        whEndInput.setText(fmtHHMM(endMin))
        Config.saveWorkingHours(this, WorkingHours(autoRejectSwitch.isChecked, startMin, endMin))

        if (Config.anyEnabled(this)) {
            ForwardService.start(this)
            scheduleWatchdog(ExistingPeriodicWorkPolicy.UPDATE)
        } else {
            ForwardService.stop(this)
            WorkManager.getInstance(this).cancelUniqueWork("watchdog")
        }

        Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun sendTest() {
        if (Config.AUTH.isBlank()) {
            Toast.makeText(this, "Token not built in. Add GATEWAY_AUTH secret and rebuild.", Toast.LENGTH_LONG).show()
            return
        }
        val dev = Config.getSmsDeviceId(this)
        for (group in Config.GROUPS) {
            QueueStore.add(this, group, "Test from SMS to WhatsApp app. If you see this, sending works.", dev)
        }
        MessageStore.add(this, "TEST", "you", "Test message", "queued to ${Config.GROUPS.size} group(s)")
        EventLog.add(this, "TEST queued ${Config.GROUPS.size} message(s)")
        ForwardService.start(this)
        Toast.makeText(this, "Test queued. Watch below.", Toast.LENGTH_SHORT).show()
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({ refreshActivity() }, 2500)
        h.postDelayed({ refreshActivity() }, 6000)
    }

    private fun updateStatus() {
        val sms = Config.loadSms(this)
        val call = Config.loadCall(this)
        val wh = Config.loadWorkingHours(this)
        status.text = buildString {
            append("SMS: ${if (sms.enabled) "● ON" else "○ off"}  (${if (hasSmsPerms()) "perms ok" else "PERMS MISSING"})\n")
            append("Call: ${if (call.enabled) "● ON" else "○ off"}  (${if (hasCallPerms()) "perms ok" else "PERMS MISSING"})\n")
            append("Auto-reject: ${if (wh.enabled) "● ON ${fmtHHMM(wh.startMin)}-${fmtHHMM(wh.endMin)} IST" else "○ off"}  (${if (hasAnswerCallsPerm()) "perms ok" else "PERMS MISSING"})\n")
            append("Token built in: ${if (Config.AUTH.isBlank()) "NO — add GATEWAY_AUTH secret" else "yes"}\n")
            val via = listOfNotNull(if (call.sendWhatsApp) "WhatsApp" else null, if (call.sendSms) "SMS" else null)
            append("Call reply via: ${via.joinToString(" + ").ifEmpty { "none" }}")
            if (call.sendSms) append("  (SMS-send ${if (SmsSender.hasPermission(this@MainActivity)) "ok" else "PERM MISSING"})")
            append("\n")
            append("WhatsApp queue: ${QueueStore.size(this@MainActivity)} · SMS queue: ${SmsQueueStore.size(this@MainActivity)}")
        }
    }

    /** Rebuilds the recent-messages list (each SMS gets a manual "Send to group" button). */
    private fun refreshActivity() {
        logView.text = EventLog.get(this)
        messagesContainer.removeAllViews()
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val items = MessageStore.list(this)
        if (items.isEmpty()) {
            val tv = TextView(this)
            tv.text = "(no messages yet)"
            tv.textSize = 12f
            messagesContainer.addView(tv)
            return
        }
        for (rec in items) {
            messagesContainer.addView(buildMessageCard(rec, fmt.format(Date(rec.time))))
        }
    }

    private fun buildMessageCard(rec: MessageStore.Rec, timeStr: String): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(12), dp(10), dp(12), dp(10))
        card.setBackgroundColor(Color.parseColor("#F4F6F8"))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        card.layoutParams = lp

        val header = TextView(this)
        header.text = "$timeStr • ${rec.type} • ${rec.from}"
        header.textSize = 11f
        header.setTextColor(Color.parseColor("#5A6470"))
        card.addView(header)

        val body = TextView(this)
        body.text = rec.text
        body.textSize = 13f
        body.setTextColor(Color.parseColor("#11181C"))
        card.addView(body)

        val st = TextView(this)
        st.text = "✓ ${rec.status}"
        st.textSize = 11f
        st.setTextColor(Color.parseColor("#0B7D3E"))
        card.addView(st)

        if (rec.type == "SMS") {
            val b = Button(this)
            b.text = "Send to group"
            b.setOnClickListener {
                val d = Config.getSmsDeviceId(this)
                for (group in Config.GROUPS) QueueStore.add(this, group, rec.text, d)
                ForwardService.start(this)
                Toast.makeText(this, "Queued to groups.", Toast.LENGTH_SHORT).show()
                updateStatus()
            }
            card.addView(b)
        }
        return card
    }

    /** Field is read-only until Edit is tapped; Edit becomes Save and persists the value. */
    private fun setupDeviceEditor(field: EditText, btn: Button, load: () -> String, store: (String) -> Unit) {
        field.setText(load())
        field.isEnabled = false
        btn.text = "Edit"
        btn.setOnClickListener {
            if (btn.text.toString() == "Edit") {
                field.isEnabled = true
                field.requestFocus()
                field.setSelection(field.text.length)
                btn.text = "Save"
            } else {
                val v = field.text.toString().trim()
                if (v.isEmpty()) {
                    Toast.makeText(this, "Device id can't be empty.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                store(v)
                field.setText(v)
                field.isEnabled = false
                btn.text = "Edit"
                Toast.makeText(this, "Device id saved: $v", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
