A=/data/data/com.zedokmusic/files
rm -rf $A/pfx3; mkdir -p $A/pfx3
S=$(date +%s)
WINEPREFIX=$A/pfx3 WINEDEBUG=-all $A/wine.sh wineboot -i > $A/wb3.log 2>&1
echo "internal wineboot exit=$? in $(( $(date +%s) - S )) s; files=$(find $A/pfx3/drive_c | wc -l) size=$(du -sh $A/pfx3/drive_c | cut -f1)"
