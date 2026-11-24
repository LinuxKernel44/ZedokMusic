A=/data/data/com.zedokmusic/files
WINEDEBUG=err+all,+loaddll $A/wine.sh /sdcard/ZedokTest/zhost.exe 'Z:\sdcard\ZedokTest\testplug.dll' 'Z:\data\data\com.zedokmusic\files\shm.bin' 2>&1 | grep -v BOX64 | grep -iE "testplug|err:|126|not found" | head -12
