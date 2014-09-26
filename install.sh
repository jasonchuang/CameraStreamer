#!/bin/sh

~/Tools/android-ndk-r9/ndk-build -B \
&& ant clean debug install \
&& adb -d shell "am start -a android.intent.action.MAIN -n com.jasonsoft.camerastreamer/.CameraStreamerActivity"
