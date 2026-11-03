# Security

## What Dterm is

A local-only Android client for a DeepSeek Harness instance running on the same device. It never
sends data to any third-party server; every request goes to `127.0.0.1`.

## Trust model

- **DSH server**: bound to loopback by `dsh-web-service` (`--host 127.0.0.1`).
- **Browser session**: a signed cookie valid for 30 days, derived from a secret stored under
  `$DSH_HOME` (`.dsh`).
- **Launch token**: minted per `dsh web` process, printed to `~/.local/state/dsh/web.log`.
- **Token bridge** (`server/dsh-token-bridge`): listens on `127.0.0.1:3081`, requires
  `Authorization: Bearer <secret>`, and returns the newest token from the log.
  The shared secret is compiled into the APK.

Anything installed on the device could, in principle, extract the bridge secret from the APK and
then obtain a token. That is roughly equivalent to holding the browser cookie, so the design suits
a personal device. On a shared device, treat DSH itself as accessible to every local app.

## Secrets in this repository

None. The following are generated locally and excluded by `.gitignore`:

- the APK signing keystore and its passphrase file (`*.p12`, `*.pass`)
- `~/.local/state/dsh/bridge.secret`
- the generated `BuildSecrets.java`

## Reporting

This is a personal project; open a regular issue for anything that looks wrong, and avoid attaching
tokens, logs with launch URLs, or screenshots that contain session content.
