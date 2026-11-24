A=/data/data/com.zedokmusic/files
mv $A/pfx3/drive_c $A/pfx3/drive_c.bak; ln -s /sdcard/ZedokTest/CCopy $A/pfx3/drive_c
S=$(date +%s)
WINEPREFIX=$A/pfx3 WINEDEBUG=-all $A/wine.sh cmd /c "echo C_ON_SHARED_OK & ver" 2>&1 | grep -v BOX64 | head -4
echo "cmd took $(( $(date +%s) - S )) s"
S=$(date +%s)
WINEPREFIX=$A/pfx3 WINEDEBUG=err+all $A/wine.sh /sdcard/ZedokTest/hostname.exe 2>&1 | grep -v BOX64 | grep -v -E "fixme" | head -4
echo "hostname took $(( $(date +%s) - S )) s"
