package com.jasonsoft.camerastreamer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
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
import java.nio.ByteBuffer;

import net.majorkernelpanic.streaming.Session;
import net.majorkernelpanic.streaming.SessionBuilder;
import net.majorkernelpanic.streaming.audio.AudioQuality;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.video.VideoQuality;

public class CameraStreamerActivity extends Activity implements Session.Callback, SurfaceHolder.Callback
            , View.OnClickListener{
    private static final String TAG = CameraStreamerActivity.class.getSimpleName();

    public static final String KEY_DESTINATION_IP_ADDRESS = "destination_ip_address";
    private static final String LOCAL_IP_ADDRESS = "127.0.0.1";

    private static final int BUFFER_SIZE = 8192; // 8K
    private static final int FRAME_BUFFER_SIZE = 500 * 1024; // 500K
    private static final int VIDEO_UDP_PORT = 5006;
    private static final int AUDIO_UDP_PORT = 5004;

    private static final int VIDEO_WIDTH = 640;
    private static final int VIDEO_HEIGHT = 480;
    private static final int VIDEO_FRAME_RATE = 10;
    private static final int VIDEO_BIT_RATE = 500000;

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
    private VideoReceiverAsyncTask mVideoReceiverAsyncTask;
    private AudioReceiverAsyncTask mAudioReceiverAsyncTask;
    private long mCurrentFrameTs = 0;
	private Session mSession;
    private EditText mDestinationEditText;
    private boolean mIsViewing;
    private SharedPreferences mPrefs;
    private AlertDialog mDialog;
    private Context mContext;

    static {
        System.loadLibrary("avutil-52");
        System.loadLibrary("avcodec-55");
        System.loadLibrary("avformat-55");
        System.loadLibrary("avformat-55");
        System.loadLibrary("swscale-2");
        System.loadLibrary("swresample-0");

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
                .setAudioEncoder(SessionBuilder.AUDIO_AAC)
                .setAudioQuality(new AudioQuality(16000, 32000))
                .setVideoEncoder(SessionBuilder.VIDEO_H264)
                .setVideoQuality(new VideoQuality(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FRAME_RATE, VIDEO_BIT_RATE))
                .setVideoPacketizer(new H264VideoPacketizer())
                .build();
        mLocalSurfaceView.getHolder().addCallback(this);
        mLocalSurfaceView.setZOrderOnTop(true);
        mRecordButton.setOnClickListener(this);
        mViewButton.setOnClickListener(this);
        mIsViewing = false;

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mDestinationEditText.setText(mPrefs.getString(KEY_DESTINATION_IP_ADDRESS, LOCAL_IP_ADDRESS));
        mContext = this;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_switch_camera:
                mSession.switchCamera();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
        case R.id.record_button:
            String destinationIpAddress = mDestinationEditText.getText().toString();
            if (Utils.isInvalidIpAddress(destinationIpAddress)) {
                // alrert dialog not start

                mRecordButton.setChecked(false);
                showInvalidIpAddressDialog();
                return;
            }

            // Restore destination ip address
            Editor editor = mPrefs.edit();
            editor.putString(KEY_DESTINATION_IP_ADDRESS, destinationIpAddress);
            editor.commit();

            // Starts/stops streaming
            mSession.setDestination(destinationIpAddress);
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
                mVideoReceiverAsyncTask = new VideoReceiverAsyncTask();
                mVideoReceiverAsyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, VIDEO_UDP_PORT);
                mAudioReceiverAsyncTask = new AudioReceiverAsyncTask();
                mAudioReceiverAsyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, AUDIO_UDP_PORT);

                mIsViewing = true;
                mViewButton.setChecked(true);
            } else {
                stopMediaReceiverAsyncTask();
                mIsViewing = false;
                mViewButton.setChecked(false);
            }
            break;
        default:
            break;
        }
    }

    private void stopMediaReceiverAsyncTask() {
        if (mVideoReceiverAsyncTask != null) {
            mVideoReceiverAsyncTask.cancel(true);
        }
        if (mAudioReceiverAsyncTask != null) {
            mAudioReceiverAsyncTask.cancel(true);
        }
    }

    private void showInvalidIpAddressDialog() {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_LIGHT);
        builder.setTitle(R.string.error_dialog_title);
        builder.setMessage(R.string.invalid_ip_address).setPositiveButton(R.string.ok_button, null);
        mDialog = builder.create();
        mDialog.show();
    }

    abstract class UDPReceiverAsyncTask extends AsyncTask<Integer, Integer, Void> {
        protected int receivedPacketCounts = 0;
        protected byte mFrameBuffer[] = new byte[FRAME_BUFFER_SIZE];
        protected int mFrameBufferPosition = 0;

        protected int parseRTP(byte[] data, int len) {
            if (len < RTP_HEADER_SIZE) {
                return -1;
            }

            Utils.printDebugLogs(TAG, "parseRTP start");
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

            Utils.printDebugLogs(TAG, "marker:" + marker);
            Utils.printDebugLogs(TAG, "seq:" + seq);

            publishProgress(seq);

            int payloadLen = len - RTP_HEADER_SIZE;
            int offset = RTP_HEADER_SIZE;
            if (mCurrentFrameTs != ts && mCurrentFrameTs != 0 && mFrameBufferPosition != 0) {
                mFrameBufferPosition = 0;
            }

            System.arraycopy(data, offset, mFrameBuffer, mFrameBufferPosition, payloadLen);
            mFrameBufferPosition += payloadLen;

            Utils.printDebugLogs(TAG, "parseRTP end");

            mCurrentFrameTs = ts;
            if (marker > 0) {
                int value = mFrameBufferPosition;
                mFrameBufferPosition = 0;
                mCurrentFrameTs = 0;
                Utils.printDebugLogs(TAG, "Got frame!! ready to decode");
                return value;
            } else {
                // frame not ready
                return -1;
            }
        }

        protected void printHexList(byte[] data, int len) {
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < len; i++) {
                int value = (int) (data[i] & 0xff);
                sb.append(Integer.toString(value, 16) + " ");
            }

            Utils.printDebugLogs(TAG, "Hext List:" + sb);
        }

    }

    class VideoReceiverAsyncTask extends UDPReceiverAsyncTask {

        public VideoReceiverAsyncTask () {
        }

        @Override
        protected Void doInBackground(Integer... params) {
            nativeStreamingMediaInit();
            mRemoteBitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888);
            nativeStreamingPrepareBitmap(mRemoteBitmap, VIDEO_WIDTH, VIDEO_HEIGHT);

            byte buffer[] = new byte[BUFFER_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket(params[0]);
            } catch (SocketException e) {
                Log.d(TAG, "SocketException:" + e);
            }

            if (socket == null) {
                return null;
            }

            while (true) {
                if (isCancelled()) {
                    Log.d(TAG, "Abort Video UDP receiving loop");
                    socket.close();
                    break;
                }

                try {
                    socket.receive(packet);
                    receivedPacketCounts++;
                    int frameLen = parseRTP(packet.getData(), packet.getLength());
                    if (frameLen > 0) {
                        if (isValidH264BitStreaming()) {
                            printHexList(mFrameBuffer, Math.min(frameLen, 20));
                            nativeStreamingDecodeFrame(mFrameBuffer, frameLen);
                            mRemoteSurfaceView.setVideoSurfaceBitmap(mRemoteBitmap);
                        }
                    }
                } catch (IOException e) {
                    Log.d(TAG, "IOException:" + e);
                }
            }

            return null;
        }

        private boolean isValidH264BitStreaming() {
            return Utils.isSpsNalu(mFrameBuffer) || Utils.isIdrNalu(mFrameBuffer) || Utils.isSliceNalu(mFrameBuffer);
        }

        @Override
        protected void onProgressUpdate(Integer... params) {
            mReceivedStats.setText(receivedPacketCounts + "/" + params[0]);
        }

        @Override
        protected void onPostExecute(Void result) {
        }
    }

    class AudioReceiverAsyncTask extends UDPReceiverAsyncTask {
        AudioManager mAudioManager;
        int mOriginalAudioStreamType;

        public AudioReceiverAsyncTask () {
        }

        @Override
        protected Void doInBackground(Integer... params) {
            nativeAudioStreamingInit();

            int sampleRateInHz = 16000;
            int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
            int minPlayBufSize = AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT) * 2;

            AudioTrack audioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL, sampleRateInHz,
                    channelConfig, AudioFormat.ENCODING_PCM_16BIT, minPlayBufSize,
                    AudioTrack.MODE_STREAM);
            setAudioMode(true);

            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(minPlayBufSize);
            Log.d(TAG, "minPlayBufSize:" + minPlayBufSize);
            nativePrepareAudioByteBuffer(byteBuffer);

            audioTrack.play();

            byte buffer[] = new byte[BUFFER_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket(params[0]);
            } catch (SocketException e) {
            }

            if (socket == null) {
                return null;
            }

            while (true) {
                if (isCancelled()) {
                    Log.d(TAG, "Abort Audio UDP receiving loop");
                    socket.close();
                    nativeAudioFinish();
                    releaseAudioTrack(audioTrack);
                    setAudioMode(false);
                    break;
                }

                try {
                    socket.receive(packet);
                    receivedPacketCounts++;
                    int frameLen = parseRTP(packet.getData(), packet.getLength());
                    if (frameLen > 0) {
                        if (isValidAACBitStreaming()) {
                            printHexList(mFrameBuffer, Math.min(frameLen, 20));
                            int encodedFrameLen = frameLen - 4;
                            byte encodedFrame[] = new byte[encodedFrameLen];
                            System.arraycopy(mFrameBuffer, 4, encodedFrame, 0, encodedFrameLen);
                            printHexList(encodedFrame, Math.min(encodedFrameLen, 20));
                            int decodedLen = nativeDecodeAudioStreamingFrame(encodedFrame, encodedFrameLen);
                            if (decodedLen > 0) {
                                byte[] output = new byte[decodedLen];
                                byteBuffer.get(output);
                                byteBuffer.clear();
                                int written = audioTrack.write(output, 0, decodedLen);
                            }
                        }
                    }
                } catch (IOException e) {
                    Log.d(TAG, "IOException:" + e);
                }
            }

            return null;
        }

        private void setAudioMode(boolean startCall) {
            if (mAudioManager == null && mContext != null) {
                mAudioManager = (AudioManager)
                    mContext.getSystemService(Context.AUDIO_SERVICE);
            }

            if (mAudioManager == null) {
                Log.d(TAG, "Could not set audio mode - no audio manager");
                return;
            }

            if (startCall) {
                mAudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                if (!mAudioManager.isSpeakerphoneOn()) {
                    mAudioManager.setSpeakerphoneOn(true);
                }
                mOriginalAudioStreamType = getVolumeControlStream();
                setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
            } else {
                mAudioManager.setMode(AudioManager.MODE_NORMAL);
                if (mAudioManager.isSpeakerphoneOn()) {
                    mAudioManager.setSpeakerphoneOn(false);
                }
                setVolumeControlStream(mOriginalAudioStreamType);
            }
        }

        private void releaseAudioTrack(AudioTrack audioTrack) {
            if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    audioTrack.stop();
                } catch (IllegalStateException e) {
                }

                audioTrack.flush();
                audioTrack.release();
                audioTrack = null;
            }
        }

        private boolean isValidAACBitStreaming() {
            return true;
        }

        @Override
        protected void onProgressUpdate(Integer... params) {
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
//        Log.d(TAG, mSession.getSessionDescription());
        mSession.start();
    }

	@Override
	public void onDestroy() {
		super.onDestroy();
		mSession.release();
        stopMediaReceiverAsyncTask();
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

    // For audio streaming
    public native int nativeAudioStreamingInit();
    public native int nativePrepareAudioByteBuffer(ByteBuffer byteBuffer);
    public native int nativeDecodeAudioStreamingFrame(byte[] inbuf, int len);
    public native int nativeAudioFinish();
}
