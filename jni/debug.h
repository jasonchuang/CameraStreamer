#ifndef _ANDDEBUG_H_
#define _ANDDEBUG_H_

#include <android/log.h>

#define LOG_TAG "jason"
//#define DEBUG_LOG

#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#ifdef DEBUG_LOG
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,   LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...)
#endif
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,    LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,    LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,   LOG_TAG, __VA_ARGS__)

#endif
