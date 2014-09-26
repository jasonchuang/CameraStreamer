#!/bin/sh
dragonPid=`adb shell ps | grep com.oplk.dragon | awk '{print $2}'`
echo "com.oplk.dragon PID is $dragonPid"
#adb logcat -c && adb logcat -v time | grep $dragonPid | tee dragonLog
adb logcat -c
#adb logcat -v time | grep -v "D/dalvikvm" | grep --color=auto $dragonPid
adb logcat -v time | grep --color=auto "jason"
#adb logcat -c && adb logcat -v time | grep $dragonPid

