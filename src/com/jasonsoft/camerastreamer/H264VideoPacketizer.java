package com.jasonsoft.camerastreamer;

import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import net.majorkernelpanic.streaming.rtp.H264Packetizer;
import net.majorkernelpanic.streaming.rtp.MediaCodecInputStream;

public class H264VideoPacketizer extends H264Packetizer {
    private static final String TAG = H264VideoPacketizer.class.getSimpleName();

    public static final int NALU_TYPE_SLICE = 1;
    public static final int NALU_TYPE_SLICE_IDR = 5;
    public static final int NALU_TYPE_SPS = 7;

    private static final boolean LOCAL_FILE_DEBUG = false;

    private FileOutputStream mFos;
    private byte[] mSpsPps = null;
    private byte[] mEncodedDataBuffer = new byte[MAXPACKETSIZE];

    public H264VideoPacketizer() {
        super();
    }

    /**
     * Reads a NAL unit in the FIFO and sends it.
     */
    @Override
    public void start() {
        Log.d(TAG, "InstaVideoPacketizer start");

        if (LOCAL_FILE_DEBUG) {
            String fileName = Environment.getExternalStorageDirectory().getAbsolutePath() + "/local_streaming.h264";
            File file = new File(fileName);
            try {
                mFos = new FileOutputStream(file);
            } catch (FileNotFoundException e) {
            }
        }

        super.start();
    }

    /**
     * Reads a NAL unit in the FIFO and sends it.
     * If it is too big, we split it in FU-A units (RFC 3984).
     */
    @Override
    protected void send() throws IOException, InterruptedException {
        int maxPayloadLen = MAXPACKETSIZE - rtphl;
        int len = is.read(mEncodedDataBuffer, 0, maxPayloadLen);
        boolean isSps = Utils.isSpsNalu(mEncodedDataBuffer);
        boolean isIdr = Utils.isIdrNalu(mEncodedDataBuffer);

        if (isSps) {
            mSpsPps = new byte[len];
            System.arraycopy(mEncodedDataBuffer, 0, mSpsPps, 0, len);
            Log.d(TAG, "Got spspps len:" + len);
        }

        // Prepare encoded data to rtp buffer
        buffer = socket.requestBuffer();
        int sendLen = len;
        if (mSpsPps != null && isIdr) {
            System.arraycopy(mSpsPps, 0, buffer, rtphl, mSpsPps.length);
            System.arraycopy(mEncodedDataBuffer, 0, buffer, rtphl + mSpsPps.length, len);
            sendLen += mSpsPps.length;
        } else {
            System.arraycopy(mEncodedDataBuffer, 0, buffer, rtphl, sendLen);
        }

        long ts = ((MediaCodecInputStream)is).getLastBufferInfo().presentationTimeUs*1000L;
        if (len < maxPayloadLen) {
            socket.markNextPacket();
        }

        socket.updateTimestamp(ts);
        super.send(rtphl + sendLen);
        // 20 ms a little stuck
        SystemClock.sleep(10);

        if (LOCAL_FILE_DEBUG) {
            try {
                mFos.write(buffer, rtphl, sendLen);
            } catch (IOException e) {
            }
        }
    }

    public void stop() {
        Log.d(TAG, "InstaVideoPacketizer stop");

        super.stop();

        if (LOCAL_FILE_DEBUG) {
            try {
                mFos.flush();
                mFos.close();
            } catch (IOException e) {
            }
        }
    }

}
