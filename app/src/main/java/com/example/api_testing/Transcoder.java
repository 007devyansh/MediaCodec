package com.example.api_testing;

import android.content.Context;

import android.media.MediaCodecInfo;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.graphics.SurfaceTexture;

import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.nio.ByteBuffer;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class Transcoder {
    private static final String TAG = "Transcoder";
    private static final long TIMEOUT_USEC = 1000; // 10ms timeout

    public void transcodeVideo(Context context, Uri inputUri, String outputFilePath) throws IOException {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        InputSurface inputSurface = null;
        OutputSurface outputSurface = null;
        try {
            // Set up the extractor with the input Uri.
            extractor = new MediaExtractor();
            extractor.setDataSource(context, inputUri, null);
            int videoTrackIndex = -1;
            MediaFormat inputFormat = null;

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrackIndex = i;
                    inputFormat = format;
                    break;
                }
            }
            if (videoTrackIndex < 0 || inputFormat == null) {
                throw new IOException("No video track found in " + inputUri);
            }
            extractor.selectTrack(videoTrackIndex);

            // Create and configure the decoder (assumed H.265/HEVC input).
            String decoderMime = inputFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(decoderMime);
            outputSurface = new OutputSurface();
            decoder.configure(inputFormat, outputSurface.getSurface(), null, 0);
            decoder.start();

            // Set target parameters: H.264, 720p (1280x720), 2 Mbps, ~30 fps.
//            MainActivity mainActivity = new MainActivity();
//            int originalBitrate = Integer.parseInt(mainActivity.getBitrate());

            int targetWidth = 720;
            int targetHeight = 480;
//            int targetBitrate = (int) Math.floor(0.7 * originalBitrate);
            int targetBitrate = 500000;
            int targetFrameRate = 24;
            String encoderMime = "video/avc";
            MediaFormat outputFormat = MediaFormat.createVideoFormat(encoderMime, targetWidth, targetHeight);
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate);
            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, targetFrameRate);
            outputFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
//            outputFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

            // Create and configure the encoder.
            encoder = MediaCodec.createEncoderByType(encoderMime);
            Log.d(TAG, "Encoder Name: " + encoder.getName());
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface encoderInputSurface = encoder.createInputSurface();
            encoder.start();

            // Wrap the encoder's input surface for EGL rendering.
            inputSurface = new InputSurface(encoderInputSurface);
            inputSurface.makeCurrent();

            // Prepare MediaMuxer for output.
            muxer = new MediaMuxer(outputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outputVideoTrack = -1;
            boolean muxerStarted = false;

            BufferInfo encoderBufferInfo = new BufferInfo();
            boolean extractorDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;

            // Main processing loop.
            while (!encoderDone) {
                // Feed compressed data to the decoder.
                if (!extractorDone) {
                    int decoderInputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                    if (decoderInputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(decoderInputBufferIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        long presentationTimeUs = extractor.getSampleTime();
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(decoderInputBufferIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            outputSurface.setEndOfStream();
                            extractorDone = true;
                        } else {
                            decoder.queueInputBuffer(decoderInputBufferIndex, 0, sampleSize,
                                    presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }

                // Drain decoded frames.
                if (!decoderDone) {
                    int decoderStatus = decoder.dequeueOutputBuffer(outputSurface.getBufferInfo(), TIMEOUT_USEC);
                    if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // No frame available.
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Format change, ignore.
                    } else if (decoderStatus >= 0) {
                        BufferInfo info = outputSurface.getBufferInfo();
                        boolean endOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        // Release the decoded frame to the OutputSurface.
                        decoder.releaseOutputBuffer(decoderStatus, true);
                        // Wait until a new frame is available.
                        outputSurface.awaitNewImage();
                        outputSurface.drawImage(); // Use your shader here to scale the image.
                        // Send the frame to the encoder’s input surface.
                        inputSurface.setPresentationTime(info.presentationTimeUs * 1000); // nanoseconds
                        inputSurface.swapBuffers();
                        if (endOfStream) {
                            encoder.signalEndOfInputStream();
                            decoderDone = true;
                        }
                    }
                }

                // Drain encoded data and write to the muxer.
                int encoderStatus = encoder.dequeueOutputBuffer(encoderBufferInfo, TIMEOUT_USEC);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // No encoded output available.
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = encoder.getOutputFormat();
                    outputVideoTrack = muxer.addTrack(newFormat);
                    muxer.start();
                    muxerStarted = true;
                } else if (encoderStatus >= 0) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(encoderStatus);
                    if (encodedData != null && encoderBufferInfo.size != 0) {
                        encodedData.position(encoderBufferInfo.offset);
                        encodedData.limit(encoderBufferInfo.offset + encoderBufferInfo.size);
                        muxer.writeSampleData(outputVideoTrack, encodedData, encoderBufferInfo);
                    }
                    encoder.releaseOutputBuffer(encoderStatus, false);
                    if ((encoderBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true;
                    }
                }
            }
            Log.d(TAG, "Transcoding complete. Output saved to " + outputFilePath);
        } finally {
            // Clean up all resources.
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception e) {
                    Log.w(TAG, "Muxer stop failed", e);
                }
                muxer.release();
            }
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Exception e) {
                    Log.w(TAG, "Encoder stop failed", e);
                }
                encoder.release();
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Exception e) {
                    Log.w(TAG, "Decoder stop failed", e);
                }
                decoder.release();
            }
            if (extractor != null) {
                extractor.release();
            }
            if (inputSurface != null) {
                inputSurface.release();
            }
            if (outputSurface != null) {
                outputSurface.release();
            }
        }
    }

    //////////////////////////////////////////////////////////////////////////////
    // Helper classes for EGL and OpenGL ES handling.
    //////////////////////////////////////////////////////////////////////////////

    /**
     * InputSurface wraps an EGL window surface for the encoder's input.
     * It sets up an EGL context so that you can render frames via OpenGL ES.
     */
    private static class InputSurface {
        private EGLDisplay mEGLDisplay;
        private EGLContext mEGLContext;
        private EGLSurface mEGLSurface;
        private Surface mSurface;

        public InputSurface(Surface surface) {
            mSurface = surface;
            mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
                throw new RuntimeException("Unable to get EGL14 display");
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
                throw new RuntimeException("Unable to initialize EGL14");
            }
            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0)) {
                throw new RuntimeException("Unable to choose EGL config");
            }
            int[] attrib_list = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
            mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0],
                    EGL14.EGL_NO_CONTEXT, attrib_list, 0);
            int[] surfaceAttribs = { EGL14.EGL_NONE };
            mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mSurface, surfaceAttribs, 0);
        }

        public void makeCurrent() {
            if (!EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
                throw new RuntimeException("eglMakeCurrent failed");
            }
        }

        public void swapBuffers() {
            EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface);
        }

        public void setPresentationTime(long nsecs) {
            EGLExt.eglPresentationTimeANDROID(mEGLDisplay, mEGLSurface, nsecs);
        }

        public void release() {
            if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
                EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
                EGL14.eglTerminate(mEGLDisplay);
            }
            if (mSurface != null) {
                mSurface.release();
            }
            mEGLDisplay = EGL14.EGL_NO_DISPLAY;
            mEGLContext = null;
            mEGLSurface = null;
            mSurface = null;
        }
    }


    public class OutputSurface implements SurfaceTexture.OnFrameAvailableListener {
        private static final String TAG = "OutputSurface";

        private SurfaceTexture mSurfaceTexture;
        private Surface mSurface;
        private final Object mFrameSyncObject = new Object();
        private boolean mFrameAvailable;
        private boolean mEndOfStream = false;
        private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

        // Variables for shader drawing:
        private int mProgram = 0;
        private int mPositionHandle;
        private int mTexCoordHandle;
        private FloatBuffer mTriangleVertices;
        private FloatBuffer mTextureVertices;
        private int mTextureID; // The texture id created for SurfaceTexture
        private boolean mInitialized = false;

        // Vertex shader: passes through position and texture coordinate.
        private static final String VERTEX_SHADER_CODE =
                "attribute vec4 aPosition;\n" +
                        "attribute vec2 aTexCoord;\n" +
                        "varying vec2 vTexCoord;\n" +
                        "void main() {\n" +
                        "  gl_Position = aPosition;\n" +
                        "  vTexCoord = aTexCoord;\n" +
                        "}\n";

        // Fragment shader: uses the external texture extension to sample from the SurfaceTexture.
        private static final String FRAGMENT_SHADER_CODE =
                "#extension GL_OES_EGL_image_external : require\n" +
                        "precision mediump float;\n" +
                        "varying vec2 vTexCoord;\n" +
                        "uniform samplerExternalOES sTexture;\n" +
                        "void main() {\n" +
                        "  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
                        "}\n";

        public OutputSurface() {
            // Generate an external texture ID.
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            mTextureID = textures[0];

            // Bind the texture and configure its parameters.
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            // Create the SurfaceTexture using the generated texture id.
            mSurfaceTexture = new SurfaceTexture(mTextureID);
            mSurfaceTexture.setOnFrameAvailableListener(this);
            mSurface = new Surface(mSurfaceTexture);
        }

        public Surface getSurface() {
            return mSurface;
        }

        public MediaCodec.BufferInfo getBufferInfo() {
            return mBufferInfo;
        }
        /**
         * Optionally call this method from your transcoding loop when you detect end-of-stream.
         */
        public void setEndOfStream() {
            synchronized (mFrameSyncObject) {
                mEndOfStream = true;
                mFrameSyncObject.notifyAll();
            }
        }
        /**
         * Wait for a new frame to be available.
         */
//        public void awaitNewImage() {
//            final int TIMEOUT_MS = 2000;  // Increase timeout for debugging
//            synchronized (mFrameSyncObject) {
//                while (!mFrameAvailable) {
//                    try {
//                        mFrameSyncObject.wait(TIMEOUT_MS);
//                        if (!mFrameAvailable) {
//                            Log.e(TAG, "Frame wait timed out after " + TIMEOUT_MS + "ms");
//                            throw new RuntimeException("Frame wait timed out");
//                        }
//                    } catch (InterruptedException ie) {
//                        Thread.currentThread().interrupt();
//                        throw new RuntimeException("Frame wait interrupted", ie);
//                    }
//                }
//                mFrameAvailable = false;
//            }
//            mSurfaceTexture.updateTexImage();
//            Log.d(TAG, "updateTexImage() called successfully.");
//        }

        public void awaitNewImage() {
            final int WAIT_INCREMENT_MS = 100;  // Wait in 100ms increments
            final int MAX_WAIT_MS = 2000;         // Total maximum wait time is 2000ms (2 seconds)
            int waited = 0;

            synchronized (mFrameSyncObject) {
                // Loop until a frame is available or we reach our maximum wait time.
                while (!mFrameAvailable && waited < MAX_WAIT_MS) {
                    try {
                        Log.d(TAG, "Waiting for new frame... waited " + waited + "ms so far");
                        mFrameSyncObject.wait(WAIT_INCREMENT_MS);
                        waited += WAIT_INCREMENT_MS;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Frame wait interrupted", ie);
                    }
                }

                // If no frame is available after waiting, log and throw an exception.
//                if (!mFrameAvailable) {
//                    Log.e(TAG, "Frame wait timed out after " + waited + "ms");
//                    throw new RuntimeException("Frame wait timed out");
//                }
                if (!mFrameAvailable) {
                    if (mEndOfStream) {
                        Log.d(TAG, "End-of-stream detected; no new frame available after " + waited + "ms");
                        return; // Return gracefully since no more frames are expected.
                    }
                    Log.e(TAG, "Frame wait timed out after " + waited + "ms");
                    throw new RuntimeException("Frame wait timed out");
                }

                // Reset the flag for the next frame.
                mFrameAvailable = false;
            }

            // Now update the texture with the latest frame.
            mSurfaceTexture.updateTexImage();
            Log.d(TAG, "updateTexImage() called successfully after waiting " + waited + "ms.");
        }


        /**
         * Implements the shader-based drawing and scaling.
         */
        public void drawImage() {
            // Initialize the shader program and vertex buffers on the first draw.
            if (!mInitialized) {
                initGLComponents();
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // Use the shader program.
            GLES20.glUseProgram(mProgram);

            // Enable vertex attributes.
            GLES20.glEnableVertexAttribArray(mPositionHandle);
            GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mTriangleVertices);

            GLES20.glEnableVertexAttribArray(mTexCoordHandle);
            GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mTextureVertices);

            // Bind the external texture.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);

            // Draw the quad.
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            // Disable vertex arrays.
            GLES20.glDisableVertexAttribArray(mPositionHandle);
            GLES20.glDisableVertexAttribArray(mTexCoordHandle);
        }

        /**
         * Initializes the shader program and vertex data.
         */
        private void initGLComponents() {
            // Compile shaders and link the program.
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE);
            int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE);
            mProgram = GLES20.glCreateProgram();
            GLES20.glAttachShader(mProgram, vertexShader);
            GLES20.glAttachShader(mProgram, fragmentShader);
            GLES20.glLinkProgram(mProgram);

            // Get attribute locations.
            mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
            mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");

            // Define a full-screen quad (using a triangle strip).
            float[] triangleVerticesData = {
                    -1.0f, -1.0f,  // bottom left
                    1.0f, -1.0f,  // bottom right
                    -1.0f,  1.0f,  // top left
                    1.0f,  1.0f   // top right
            };
            float[] textureVerticesData = {
                    0.0f, 1.0f,  // bottom left
                    1.0f, 1.0f,  // bottom right
                    0.0f, 0.0f,  // top left
                    1.0f, 0.0f   // top right
            };

            // Allocate buffers for vertex data.
            mTriangleVertices = ByteBuffer.allocateDirect(triangleVerticesData.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mTriangleVertices.put(triangleVerticesData).position(0);

            mTextureVertices = ByteBuffer.allocateDirect(textureVerticesData.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mTextureVertices.put(textureVerticesData).position(0);

            mInitialized = true;
        }

        /**
         * Helper method to load and compile a shader.
         */
        private int loadShader(int type, String shaderCode) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, shaderCode);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader " + type + ":");
                Log.e(TAG, GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
            return shader;
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            synchronized (mFrameSyncObject) {
                mFrameAvailable = true;
                mFrameSyncObject.notifyAll();
            }
            Log.d(TAG, "onFrameAvailable() called, frame available flag set.");
        }

        public void release() {
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            if (mSurfaceTexture != null) {
                mSurfaceTexture.release();
                mSurfaceTexture = null;
            }
        }
    }
}

