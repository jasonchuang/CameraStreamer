#!/bin/sh
if [ $# -eq 0 ]
then
    echo "Usage: sh ndk-stack.sh log.txt"
    exit 1
fi

~/Tools/android-ndk-r9/ndk-stack -sym ./obj/local/armeabi/ -dump $1
#~/Tools/android-ndk-r9/ndk-stack -sym ./obj/local/armeabi-v7a/ -dump $1
