A=/data/data/com.zedokmusic/files
for cfg in "128 3000 8 0" "128 3000 8 200" "64 4000 8 200" "256 3000 16 200"; do
  echo "== frames blocks voices warm: $cfg"
  $A/wine.sh /sdcard/ZedokTest/vst3host.exe 'D:\ZedokTest\Podolski.vst3' $cfg 2>&1 | grep RESULT
done
