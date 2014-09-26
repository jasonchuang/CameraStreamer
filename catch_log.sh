#!/bin/sh
adb logcat -c && adb logcat -v time | tee jason

