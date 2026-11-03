# Dterm

[![Latest release](https://img.shields.io/github/v/release/Howardyh/dterm?color=1F4DFF)](https://github.com/Howardyh/dterm/releases)
[![License](https://img.shields.io/badge/license-MIT-22C55E.svg)](LICENSE)

[中文](README.md) · **English**

**An Android app that makes DeepSeek Harness running in Termux feel like a native application.**

Dterm is not a bundle of DSH — it is a 46 KB full-screen shell. It ships a launcher icon, has no
address bar, handles the back gesture, and talks to the DSH server already running on
`127.0.0.1:3080`. The harness itself still lives in Termux + PRoot Ubuntu; Dterm takes care of
the part that makes it feel like an app.

<p align="center"><img src="docs/icon.png" width="180" alt="Dterm icon"></p>

## Features

| | |
| --- | --- |
| **Zero-tap login** | A loopback token bridge hands the per-process launch token to the app, which exchanges it for a 30-day cookie. Manual paste is only the last resort. |
| **One-tap service start** | Runs `dsh-start` through Termux's `RUN_COMMAND`, with live progress printed in the Termux session. |
| **Automatic return** | When the command finishes, Termux calls `am start` and brings Dterm back to the foreground. |
| **Launcher shortcuts** | Long-press the icon: reload / open in browser / open Termux. |
| **Attachments & downloads** | System file picker for uploads, downloads saved to `Download/DSH/`. |
| **Voice input** | The web UI's `getUserMedia` flows through the normal microphone permission. |
| **Native fallbacks** | Service down, login required, renderer crash — each has a real screen instead of a blank page. |
| **Platform polish** | Live dark mode, Android 12+ splash screen, Android 13 themed icon, predictive back. |

## How it works

### 1. Login: cookie → token bridge → paste

Every `dsh web` process mints a random launch token and writes the authenticated URL to
`~/.local/state/dsh/web.log`; a browser exchanges that URL for a 30-day signed cookie.

On launch Dterm requests `/` with the stored cookie:

- `200` → already authenticated, load the UI;
- `401` → fetch the current token from `127.0.0.1:3081/token` (shared-secret auth) and load
  `/?token=…`;
- bridge unavailable → show the paste screen.

The bridge is `server/dsh-token-bridge`, started alongside DSH by `server/dsh-web-service`.
It listens on loopback only and reads `web.log` on demand, so DSH restarts need no attention.

### 2. Start: Termux RUN_COMMAND

When the service is down, "Start DSH" hands Termux this command:

```bash
printf '▸ Dterm: starting DSH…'
dsh-start
printf '▸ ready, returning to Dterm…'
am start -n dev.dsh.pad/dev.dsh.pad.MainActivity
```

Termux executes it in a visible session, prints progress, and pulls Dterm back when done.
Meanwhile the app polls `127.0.0.1:3080` for up to 150 seconds.

## Layout

```
app/        Android project without Gradle: manifest, Java, resources, build scripts, icon generator
server/     Loopback token bridge and the dsh-web-service wrapper
termux/     Example Termux:Widget shortcut
docs/       Install guide, icon
release/    Prebuilt APK
```

## Install

1. Download `release/Dterm-1.4.4.apk` (or grab it from the
   [latest release](https://github.com/Howardyh/dterm/releases/latest)) and allow installation
   from unknown sources.
2. Make sure the DSH service is running in Termux (`dsh-start`).
3. Open Dterm — it should land straight in the UI.

Detailed steps and troubleshooting: [`docs/install.md`](docs/install.md) (Chinese).

"Start DSH" needs one Termux setting:

```bash
echo 'allow-external-apps=true' >> ~/.termux/termux.properties
termux-reload-settings
```

## Build from source

Gradle is deliberately avoided; the whole pipeline runs inside an arm64 PRoot Ubuntu.

Requirements:

- JDK 17
- Android SDK `build-tools;34.0.0` (for `d8` and `apksigner`) and `platforms;android-34`
- an **aarch64 `aapt2`** (Debian's `aapt` package provides one; the binary shipped in the
  official build-tools is x86_64 and will not run here)

```bash
cd app
ANDROID_SDK_ROOT=/path/to/sdk JAVA_HOME=/path/to/jdk \
AAPT2=/path/to/aapt2 bash build.sh
```

`build.sh` generates the icons, runs `aapt2 compile/link`, `javac`, `d8`, assembles the zip
with `tools/assemble.py` (a stand-in for the non-runnable `zipalign`), and signs with
`apksigner`. The signing key defaults to `/opt/dsh-build/keys`; override with `DSH_KEYDIR`.

## Version history

See [CHANGELOG.md](CHANGELOG.md) (Chinese). Currently 1.4.4.

## Known limitations

- Loopback only; remote DSH instances are not supported.
- Downloads triggered through JS `blob:` URLs never reach `DownloadListener`; use "open in browser".
- No pull-to-refresh; use the launcher shortcut "reload" when a page hangs.
- "Start DSH" depends on Termux's `RUN_COMMAND` and `allow-external-apps`; without them the app
  falls back to opening Termux.

## Security

- The token bridge binds `127.0.0.1` and requires `Authorization: Bearer <secret>`; the secret is
  compiled into the APK. Anything installed on the device could in principle extract it, so the
  trust level is roughly that of the DSH browser cookie — fine for a personal device.
- The repository contains **no secrets**: signing keys, `bridge.secret` and the generated
  `BuildSecrets.java` are all excluded by `.gitignore`.

## License

MIT — see [LICENSE](LICENSE).
