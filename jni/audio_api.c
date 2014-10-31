/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
#include <string.h>
#include <jni.h>
#include <android/bitmap.h>

#include "libavcodec/avcodec.h"
#include "libswresample/swresample.h"
#include "debug.h"

#define AUDIO_CHANNAL_COUNT 1
#define AUDIO_BIT_RATE 32000
#define AUDIO_SAMPLE_RATE 16000

AVCodecContext *gAudioAVCodecContext;
AVFrame *gAudioAVFrame;
struct SwrContext *gAudioSwrContext;
int gAudioFrameNumber;

jbyte* g_buffer;

jint
Java_com_jasonsoft_camerastreamer_CameraStreamerActivity_nativeAudioStreamingInit(JNIEnv* env, jobject thiz)
{
    av_register_all();
//    av_log_set_callback(ff_audio_log_callback);

    AVCodec *pCodec = avcodec_find_decoder(CODEC_ID_AAC);
    gAudioAVCodecContext = avcodec_alloc_context3(pCodec);
    gAudioAVCodecContext->channels = AUDIO_CHANNAL_COUNT;
    gAudioAVCodecContext->bit_rate = AUDIO_BIT_RATE;
    gAudioAVCodecContext->sample_rate = AUDIO_SAMPLE_RATE;
    gAudioAVCodecContext->sample_fmt = AV_SAMPLE_FMT_S16;

    LOGI("AVCodec name: %s", pCodec->name);
    LOGI("AVCodec long name: %s", pCodec->long_name);
    if (pCodec == NULL) {
        LOGE("unsupported codec");
        return -1;
    }

    if (avcodec_open2(gAudioAVCodecContext, pCodec, NULL) < 0) {
        LOGE("could not open codec");
        return -1;
    }

    gAudioAVFrame = avcodec_alloc_frame();
    avcodec_get_frame_defaults(gAudioAVFrame);

    return 0;
}

jint
Java_com_jasonsoft_camerastreamer_CameraStreamerActivity_nativePrepareAudioByteBuffer(JNIEnv* env,
        jobject thiz, jobject pByteBuffer) {
    LOGI("nativePrepareByteBuffer");
    if (pByteBuffer == NULL) {
        return false;
    }

    g_buffer = (jbyte*)(*env)->GetDirectBufferAddress(env, pByteBuffer);

    if (g_buffer == NULL) {
        return false;
    }

    gAudioSwrContext = swr_alloc_set_opts(gAudioSwrContext,
            gAudioAVCodecContext->channel_layout, AV_SAMPLE_FMT_S16, gAudioAVCodecContext->sample_rate,
            gAudioAVCodecContext->channel_layout, gAudioAVCodecContext->sample_fmt, gAudioAVCodecContext->sample_rate,
            0, 0);
    return 0;
}


jint
Java_com_jasonsoft_camerastreamer_CameraStreamerActivity_nativeDecodeAudioStreamingFrame(JNIEnv* env, jobject thiz,
        jbyteArray data, jint len)
{
    jbyte *pJbyteBuffer = 0;

    if (data) {
        pJbyteBuffer = (char *) (*env)->GetByteArrayElements(env, data, 0);
    } else {
        return;
    }

    int framefinished;
    AVPacket packet;
    av_init_packet(&packet);
    packet.data = (unsigned char *)(pJbyteBuffer);
    packet.size = len;

    int usedLen = avcodec_decode_audio4(gAudioAVCodecContext, gAudioAVFrame, &framefinished, &packet);
    if (pJbyteBuffer) {
        (*env)->ReleaseByteArrayElements(env, data, pJbyteBuffer, JNI_ABORT);
    }

    int data_size = av_samples_get_buffer_size(NULL, gAudioAVCodecContext->channels,
            gAudioAVFrame->nb_samples, gAudioAVCodecContext->sample_fmt, 0);

    if (framefinished && usedLen > 0) {
        uint8_t pTemp[data_size];
        uint8_t *pOut = (uint8_t *)&pTemp;
        swr_init(gAudioSwrContext);
        swr_convert(gAudioSwrContext, (uint8_t **)(&pOut), gAudioAVFrame->nb_samples,
                (const uint8_t **)gAudioAVFrame->extended_data,
                gAudioAVFrame->nb_samples);

        data_size = av_samples_get_buffer_size(NULL, gAudioAVCodecContext->channels,
                gAudioAVFrame->nb_samples, AV_SAMPLE_FMT_S16, 0);
        memcpy(g_buffer, pOut, data_size);
        gAudioFrameNumber++;

        av_free_packet(&packet);
        return data_size;
    }

    return -1;
}

jint
Java_com_jasonsoft_camerastreamer_CameraStreamerActivity_nativeAudioFinish(JNIEnv* env, jobject thiz)
{
    av_free(gAudioAVFrame);
    avcodec_close(gAudioAVCodecContext);
    return 0;
}
