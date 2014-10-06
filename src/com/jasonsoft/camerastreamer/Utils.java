package com.jasonsoft.camerastreamer;

import android.util.Log;

public class Utils {

    private static final boolean DEBUG_LOG = false;

    public Utils() {
        super();
    }

    public static int readUnsignedShort(byte[] data, int index) {
        short value = (short) ((data[index] & 0xff) << 8 | data[index + 1] & 0xff);
        return value & 0xFFFF;
    }

    public static long readUnsignedInt(byte[] data, int index) {
        int value = (int) ((data[index] & 0xff) << 24 | (data[index + 1] & 0xff) << 16 |
                (data[index + 2] & 0xff) << 8 | data[index + 3] & 0xff);
        return value & 0xFFFFFFFFL;
    }

    public static boolean isSpsNalu(byte[] header) {
        int type = (int) (header[4] & 0x1F);
        return isNaluHeaderPrefix(header) && type == H264VideoPacketizer.NALU_TYPE_SPS;
    }

    public static boolean isIdrNalu(byte[] header) {
        int type = (int) (header[4] & 0x1F);
        return isNaluHeaderPrefix(header) && type == H264VideoPacketizer.NALU_TYPE_SLICE_IDR;
    }

    public static boolean isSliceNalu(byte[] header) {
        int type = (int) (header[4] & 0x1F);
        return isNaluHeaderPrefix(header) && type == H264VideoPacketizer.NALU_TYPE_SLICE;
    }

    public static boolean isNaluHeaderPrefix(byte[] header) {
        return header[0] == 0 && header[1] == 0 && header[2] == 0 && header[3] == 1;
    }

    public static void printDebugLogs(String tag, String msg) {
        if (DEBUG_LOG) {
            Log.d(tag, msg);
        }
    }

}
