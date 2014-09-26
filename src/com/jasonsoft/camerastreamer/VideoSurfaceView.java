package com.jasonsoft.camerastreamer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

public class VideoSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    public static final int APP_BACKGROUND_COLOR = 0xFFF9F9F9;

    // All the stuff we need for playing and showing a video
    private SurfaceHolder mSurfaceHolder = null;
    private int mSurfaceWidth;
    private int mSurfaceHeight;

    private Bitmap mBitmap = null;
    private int mVideoWidth;
    private int mVideoHeight;
    private Matrix mMatrix;
    private Matrix mBitmapMatrix = null;

    // Avoid allocations...
    private RectF mTempSrc = new RectF();
    private RectF mTempDst = new RectF();

    public VideoSurfaceView(Context context) {
        super(context);
        initVideoSurfaceView();
    }

    public VideoSurfaceView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
        initVideoSurfaceView();
    }

    public VideoSurfaceView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initVideoSurfaceView();
    }

    private void initVideoSurfaceView() {
        mMatrix = new Matrix();
        mVideoWidth = 0;
        mVideoHeight = 0;
        getHolder().addCallback(this);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mSurfaceWidth = width;
        mSurfaceHeight = height;

        configureBounds();
        drawBitmap();
    }

    public void surfaceCreated(SurfaceHolder holder) {
        mSurfaceHolder = holder;
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        // after we return from this we can't use the surface any more
        mSurfaceHolder = null;
    }

    private void drawVideoSurfaceBackground(Canvas canvas) {
        Paint paint = new Paint();
        paint.setColor(APP_BACKGROUND_COLOR);
        canvas.drawRect(0.0f, 0.0f, mSurfaceWidth, mSurfaceHeight, paint);
    }


    public void setVideoSurfaceBitmap(Bitmap bm) {
        mBitmap = bm;
        configureBounds();
        drawBitmap();
    }

    private void configureBounds() {
        if (mBitmap == null) {
            return;
        }

        mVideoWidth = mBitmap.getWidth();
        mVideoHeight = mBitmap.getHeight();

        boolean fits = (mVideoWidth < 0 || mSurfaceWidth == mVideoWidth) &&
            (mVideoHeight < 0 || mSurfaceHeight == mVideoHeight);

        if (fits) {
            mBitmapMatrix = null;
        } else {
            // Generate the required transform.
            mTempSrc.set(0, 0, mVideoWidth, mVideoHeight);
            mTempDst.set(0, 0, mSurfaceWidth, mSurfaceHeight);

            mBitmapMatrix = mMatrix;
//            mBitmapMatrix.setRectToRect(mTempSrc, mTempDst, Matrix.ScaleToFit.FILL);
            mBitmapMatrix.setRectToRect(mTempSrc, mTempDst, Matrix.ScaleToFit.CENTER);
        }
    }

    private void drawBitmap() {
        if (mSurfaceHolder != null) {
            Canvas canvas = mSurfaceHolder.lockCanvas();
            drawVideoSurfaceBackground(canvas);

            if (mBitmap != null) {
                canvas.drawBitmap(mBitmap, mBitmapMatrix, null);
            }

            mSurfaceHolder.unlockCanvasAndPost(canvas);
        }
    }
}
