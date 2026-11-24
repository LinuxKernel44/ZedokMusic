A=/data/data/com.zedokmusic/files
rm -rf /sdcard/ZedokTest/CCopy
S=$(date +%s)
cp -r $A/pfx3/drive_c /sdcard/ZedokTest/CCopy 2>&1 | tail -2
echo "copy to shared storage: $(( $(date +%s) - S )) s; files=$(find /sdcard/ZedokTest/CCopy | wc -l)"
