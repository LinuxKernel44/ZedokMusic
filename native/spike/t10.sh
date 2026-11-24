A=/data/data/com.zedokmusic/files
t() { S=$(date +%s%N); WINEPREFIX=$A/pfx3 WINEDEBUG=-all $A/wine.sh cmd /c "echo x" >/dev/null 2>&1; echo "$1: $(( ($(date +%s%N) - S) / 1000000 )) ms"; }
t "C on shared (1st)"; t "C on shared (2nd)"
rm $A/pfx3/drive_c; mv $A/pfx3/drive_c.bak $A/pfx3/drive_c
t "C internal (1st)"; t "C internal (2nd)"
