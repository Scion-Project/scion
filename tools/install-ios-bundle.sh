#!/usr/bin/env bash
# Install Scion and its iOS 16.5 VM (AVF) on a Pixel over adb, from the bundle the "ios-bundle" workflow makes
# (scion-ios-bundle.7z, opened with its passphrase). Run it from the bundle's folder:
#
#   ./install.sh [ADB_SERIAL]
#
# What it does, in order:
#   1. checks the bundle (SHA256SUMS);
#   2. installs or updates the app (adb install -r: an existing install keeps its data; one signed with
#      another key is left as it is);
#   3. grants the two VM permissions;
#   4. sets the one global setting Android's custom-VM API needs
#      (hidden_api_blacklist_exemptions, docs/PHONE-QUICKSTART.md; undo:
#      adb shell settings delete global hidden_api_blacklist_exemptions);
#   5. copies the VM's files into the app's files/ios165 through run-as, checking each by sha256;
#   6. registers the folder as the install "iOS 16.5 (AVF)".
# It never touches files/vm, an existing VM's folder or its data, and stops if a VM of the app is running.
# Needs: adb, python3, a Pixel with pKVM (Pixel 8 or newer) and USB debugging on.
set -euo pipefail
cd "$(dirname "$0")"
PKG=io.github.dylanneve1.iosonpixel
ADB=(adb)
[ $# -ge 1 ] && ADB=(adb -s "$1")
DIR=files/ios165
EXEMPT='Landroid/system/virtualmachine/VirtualMachineCustomImageConfig,Landroid/system/virtualmachine/VirtualMachineConfig$Builder;->setCustomImageConfig('
sh_() { "${ADB[@]}" shell "$@"; }
sum() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

echo "== checking the bundle"
if command -v sha256sum >/dev/null; then sha256sum -c --quiet SHA256SUMS; else shasum -a 256 -c --quiet SHA256SUMS; fi

echo "== waiting for the phone"
"${ADB[@]}" wait-for-device
model=$(sh_ getprop ro.product.model | tr -d '\r')
echo "   $model, Android $(sh_ getprop ro.build.version.release | tr -d '\r')"
case "$model" in *Pixel*) ;; *) echo "   this is not a Pixel: AVF custom VMs may not be there (continuing)";; esac
if sh_ dumpsys activity services "$PKG" 2>/dev/null | grep -q ServiceRecord; then
	echo "the app is running a VM: stop it in the app first" >&2
	exit 1
fi

echo "== the app"
# A phone whose Scion is signed with another key (built on another computer) refuses this one: that app is
# kept as it is, with its data (never uninstalled here), and only the VM's files are added to it.
if ! out=$("${ADB[@]}" install -r scion.apk 2>&1); then
	case "$out" in
	*UPDATE_INCOMPATIBLE*|*signatures*do*not*match*)
		echo "   the installed Scion is signed with another key: keeping it and its data, adding only the VM" ;;
	*) echo "$out" >&2; exit 1 ;;
	esac
fi
sh_ pm grant "$PKG" android.permission.MANAGE_VIRTUAL_MACHINE
sh_ pm grant "$PKG" android.permission.USE_CUSTOM_VIRTUAL_MACHINE
sh_ settings put global hidden_api_blacklist_exemptions "\"$EXEMPT\""
# once, so that it makes its folders and its list of installs
sh_ am start -n "$PKG/.MainActivity" >/dev/null
sleep 6
sh_ am force-stop "$PKG"

echo "== the VM's files into $DIR"
sh_ "run-as $PKG mkdir -p $DIR"
for f in avf.properties Image payload.bin system.apfs; do
	want=$(sum "vm/$f")
	"${ADB[@]}" push "vm/$f" "/data/local/tmp/scion-$f" >/dev/null
	sh_ "cat /data/local/tmp/scion-$f | run-as $PKG sh -c 'cat > $DIR/$f.new && mv $DIR/$f.new $DIR/$f && chmod 600 $DIR/$f'"
	sh_ rm -f "/data/local/tmp/scion-$f"
	got=$(sh_ "run-as $PKG sha256sum $DIR/$f" | cut -d' ' -f1 | tr -d '\r')
	[ "$got" = "$want" ] || { echo "   $f arrived wrong ($got, want $want)" >&2; exit 1; }
	echo "   $f ok"
done

echo "== registering the install"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
"${ADB[@]}" exec-out run-as "$PKG" cat shared_prefs/installs.xml > "$T/installs.xml" 2>/dev/null || true
grep -q 'name="list"' "$T/installs.xml" 2>/dev/null || {
	echo "   the app has no list of installs yet: open Scion once on the phone, close it, and run this again" >&2
	exit 1
}
python3 register-install.py "$T/installs.xml" "$T/installs.new.xml" "/data/user/0/$PKG/$DIR"
if ! cmp -s "$T/installs.xml" "$T/installs.new.xml"; then
	"${ADB[@]}" push "$T/installs.new.xml" /data/local/tmp/scion-installs.xml >/dev/null
	sh_ "cat /data/local/tmp/scion-installs.xml | run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/installs.xml'"
	sh_ rm -f /data/local/tmp/scion-installs.xml
fi
echo
echo "Done. Open Scion, pick \"iOS 16.5 (AVF)\" and press Start. The home screen takes a minute or two."
