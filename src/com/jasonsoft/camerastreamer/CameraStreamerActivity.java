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
                .setVideoPacketizer(new H264VideoPacketizer())
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
                Log.d(TAG, "SocketException:" + e);
            }

            if (socket == null) {
                return null;
            }

            while (true) {
                if (isCancelled()) {
                    Log.d(TAG, "Abort UDP receiving loop");
                    socket.close();
                    break;
                }

                try {
                    socket.receive(packet);
                    receivedPacketCounts++;
                    int frameLen = parseRTP(packet.getData(), packet.getLength());
                    if (frameLen > 0) {
                        printHexList(mFrameBuffer, Math.min(frameLen, 20));
                        nativeStreamingDecodeFrame(mFrameBuffer, frameLen);
                        mRemoteSurfaceView.setVideoSurfaceBitmap(mRemoteBitmap);
                    }
                    SystemClock.sleep(10);
                } catch (IOException e) {
                    Log.d(TAG, "IOException:" + e);
                }
            }

            return null;
        }

        void printHexList(byte[] data, int len) {
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < len; i++) {
                int value = (int) (data[i] & 0xff);
                sb.append(Integer.toString(value, 16) + " ");
            }

            Log.i(TAG, "Hext List:" + sb);
        }

        private int parseRTP(byte[] data, int len) {
            Log.d(TAG, "parseRTP start");
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

//            Log.d(TAG, "marker:" + marker);
//            Log.d(TAG, "seq:" + seq);
//
            publishProgress(seq);

            int payloadLen = len - RTP_HEADER_SIZE;
            int offset = RTP_HEADER_SIZE;

            System.arraycopy(data, offset, mFrameBuffer, mFrameBufferPosition, payloadLen);
            mFrameBufferPosition += payloadLen;

            Log.d(TAG, "parseRTP end");
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
        Log.d(TAG, "surfaceCreated mSession.startPreview:");
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
