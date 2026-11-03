# Dterm install guide (v1.4.4)

**Dterm** is the Android shell for DSH: its own launcher icon, full screen, no address bar,
connected to the DSH server already running in Termux (`127.0.0.1:3080`).

It does not bundle DSH — the harness still runs in Termux/Ubuntu. So:

- the launcher shows a **Dterm** icon;
- the DSH service must be running in Termux (it usually is; see section 3 if a reboot left it
  stopped).

---

## 1. Install / update

1. Download [`release/Dterm-1.4.4.apk`](../release/Dterm-1.4.4.apk) (or from the
   [latest release](https://github.com/Howardyh/dterm/releases/latest)) and copy it to the device.
2. Open it with the file manager.
3. Upgrading from DSH 1.x: **install over the top**, no uninstall needed — the package name
   (`dev.dsh.pad`) and signing key are unchanged, so the login cookie survives.
4. Allow "install from unknown sources" when prompted. Play Protect may warn about a self-signed
   app; choose **More details → Install anyway**.

## 2. Open

Tap the **Dterm** icon. It should land in the UI within a second or two, **without asking you to
log in**: the app fetches the per-process token from the local bridge and exchanges it for a
30-day cookie.

Long-press the icon for three shortcuts:

| Shortcut | Purpose |
| --- | --- |
| Reload | Page stuck, blank, or the service just restarted |
| Open in browser | When you want the address bar or a browser extension |
| Open Termux | To start the service by hand |

If you ever see "login required": open DSH in the browser, copy the full address bar URL
(`http://127.0.0.1:3080/?token=…`), paste it into the app and tap Enter. One successful login
lasts 30 days.

## 3. "DSH service is not running"

**Option A (recommended)**: tap **Start DSH**. The app asks Termux to run `dsh-start`. If Termux
refuses to run it in the background, the app switches to Termux after 5 seconds; you will see the
command running there, and when it finishes Termux calls `am start` to bring Dterm back. The app
waits up to 150 seconds.

Before using this button, allow external apps once in Termux:

```bash
grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null \
  && sed -i 's/^allow-external-apps=.*/allow-external-apps=true/' ~/.termux/termux.properties \
  || echo 'allow-external-apps=true' >> ~/.termux/termux.properties
termux-reload-settings
```

The first tap shows a system dialog asking whether Dterm may run commands in Termux — allow it.

**Option B**: tap **Open Termux** and run:

```bash
dsh-runner
```

If the command is not found, use the full path:

```bash
bash ~/dsh-pad-deploy/scripts/dsh-runner
```

Switch back to Dterm afterwards; it retries automatically. The **Copy command** button puts
`dsh-runner` on the clipboard.

## 4. What works

- The same DSH UI as in the browser: chat, terminal, files, attachments, downloads, voice input.
- Attachments: the composer's **+ → File** entry, or the paperclip, opens the system picker.
- Downloads go to `Download/DSH/`.
- Back gesture walks web history first, then sends the app to the background.
- External links, mail and phone links open in their own apps.
- Voice: the first microphone request shows the system permission dialog.

## 5. FAQ

**Blank screen / endless spinner**
Long-press the icon → Reload. If it persists, check that the Termux service is up, then swipe the
app away from recents and reopen it.

**"Login expired"**
The DSH process restarted and the cookie aged out (30 days). Paste the URL once as in section 2.

**The microphone button does nothing**
The first tap asks for permission; choose Allow. If you denied it, enable it under
Settings → Apps → Dterm → Permissions → Microphone.

**The launcher icon did not change**
Launcher cache; wait a moment or restart the launcher.

**Updating later**
Install the new APK over the old one. "Signature mismatch" means the signing key changed — uninstall
first (this clears the login cookie, so log in again afterwards).
