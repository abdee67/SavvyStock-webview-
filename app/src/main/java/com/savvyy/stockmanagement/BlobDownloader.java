package com.savvyy.stockmanagement;

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

        try {
            // Validate base64 data
            if (base64Data == null || !base64Data.contains(",")) {
                throw new IllegalArgumentException("Invalid base64 data");
            }

            String extension = getExtensionFromMimeType(mimeType);
            String fileName = "Download_" + System.currentTimeMillis() + extension;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveFileUsingMediaStore(fileName, mimeType, base64Data);
            } else {
                saveFileLegacy(fileName, mimeType, base64Data);
            }

        } catch (Exception e) {
            Log.e(TAG, "Download failed", e);
            showToast("Download failed: " + e.getMessage());
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void saveFileUsingMediaStore(String fileName, String mimeType, String base64Data) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        contentValues.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        contentValues.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        contentValues.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
        if (uri == null) {
            throw new IOException("Failed to create MediaStore entry");
        }

        try (OutputStream out = resolver.openOutputStream(uri)) {
            byte[] fileData = Base64.decode(base64Data.split(",")[1], Base64.DEFAULT);
            out.write(fileData);

            // Mark download as complete
            contentValues.clear();
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, contentValues, null, null);

            showToast("File saved to Downloads/" + fileName);
            showDownloadCompleteNotification(uri, fileName);
        } catch (Exception e) {
            // Delete the failed entry
            resolver.delete(uri, null, null);
            throw e;
        }
    }

    private void saveFileLegacy(String fileName, String mimeType, String base64Data) throws IOException {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw new IOException("Failed to create Downloads directory");
        }

        File file = new File(downloadsDir, fileName);
        byte[] fileData = Base64.decode(base64Data.split(",")[1], Base64.DEFAULT);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(fileData);
        }

        // Notify media scanner
        MediaScannerConnection.scanFile(
                context,
                new String[]{file.getAbsolutePath()},
                new String[]{mimeType},
                (path, uri) -> Log.d(TAG, "Media scan completed for: " + path)
        );

        showToast("File saved to Downloads/" + fileName);
        showDownloadCompleteNotification(Uri.fromFile(file), fileName);
    }

    private void showDownloadCompleteNotification(Uri fileUri, String fileName) {
        String CHANNEL_ID = "download_channel";
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        // Create notification channel for Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Downloads",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            notificationManager.createNotificationChannel(channel);
        }

        // Create intent to view the file
        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setDataAndType(fileUri, getMimeType(fileName));
        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download complete")
                .setContentText(fileName)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        notificationManager.notify((int) System.currentTimeMillis(), builder.build());
    }

    private String getMimeType(String fileName) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(fileName);
        return extension != null ?
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase()) :
                "application/octet-stream";
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
                "if (window._blobHandlerInjected) return;" +
                "window._blobHandlerInjected = true;" +

                "function downloadBlob(blob, fileName) {" +
                "  return new Promise((resolve, reject) => {" +
                "    const reader = new FileReader();" +
                "    reader.onload = () => {" +
                "      try {" +
                "        const base64 = reader.result;" +
                "        if (window.BlobDownloader && window.BlobDownloader.saveBlob) {" +
                "          window.BlobDownloader.saveBlob(base64, blob.type, fileName);" +
                "          resolve();" +
                "        } else {" +
                "          reject('BlobDownloader not available');" +
                "        }" +
                "      } catch (e) {" +
                "        reject(e);" +
                "      }" +
                "    };" +
                "    reader.onerror = () => reject(reader.error);" +
                "    reader.readAsDataURL(blob);" +
                "  });" +
                "}" +

                "const originalCreateObjectURL = URL.createObjectURL;" +
                "URL.createObjectURL = function(blob) {" +
                "  if (blob instanceof Blob) {" +
                "    const url = originalCreateObjectURL.call(this, blob);" +
                "    const fileName = 'file_' + Date.now() + getExtension(blob.type);" +
                "    downloadBlob(blob, fileName).catch(e => console.error('Blob download failed:', e));" +
                "    return url;" +
                "  }" +
                "  return originalCreateObjectURL.apply(this, arguments);" +
                "};" +

                "function getExtension(mimeType) {" +
                "  const extensions = {" +
                "    'application/pdf': '.pdf'," +
                "    'image/jpeg': '.jpg'," +
                "    'image/png': '.png'," +
                "    'application/vnd.ms-excel': '.xls'," +
                "    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': '.xlsx'," +
                "    'application/msword': '.doc'," +
                "    'application/vnd.openxmlformats-officedocument.wordprocessingml.document': '.docx'" +
                "  };" +
                "  return extensions[mimeType] || '.bin';" +
                "}" +
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
            default:
                Log.w("BlobDownloader", "Unknown MIME type: " + mimeType);
                String fallback = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                return (fallback != null ? "." + fallback : ".bin");
        }
    }
}
