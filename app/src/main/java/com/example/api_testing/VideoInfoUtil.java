package com.example.api_testing;


import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

public class VideoInfoUtil {
    private static final String TAG = "VideoInfoUtil";

    public static void printVideoInfo(String filePath) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(filePath);
            int trackCount = extractor.getTrackCount();
            Log.d(TAG, "Total Tracks: " + trackCount);

            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                Log.d(TAG, "Track " + i + " MIME type: " + mime);

                // If this is a video track, print out video properties.
                if (mime.startsWith("video/")) {
                    if (format.containsKey(MediaFormat.KEY_WIDTH) && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                        int width = format.getInteger(MediaFormat.KEY_WIDTH);
                        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
                        Log.d(TAG, "Resolution: " + width + "x" + height);
                    }
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        int bitRate = format.getInteger(MediaFormat.KEY_BIT_RATE);
                        Log.d(TAG, "Bitrate: " + bitRate + " bps");
                    }
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        int frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                        Log.d(TAG, "Frame Rate: " + frameRate + " fps");
                    }
                }
                // If it's an audio track, you can also retrieve keys like CHANNEL_COUNT and SAMPLE_RATE.
                if (mime.startsWith("audio/")) {
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        Log.d(TAG, "Audio Sample Rate: " + sampleRate);
                    }
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        Log.d(TAG, "Audio Channels: " + channelCount);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading video info", e);
        } finally {
            extractor.release();
        }
    }
}
