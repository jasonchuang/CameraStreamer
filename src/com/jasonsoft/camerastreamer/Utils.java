package com.jasonsoft.camerastreamer;

public class Utils {

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
}
