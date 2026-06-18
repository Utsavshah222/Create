# SMS → WhatsApp (personal, single-device)

A small native Android app for **your own device**. It watches incoming SMS on a SIM you
choose, keeps only the ones that look like bank **debit/credit** alerts, and forwards each
message **exactly as received** to one or more WhatsApp groups through your gateway
(`wa.jinsolutions.in`). All settings stay on the phone; nothing is stored on any server.

> Install this only on a device you own, with a SIM and bank account that are yours.
> It reads SMS and sends bank-transaction text to the gateway you configure — make sure
> that gateway is one you control.

---

## What it does

1. **Pick SIM** – lists your active SIMs; you choose which one to watch (or "Any SIM").
2. **Permissions** – requests `RECEIVE_SMS`, `READ_SMS`, `READ_PHONE_STATE`, `READ_PHONE_NUMBERS`.
3. **Groups + gateway** – pre-filled with the values you gave; editable.
4. Flip **Enabled** → **Save**. From then on it runs in the background, even after the app
   is closed. Incoming debit/credit SMS on the chosen SIM are POSTed to each group.

The request sent to the gateway is:

```
POST https://wa.jinsolutions.in/send/message
Authorization: Basic <your-token>      <-- you paste this once, in the app (not stored in git)
x-device-id: savebirdskandivali
Content-Type: application/json

{"phone":"120363416877358281@g.us","message":"<the SMS text>"}
```

> The `Authorization` value is **not** in the source code on purpose, so it never ends up
> on GitHub. Open the app once, paste your `Basic ...` token into the **Authorization
> header** field, and tap **Save**. It stays only on the phone.

One request per group. The two groups are pre-filled:
`120363416877358281@g.us` and `919664305350-1606905372@g.us`.

---

## How to get the APK (no Android tools needed on your PC)

This machine has no Android SDK, so the APK is built by **GitHub Actions** in the cloud.

1. Create a new GitHub repository (private is fine).
2. Upload **all files in this folder** (keep the folder structure, including `.github/`).
3. GitHub → the repo → **Actions** tab → the **Build APK** run starts automatically
   (or click **Run workflow**).
4. When it finishes (green check), open the run → **Artifacts** → download **app-debug-apk**.
5. Unzip it → you get `app-debug.apk`.

### Install on the phone
1. Copy `app-debug.apk` to the phone (USB, Drive, WhatsApp to yourself, etc.).
2. Open it → allow **Install unknown apps** for your file manager/browser when asked.
3. Open the app, complete the 3 steps, turn **Enabled** on, tap **Save**.

> It's a *debug-signed* APK — perfectly fine for sideloading onto your own phone. Any
> future update must be built the same way (same signing) to install over it.

---

## Keep it alive in the background (important on some phones)

Xiaomi/Redmi (MIUI), Oppo/Realme/OnePlus (ColorOS), Vivo, Samsung etc. aggressively kill
background apps. To make sure SMS keeps forwarding after reboots and idle time:

- Settings → Apps → **SMS → WhatsApp** → **Battery** → set to **Unrestricted / No restrictions**.
- Enable **Autostart** for the app (MIUI/ColorOS have this toggle).
- Don't "lock"/swipe it away in a way that force-stops it (a force-stopped app stops
  receiving SMS until you open it once more).

---

## Build it locally instead (optional)

If you later install **JDK 17 + Android SDK + Gradle 8.9** on a PC:

```
gradle assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

---

## Project layout

```
.github/workflows/build.yml          GitHub Actions cloud build
settings.gradle.kts, build.gradle.kts, gradle.properties
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/jinsolutions/smsforward/
    MainActivity.kt    setup screen (SIM, permissions, keywords, test, log)
    Config.kt          on-device settings; fixed values + build-time token
    SmsReceiver.kt     background SMS receiver + filter -> adds to queue
    ForwardService.kt  always-on foreground service; drains queue 1 msg / 2s
    QueueStore.kt      persistent FIFO send queue
    Sender.kt          single HTTP POST to the gateway
    EventLog.kt        on-screen activity log
    BootReceiver.kt    restarts the service after reboot
    WatchdogWorker.kt  revives the service every ~15 min
app/src/main/res/...   layout, strings, theme, launcher icon
```

## Changing settings later
Just open the app, edit any field (groups, keywords, SIM, gateway), and tap **Save**.
To stop forwarding, turn **Enabled** off and **Save**.
