package com.example.api_testing;


import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final int REQUEST_CODE_PICK_VIDEO = 1001;
    private static final String TAG = "MainActivity";

//    private String bitrate;
    private Uri selectedVideoUri;
    private Button pickButton, detailsButton, transcodeButton;


    public Uri getSelectedUri() {
        return selectedVideoUri;
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pickButton = findViewById(R.id.pickButton);
        detailsButton = findViewById(R.id.detailsButton);
        transcodeButton = findViewById(R.id.transcodeButton);

        // Initially disable details and transcode buttons until a video is selected.
        detailsButton.setEnabled(false);
        transcodeButton.setEnabled(false);

        // Check storage permissions.
        if (!hasStoragePermission()) {
            requestStoragePermission();
        }

        pickButton.setOnClickListener(v -> {
            // Launch the system file picker to select a video.
            Toast.makeText(this, "touched", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/*");
            startActivityForResult(intent, REQUEST_CODE_PICK_VIDEO);
        });

        detailsButton.setOnClickListener(v -> {
            Toast.makeText(this, "details", Toast.LENGTH_SHORT).show();
            if (selectedVideoUri != null) {
                try {
                    showVideoDetails(selectedVideoUri);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                Toast.makeText(MainActivity.this, "No video selected", Toast.LENGTH_SHORT).show();
            }
        });

        transcodeButton.setOnClickListener(v -> {
            // Save output file to your app's external files directory.
            File outputDir = getExternalFilesDir(null);
            if (outputDir == null) {
                Toast.makeText(MainActivity.this, "Unable to access output directory", Toast.LENGTH_LONG).show();
                return;
            }
            String outputFilePath = new File(outputDir, "transcoded_video.mp4").getAbsolutePath();
            // Start transcoding using AsyncTask.
            Toast.makeText(this, "Starting transcoding..." + outputFilePath, Toast.LENGTH_SHORT).show();
            Log.d(TAG, "onCreate: outFile " + outputFilePath);
            new TranscodeTask().execute(selectedVideoUri, outputFilePath);
        });
    }

    private boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (!(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(this, "Storage permission required", Toast.LENGTH_LONG).show();
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_PICK_VIDEO && resultCode == RESULT_OK && data != null) {
            selectedVideoUri = data.getData();
            if (selectedVideoUri != null) {
                detailsButton.setEnabled(true);
                transcodeButton.setEnabled(true);
                Toast.makeText(this, "Video selected", Toast.LENGTH_SHORT).show();
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // AsyncTask to run transcoding on a background thread.
    private class TranscodeTask extends AsyncTask<Object, Void, Boolean> {
        @Override
        protected Boolean doInBackground(Object... params) {
            Uri inputUri = (Uri) params[0];
            String outputFilePath = (String) params[1];
            try {
//                Transcoder.transcodeVideo(MainActivity.this, inputUri, outputFilePath);
                Transcoder coder = new Transcoder();
                coder.transcodeVideo(MainActivity.this, inputUri, outputFilePath);
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Transcoding failed", e);
                return false;
            }
        }
        @Override
        protected void onPostExecute(Boolean success) {
            String message = success ? "Transcoding completed!" : "Transcoding failed!";
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
        }
    }


    private String getVideoCodecInfo(Uri videoUri) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(this, videoUri, null);
            int trackCount = extractor.getTrackCount();
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    // This mime value should indicate the codec used (e.g., "video/hevc" or "video/avc")
                    return mime;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            extractor.release();
        }
        return "Unknown";
    }

//    public void setBitrate(String bitrate) {
//        this.bitrate = bitrate;
//    }
//
//    public String getBitrate() {
//        return bitrate;
//    }
    private void showVideoDetails(Uri videoUri) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, videoUri);

            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String bitRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
//            setBitrate(bitRate);
            String fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            // Retrieve container MIME type (likely "video/mp4")
            String containerMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);

            // Get the actual codec info using MediaExtractor.
            String codec = getVideoCodecInfo(videoUri);

            String details = String.format(Locale.getDefault(),
                    "Duration: %sms\nResolution: %sx%s\nBitrate: %s bps\nContainer MIME: %s\nCodec: %s\nFPS: %s",
                    duration, height, width, bitRate, containerMime, codec, fps);

            new AlertDialog.Builder(this)
                    .setTitle("Video Details")
                    .setMessage(details)
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "Error retrieving metadata", Toast.LENGTH_LONG).show();
            e.printStackTrace();
        } finally {
            retriever.release();
        }
    }




    /**
     * Retrieves and displays metadata from the selected video using MediaMetadataRetriever.
     */
//    private void showVideoDetails(Uri videoUri) throws IOException {
//        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
//        try {
//            retriever.setDataSource(this, videoUri);
//            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
//            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
//            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
//            String bitRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
//            String mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
//
//            String details = String.format(Locale.getDefault(),
//                    "Duration: %sms\nResolution: %sx%s\nBitrate: %s bps\nMIME: %s",
//                    duration, width, height, bitRate, mime);
//
//            new AlertDialog.Builder(this)
//                    .setTitle("Video Details")
//                    .setMessage(details)
//                    .setPositiveButton("OK", null)
//                    .show();
//        } catch (Exception e) {
//            Toast.makeText(this, "Error retrieving metadata", Toast.LENGTH_LONG).show();
//            e.printStackTrace();
//        } finally {
//            retriever.release();
//        }
//    }
}
