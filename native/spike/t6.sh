A=/data/data/com.zedokmusic/files
rm -rf /sdcard/ZedokTest/CDrive $A/pfx2; mkdir -p /sdcard/ZedokTest/CDrive $A/pfx2
ln -s /sdcard/ZedokTest/CDrive $A/pfx2/drive_c
WINEPREFIX=$A/pfx2 WINEDEBUG=-all $A/wine.sh wineboot -i > $A/wb.log 2>&1
echo "wineboot exit=$?"
echo "files on sdcard: $(find /sdcard/ZedokTest/CDrive | wc -l)  size: $(du -sh /sdcard/ZedokTest/CDrive | cut -f1)"
ls /sdcard/ZedokTest/CDrive; ls /sdcard/ZedokTest/CDrive/windows | head; echo "pfx2: $(ls $A/pfx2 | tr '\n' ' ')"
WINEPREFIX=$A/pfx2 WINEDEBUG=-all $A/wine.sh cmd /c "echo CDRIVE_OK" 2>&1 | grep -v BOX64 | head -3
grep -iE "err|fail|denied" $A/wb.log | grep -v -E "nodrv|winediag|apartment|ole:|fixme" | head -5
