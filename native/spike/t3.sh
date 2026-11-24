A=/data/data/com.zedokmusic/files
$A/wine.sh cmd /c 'dir Z:\sdcard\ZedokTest' 2>&1 | grep -v BOX64 | head -12
ls -la $A/pfx/dosdevices
