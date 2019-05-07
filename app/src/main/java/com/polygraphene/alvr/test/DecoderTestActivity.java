package com.polygraphene.alvr.test;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;

import com.polygraphene.alvr.DecoderThread;
import com.polygraphene.alvr.NAL;
import com.polygraphene.alvr.NALParser;
import com.polygraphene.alvr.R;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

public class DecoderTestActivity extends AppCompatActivity {

    private static final String TAG = "DecoderTestActivity";
    private long mFrameIndex = 0;
    private long mLoop = 0;
    private DecoderThread decoderThread;
    private TextView textView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_decoder_test);

        textView = findViewById(R.id.text);

        final SurfaceView surfaceView = findViewById(R.id.surface);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(final SurfaceHolder holder) {
                NALParser dummyNALParser;
                try {
                    dummyNALParser = getNALParser();
                }catch (Exception e){
                    textView.setText("codectest.h265 Not found");
                    return;
                }

                decoderThread = new DecoderThread(dummyNALParser, holder.getSurface(), DecoderTestActivity.this);
                decoderThread.start();
                surfaceView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        long ret = decoderThread.render(50);

                        if(ret >= 0) {
                            mFrameIndex++;
                            textView.setText("Current:" + mFrameIndex + " " + mLoop);
                        }
                        Log.v("DecoderTestActivity", "Frame rendered: " + ret + "  i:" + mFrameIndex + " loop:" + mLoop);
                        if(ret >= 4) {
                            mLoop++;
                            mFrameIndex = 0;

                            Log.v("DecoderTestActivity", "Next loop:"+ mLoop + ". stop and wait");
                            decoderThread.stopAndWait();
                            Log.v("DecoderTestActivity", "Next loop:"+ mLoop + ". stop ok");

                            NALParser dummyNALParser = null;
                            try {
                                dummyNALParser = getNALParser();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                            decoderThread = new DecoderThread(dummyNALParser, holder.getSurface(), DecoderTestActivity.this);
                            decoderThread.start();
                            Log.v("DecoderTestActivity", "Next loop:"+ mLoop + ". started");
                        }
                        surfaceView.postDelayed(this, 1);
                    }
                }, 1);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {

            }
        });
    }

    NALParser getNALParser() throws Exception {
        DummyNALParser dummyNALParser = new DummyNALParser();

        FileInputStream fis = null;
        try {
            fis = new FileInputStream(DecoderTestActivity.this.getExternalFilesDir("test") + "/codectest.h265");

            long frameIndex = 1;
            NAL nal = new NAL();

            byte[] buffer = new byte[87];
            fis.read(buffer, 0, buffer.length);
            nal.frameIndex = frameIndex;
            nal.buf = buffer;
            nal.length = nal.buf.length;

            String s = "";
            for(int i = 0; i < 8; i++) {
                s += String.format("%02X ", buffer[i]);
            }
            Log.v(TAG, "NAL Buffer: " + s);

            dummyNALParser.mNalList.add(nal);

            int[] frameSizes = new int[]{1393, 193438, 56245, 36483, 32754, 37696, 38229, 44998, 30080, 32862,
                    31067, 35536, 37509, 37098, 34429, 36402, 27596, 28715};

            for (int size : frameSizes) {
                buffer = new byte[size];
                fis.read(buffer, 0, buffer.length);

                nal = new NAL();
                nal.frameIndex = frameIndex++;
                nal.buf = buffer;
                nal.length = nal.buf.length;

                dummyNALParser.mNalList.add(nal);
            }
        } finally {
            try {
                fis.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return dummyNALParser;
    }
}
