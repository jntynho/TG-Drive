#!/usr/bin/env bash
# Runs only on the disposable, rootable CI emulator. Never uninstall or clear app data.
set -euo pipefail
logfile="$RUNNER_TEMP/tgdrive-upgrade-check.log"
exec > >(tee "$logfile") 2>&1
report_failure() {
  status=$?
  if (( status != 0 )); then
    adb logcat -d -s AndroidRuntime -t 80 >> "$logfile" 2>&1 || true
    python3 - "$logfile" <<'PYREPORT'
import pathlib,sys
text='\n'.join(pathlib.Path(sys.argv[1]).read_text(errors='replace').splitlines()[-65:])
text=text.replace('%','%25').replace('\r','%0D').replace('\n','%0A')
print('::error title=Signed upgrade diagnostics::'+text)
PYREPORT
  fi
}
trap report_failure EXIT
package=app.tgdrive
tools="$ANDROID_HOME/build-tools/36.0.0"
baseline="$RUNNER_TEMP/tg-drive-0.1.1.apk"
curl --fail --location --retry 3 'https://github.com/jntynho/TG-Drive/releases/download/v0.1.1/tg-drive-0.1.1.apk' --output "$baseline"
expected="$(tr -d '\r\n ' < scripts/sideload-cert-sha256.txt)"
for apk in "$baseline" upgrade/new/*.apk; do
  cert="$("$tools/apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
  [[ "$cert" == "$expected" ]]
done
echo "Install published universal baseline"
adb install "$baseline"
adb shell am start -W -n "$package/.MainActivity"
echo "Seed synthetic app-owned data on the disposable emulator"
adb root
adb wait-for-device
appdir="/data/user/0/$package"
uid="$(adb shell stat -c %u "$appdir" | tr -d '\r')"
[[ "$uid" =~ ^[0-9]+$ ]]
adb shell "mkdir -p '$appdir/files' '$appdir/shared_prefs'"
adb shell "printf 'upgrade-data-retained' > '$appdir/files/upgrade-check.txt'"
adb shell "printf '<map><string name=\"marker\">upgrade-pref-retained</string></map>' > '$appdir/shared_prefs/upgrade-check.xml'"
adb shell "chown '$uid:$uid' '$appdir/files' '$appdir/shared_prefs' '$appdir/files/upgrade-check.txt' '$appdir/shared_prefs/upgrade-check.xml'"
adb shell am force-stop "$package"
echo "Install standalone x86_64 update without clearing data"
adb install -r upgrade/new/*.apk
echo "Check UID, private file and preferences survived"
[[ "$(adb shell stat -c %u "$appdir" | tr -d '\r')" == "$uid" ]]
[[ "$(adb shell cat "$appdir/files/upgrade-check.txt" | tr -d '\r')" == upgrade-data-retained ]]
adb shell cat "$appdir/shared_prefs/upgrade-check.xml" | grep -q upgrade-pref-retained
adb shell am start -W -n "$package/.MainActivity"
sleep 3
adb shell pidof "$package"
adb shell dumpsys activity activities | grep -E 'mResumedActivity:.*app.tgdrive/.MainActivity'
printf '## Signed APK upgrade\n\nPublished universal 0.1.1 → standalone x86_64 APK: installed in place, UID/private file/preferences retained, release activity running.\n\nAuthenticated Telegram sessions and physical ARM devices were not exercised.\n' >> "$GITHUB_STEP_SUMMARY"
