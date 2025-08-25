package com.savvyy.stockmanagement;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Provides blob download support in WebView by fetching the blob,
 * converting to Base64, and saving via Android filesystem.
 * Injects JavaScript safely after PrimeFaces AJAX updates.
 */
public class BlobDownloader {
    private final Context context;
    private static final String TAG = "BlobDownloader";

    public BlobDownloader(Context context) {
        this.context = context;
    }

    @JavascriptInterface
    public void saveBase64File(String base64Data, String mimeType) {
        Log.d(TAG, "saveBase64File() called; mimeType=" + mimeType);
        logStorageState();

        try {
            // Validate base64 data
            if (base64Data == null || !base64Data.contains(",")) {
                throw new IllegalArgumentException("Invalid base64 data");
            }

            String extension = getExtensionFromMimeType(mimeType);
            String fileName = "Download_" + System.currentTimeMillis() + extension;
            String base64Content = base64Data.split(",")[1];
            byte[] fileData = Base64.decode(base64Content, Base64.DEFAULT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(fileName, mimeType, fileData);
            } else {
                saveLegacy(fileName, mimeType, fileData);
            }

        } catch (Exception e) {
            Log.e(TAG, "Download failed", e);
            showToast("Download failed: " + e.getMessage());
        }
    }
    private void logStorageState() {
        Log.d(TAG, "Storage state:");
        Log.d(TAG, "External storage state: " + Environment.getExternalStorageState());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentResolver resolver = context.getContentResolver();
                Uri uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
                    Log.d(TAG, "MediaStore Downloads items count: " +
                            (cursor != null ? cursor.getCount() : 0));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking MediaStore", e);
            }
        }
    }

    @SuppressLint("Range")
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void saveViaMediaStore(String fileName, String mimeType, byte[] fileData) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("Failed to create MediaStore entry");
            }

            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("Failed to open output stream");
                }
                out.write(fileData);
            }

            // Mark as completed
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, values, null, null);

            // Get the actual file path for notification
            String filePath = null;
            try (Cursor cursor = resolver.query(uri,
                    new String[]{MediaStore.Downloads.DATA},
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    filePath = cursor.getString(cursor.getColumnIndex(MediaStore.Downloads.DATA));
                }
            }

            showDownloadCompleteNotification(uri, fileName, filePath);
            showToast("File saved to Downloads/" + fileName);

        } catch (Exception e) {
            // Clean up if failed
            if (uri != null) {
                resolver.delete(uri, null, null);
            }
            throw e;
        }
    }

    @SuppressWarnings("deprecation")
    private void saveLegacy(String fileName, String mimeType, byte[] fileData) throws IOException {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);

        // Fallback to app-specific storage if we can't access Downloads
        if (!downloadsDir.exists() || !downloadsDir.canWrite()) {
            downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (downloadsDir == null) {
                downloadsDir = context.getFilesDir();
            }
        }

        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw new IOException("Failed to create download directory");
        }

        File file = new File(downloadsDir, fileName);

        // Ensure no duplicate files
        int counter = 1;
        while (file.exists()) {
            String newName = fileName.replaceFirst(
                    "(\\.\\w+)?$",
                    " (" + (counter++) + ")$1"
            );
            file = new File(downloadsDir, newName);
        }

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(fileData);
        }

        // Notify media scanner
        MediaScannerConnection.scanFile(
                context,
                new String[]{file.getAbsolutePath()},
                new String[]{mimeType},
                (path, uri) -> {
                    if (uri == null) {
                        Log.e(TAG, "Media scan failed for: " + path);
                    } else {
                        showDownloadCompleteNotification(uri, fileName, path);
                    }
                }
        );

        showToast("File saved to " + file.getParentFile().getName() + "/" + file.getName());
    }

    private void showDownloadCompleteNotification(Uri uri, String fileName, String filePath) {
        String channelId = "download_channel";
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        // Create notification channel for Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "Downloads",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            manager.createNotificationChannel(channel);
        }

        // Create content URI using FileProvider for Android 7+
        Uri contentUri;
        if (filePath != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                contentUri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName() + ".provider",
                        new File(filePath)
                );
            } catch (Exception e) {
                contentUri = uri;
            }
        } else {
            contentUri = uri;
        }

        // Create view intent
        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setDataAndType(contentUri, getMimeType(fileName));
        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download complete")
                .setContentText(fileName)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        manager.notify((int) System.currentTimeMillis(), builder.build());
    }

    private String getMimeType(String fileName) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(fileName);
        return extension != null ?
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase()) :
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    private void showToast(String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }


    /**
     * Returns a JavaScript snippet (no "javascript:" prefix) that safely queues blob-download logic
     * after PrimeFaces AJAX updates (if available), or runs immediately.
     * MainActivity should invoke it via:
     *   webView.evaluateJavascript("javascript:" + getBlobDownloadScript(...), null);
     */
    public static String getBlobDownloadScript(String mimeType, String fileName) {
        return "(function() {" +
                "  if (window._blobHookInjected) return;" +
                "  window._blobHookInjected = true;" +
                "  const originalCreateObjectURL = URL.createObjectURL;" +
                "  URL.createObjectURL = function(blob) {" +
                "    const reader = new FileReader();" +
                "    reader.onloadend = function() {" +
                "      const base64Data = reader.result;" +
                "      let type = blob.type;" +
                "      if (!type || type === 'application/octet-stream') {" +
                "        type = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';" +
                "      }" +
                "      const name = '" + fileName + "' || 'export.xlsx';" +
                "      if (window.BlobDownloader && window.BlobDownloader.saveBase64File) {" +
                "        window.BlobDownloader.saveBase64File(base64Data, type);" +
                "      }" +
                "    };" +
                "    reader.readAsDataURL(blob);" +
                "    return originalCreateObjectURL.call(URL, blob);" +
                "  };" +
                "})();";
    }


    public static String getExtensionFromMimeType(String mimeType) {
        switch (mimeType) {
            case "application/msword": return ".doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": return ".docx";
            case "application/vnd.ms-excel": return ".xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": return ".xlsx";
            case "application/pdf": return ".pdf";
            case "text/plain": return ".txt";
            case "image/jpeg": return ".jpg";
            case "image/png": return ".png";
            case "application/octet-stream": return ".xlsx"; // 👈 force excel for unknown blobs
            default:
                Log.w(TAG, "Unknown MIME type: " + mimeType);
                String fallback = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                return (fallback != null ? "." + fallback : ".xlsx");
        }
    }

}
