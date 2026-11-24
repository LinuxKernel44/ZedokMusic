A=/data/data/com.zedokmusic/files
ln -sfn /sdcard $A/pfx/dosdevices/d:; ln -sfn $A $A/pfx/dosdevices/e:
FR=${1:-256}; IT=${2:-5000}
rm -f $A/shm.bin
$A/wine.sh /sdcard/ZedokTest/zhost.exe 'D:\ZedokTest\testplug.dll' 'E:\shm.bin' > $A/host.log 2>&1 &
sleep 1
$A/pingpong $A/shm.bin $FR $IT
sleep 1
grep -v BOX64 $A/host.log
