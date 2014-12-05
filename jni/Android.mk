# Copyright (C) 2009 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_ALLOW_UNDEFINED_SYMBOLS=false

PATH_TO_FFMPEG_SOURCE := $(LOCAL_PATH)/ffmpeg-2.2
LOCAL_C_INCLUDES += $(PATH_TO_FFMPEG_SOURCE)
LOCAL_C_INCLUDES += $(LOCAL_PATH)/yuv2rgb/include

LOCAL_MODULE    := media_api
LOCAL_CFLAGS := -D__ARM_NEON__ -mfpu=neon
LOCAL_SRC_FILES := video_api.c yuv2rgb/src/yuv2rgb.neon.S audio_api.c
LOCAL_LDLIBS := -llog -lz -ljnigraphics -lm
LOCAL_SHARED_LIBRARIES := libavcodec libavutil libavformat libswscale libswresample

include $(BUILD_SHARED_LIBRARY)
# Due to GNU Make limitations, NDK_MODULE_PATH must not contain any space.
$(call import-add-path,$(LOCAL_PATH)/ffmpeg-2.2/android)
$(call import-module,armv7-neon)
