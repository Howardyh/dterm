#!/data/data/com.termux/files/usr/bin/bash
# 先同步文件并确保 DSH 服务在跑，然后拉起 Dterm 应用（而不是浏览器）。
set -uo pipefail
url=$(dsh-open --url) || {
  echo "DSH 服务没起来，运行 dsh-status / dsh-log 看看。" >&2
  exit 1
}
if ! am start -n dev.dsh.pad/dev.dsh.pad.MainActivity >/dev/null 2>&1; then
  termux-open-url "$url"
fi
