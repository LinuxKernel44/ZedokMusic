A=/data/data/com.zedokmusic/files
ln -sfn /sdcard $A/pfx/dosdevices/d:
WINEDEBUG=${WD:--all} $A/wine.sh /sdcard/ZedokTest/vst3host.exe "D:\\ZedokTest\\$1" ${2:-256} ${3:-2000} 2>&1 | grep -v BOX64
