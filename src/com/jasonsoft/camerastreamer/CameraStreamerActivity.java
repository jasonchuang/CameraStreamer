package com.jasonsoft.camerastreamer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.ToggleButton;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.SocketException;

import net.majorkernelpanic.streaming.Session;
import net.majorkernelpanic.streaming.SessionBuilder;
import net.majorkernelpanic.streaming.audio.AudioQuality;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.video.VideoQuality;

public class CameraStreamerActivity extends Activity implements Session.Callback, SurfaceHolder.Callback
            , View.OnClickListener{
    private static final String TAG = CameraStreamerActivity.class.getSimpleName();

    private static final int BUFFER_SIZE = 8192; // 8K
    private static final int FRAME_BUFFER_SIZE = 500 * 1024; // 500K
    private static final int UDP_PORT = 5006;

    private static final int VIDEO_WIDTH = 640;
    private static final int VIDEO_HEIGHT = 480;

    private static final int V_P_X_CC_BYTE_INDEX = 0;
    private static final int M_PT_BYTE_INDEX = 1;
    private static final int SEQ_START_BYTE_INDEX = 2;
    private static final int TS_START_BYTE_INDEX = 4;
    private static final int SSRC_START_BYTE_INDEX = 8;
    private static final int FU_IDENTIFIER_BYTE_INDEX = 12;
    private static final int FU_HEADER_INDEX = 13;
    private static final int RTP_HEADER_SIZE = 12;
    private static final int H264_RTP_HEADER_SIZE = 13;

    private static final int NAL_UNIT_SINGLE_PACKET_START = 1;
    private static final int NAL_UNIT_SINGLE_PACKET_END = 23;
    private static final int NAL_UNIT_FU_A = 28;
    private static final int NAL_UNIT_FU_B = 29;

    private SurfaceView mLocalSurfaceView;
    private VideoSurfaceView mRemoteSurfaceView;
    private TextView mSentStats;
    private TextView mReceivedStats;
    private Bitmap mLocalBitmap = null;
    private Bitmap mRemoteBitmap = null;
    private ToggleButton mRecordButton;
    private ToggleButton mViewButton;
    private UDPReceiverAsyncTask mUDPReceiverAsyncTask;
    private byte mFrameBuffer[] = new byte[FRAME_BUFFER_SIZE];
    private int mFrameBufferPosition = 0;
	private Session mSession;
    private EditText mDestinationEditText;
    private boolean mIsViewing;

    static {
        System.loadLibrary("avutil-52");
        System.loadLibrary("avcodec-55");
        System.loadLibrary("avformat-55");
        System.loadLibrary("avformat-55");
        System.loadLibrary("swscale-2");

        System.loadLibrary("media_api");
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mRecordButton = (ToggleButton) findViewById(R.id.record_button);
        mViewButton = (ToggleButton) findViewById(R.id.view_button);
        mLocalSurfaceView = (SurfaceView) findViewById(R.id.local_surface_view);
        mRemoteSurfaceView = (VideoSurfaceView) findViewById(R.id.remote_surface_view);
        mSentStats = (TextView) findViewById(R.id.sent_stats);
        mReceivedStats = (TextView) findViewById(R.id.received_stats);
        mDestinationEditText = (EditText) findViewById(R.id.destination_edit);

        mSession = SessionBuilder.getInstance()
                .setCallback(this)
                .setSurfaceView(mLocalSurfaceView)
                .setPreviewOrientation(90)
                .setContext(getApplicationContext())
                .setAudioEncoder(SessionBuilder.AUDIO_NONE)
                .setAudioQuality(new AudioQuality(16000, 32000))
                .setVideoEncoder(SessionBuilder.VIDEO_H264)
                .setVideoQuality(new VideoQuality(VIDEO_WIDTH, VIDEO_HEIGHT, 10, 500000))
                .setVideoPacketizer(new InstaVideoPacketizer())
                .build();
        mLocalSurfaceView.getHolder().addCallback(this);
        mLocalSurfaceView.setZOrderOnTop(true);
        mRecordButton.setOnClickListener(this);
        mViewButton.setOnClickListener(this);
        mIsViewing = false;
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
        case R.id.record_button:
            // Starts/stops streaming
            mSession.setDestination(mDestinationEditText.getText().toString());
            if (!mSession.isStreaming()) {
                mSession.configure();
                mRecordButton.setChecked(true);
            } else {
                mSession.stop();
                mRecordButton.setChecked(false);
            }

            break;
        case R.id.view_button:
            if (!mIsViewing) {
                mUDPReceiverAsyncTask = new UDPReceiverAsyncTask();
                mUDPReceiverAsyncTask.execute("test");

                mIsViewing = true;
                mViewButton.setChecked(true);
            } else {
                if (mUDPReceiverAsyncTask != null) {
                    mUDPReceiverAsyncTask.cancel(true);
                }
                mIsViewing = false;
                mViewButton.setChecked(false);
            }
            break;
        default:
            break;
        }
    }

    class UDPReceiverAsyncTask extends AsyncTask<String, Integer, Void> {
        private String data;
        private int receivedPacketCounts = 0;

        public UDPReceiverAsyncTask() {
        }

        @Override
        protected Void doInBackground(String... params) {
            nativeStreamingMediaInit();
            mRemoteBitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888);
            nativeStreamingPrepareBitmap(mRemoteBitmap, VIDEO_WIDTH, VIDEO_HEIGHT);

            byte buffer[] = new byte[BUFFER_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket(UDP_PORT);
            } catch (SocketException e) {
                Log.d("jason", "SocketException:" + e);
            }

            if (socket == null) {
                return null;
            }

            while (true) {
                if (isCancelled()) {
                    Log.d("jason", "Abort UDP receiving loop");
                    socket.close();
                    break;
                }

//                Log.d("jason", "start receive");
                try {
                    socket.receive(packet);
                    receivedPacketCounts++;
//                    Log.d("jason", "Receiving len:" + packet.getLength());
                    int frameLen = parseRTP(packet.getData(), packet.getLength());
                    if (frameLen > 0) {
                        printHexList(mFrameBuffer, Math.min(frameLen, 20));
                        nativeStreamingDecodeFrame(mFrameBuffer, frameLen);
                        mRemoteSurfaceView.setVideoSurfaceBitmap(mRemoteBitmap);
                    }
                    SystemClock.sleep(10);
                } catch (IOException e) {
                    Log.d("jason", "IOException:" + e);
                }
                // Log.d("jason", "receive done");
            }

            return null;
        }

        void printHexList(byte[] data, int len) {
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < len; i++) {
                int value = (int) (data[i] & 0xff);
                sb.append(Integer.toString(value, 16) + " ");
            }

            Log.i("jason", "Hext List:" + sb);
        }

        private int parseRTP(byte[] data, int len) {
            Log.d("jason", "parseRTP start");
            /* Here is the RTP header
             * 0                   1                   2                   3
             * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
             * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
             * |V=2|P|X|  CC   |M|     PT      |       sequence number         |
             * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
             * |                           timestamp                           |
             * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
             * |           synchronization source (SSRC) identifier            |
             * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
             */
            int marker = (int)(data[M_PT_BYTE_INDEX] & 0x80) >> 7;
            int pt = (int)(data[M_PT_BYTE_INDEX] & 0x7F);
            int seq = Utils.readUnsignedShort(data, SEQ_START_BYTE_INDEX);
            long ts = Utils.readUnsignedInt(data, TS_START_BYTE_INDEX);
            long ssrc = Utils.readUnsignedInt(data, SSRC_START_BYTE_INDEX);

            Log.d("jason", "marker:" + marker);
//            Log.d("jason", "pt:" + pt);
            Log.d("jason", "seq:" + seq);
//            Log.d("jason", "ts:" + ts);
//            Log.d("jason", "ssrc:" + ssrc);
//
//            /* Here is the FU indicator header
//             * +---------------+
//             * |0|1|2|3|4|5|6|7|
//             * +-+-+-+-+-+-+-+-+
//             * |F|NRI|  Type   |
//             * +---------------+
//             */
//
//            /* FU Indicator; Type = 28 ---> FU-A */
//            int type = (int)(data[FU_IDENTIFIER_BYTE_INDEX] & 0x1F);
//
//            /* The FU header
//             * +---------------+
//             * |0|1|2|3|4|5|6|7|
//             * +-+-+-+-+-+-+-+-+
//             * |S|E|R|  Type   |
//             * +---------------+
//             */
//            int startBit = (int)(data[FU_HEADER_INDEX] & 0x80) >> 7;
//            int endBit = (int)(data[FU_HEADER_INDEX] & 0x40) >> 6;
//            int nalUnitType = (int)(data[FU_HEADER_INDEX] & 0x1F);
//            Log.d("jason", "h264 type:" + type);
//            Log.d("jason", "h264 startBit:" + startBit);
//            Log.d("jason", "h264 endBit:" + endBit);
//            Log.d("jason", "h264 Nal_unit_type:" + nalUnitType);
//
            publishProgress(seq);

//            return (type == 1 || type == NAL_UNIT_FU_A || type == NAL_UNIT_FU_B ) ? true : false;

            int payloadLen = len - RTP_HEADER_SIZE;
            int offset = RTP_HEADER_SIZE;

//            if (type >= NAL_UNIT_SINGLE_PACKET_START && type <= NAL_UNIT_SINGLE_PACKET_END) {
//                mFrameBuffer[0] = 0x00;
//                mFrameBuffer[1] = 0x00;
//                mFrameBuffer[2] = 0x00;
//                mFrameBuffer[3] = 0x01;
//                mFrameBufferPosition += 4;
//                payloadLen -= RTP_HEADER_SIZE;
//                offset = RTP_HEADER_SIZE;
//            } else if (type == NAL_UNIT_FU_A) {
//                if (startBit == 1) {
//                    mFrameBufferPosition = 0;
////                    mFrameBuffer[0] = 0x00;
////                    mFrameBuffer[1] = 0x00;
////                    mFrameBuffer[2] = 0x00;
////                    mFrameBuffer[3] = 0x01;
//                    int fnri = (int)(data[FU_IDENTIFIER_BYTE_INDEX] & 0xE0);
//                    mFrameBuffer[0] = (byte)(fnri | nalUnitType);
//                    mFrameBufferPosition += 1;
//                }
//                payloadLen -= H264_RTP_HEADER_SIZE;
//                offset = H264_RTP_HEADER_SIZE;
//            } else {
//                Log.d("jason", "Not a valid nalu type");
//                return -1;
//            }
//
            Log.d("jason", "packetLen:" + len);
//            Log.d("jason", "payload Content:");
//            printHexList(data, Math.min(payloadLen, 20));
            Log.d("jason", "payload len:" + payloadLen);
//            Log.d("jason", "before packet copy mFrameBufferPosition:" + mFrameBufferPosition);
            System.arraycopy(data, offset, mFrameBuffer, mFrameBufferPosition, payloadLen);
            mFrameBufferPosition += payloadLen;
//            Log.d("jason", "after packet copy mFrameBufferPosition:" + mFrameBufferPosition);

            Log.d("jason", "parseRTP end");
            if (marker > 0) {
                int value = mFrameBufferPosition;
                mFrameBufferPosition = 0;
                return value;
            } else {
                // frame not ready
                return -1;
            }
        }

        @Override
        protected void onProgressUpdate(Integer... params) {
            mReceivedStats.setText(receivedPacketCounts + "/" + params[0]);
        }

        @Override
        protected void onPostExecute(Void result) {
        }
    }

    @Override
    public void onBitrateUpdate(long bitrate) {
        Log.d(TAG, "Bitrate: "+bitrate);
        mSentStats.setText("Streaming bitrate " + bitrate);
    }

    @Override
    public void onSessionError(int message, int streamType, Exception e) {
        mRecordButton.setEnabled(true);
    }

    @Override

    public void onPreviewStarted() {
        Log.d(TAG,"Preview started.");
    }

    @Override
    public void onSessionConfigured() {
        Log.d(TAG,"Preview configured.");
        // Once the stream is configured, you can get a SDP formated session description
        // that you can send to the receiver of the stream.
        // For example, to receive the stream in VLC, store the session description in a .sdp file
        // and open it with VLC while streming.
        Log.d(TAG, mSession.getSessionDescription());
        mSession.start();
    }

	@Override
	public void onDestroy() {
		super.onDestroy();
		mSession.release();
	}

    @Override
    public void onSessionStarted() {
        Log.d(TAG,"Session started.");
        mRecordButton.setEnabled(true);
    }

    @Override
    public void onSessionStopped() {
        Log.d(TAG,"Session stopped.");
        mRecordButton.setEnabled(true);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width,
            int height) {
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d("jason", "surfaceCreated mSession.startPreview:");
        mSession.startPreview();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mSession.stop();
    }

    public native int nativeMediaInit(String fileName);
    public native int[] nativeGetMediaResolution();
    public native int nativeGetMediaDuration();
    public native int nativePrepareBitmap(Bitmap bitmap, int width, int height);
    public native int nativeGetFrame();
    public native int nativeMediaFinish(Bitmap bitmap);

    // For streaming media
    public native int nativeStreamingMediaInit();
    public native int nativeStreamingPrepareBitmap(Bitmap bitmap, int width, int height);
    public native int nativeStreamingDecodeFrame(byte[] inbuf, int len);
}
