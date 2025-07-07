package com.savvyy.stockmanagement;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Provides blob download support in WebView by fetching the blob,
 * converting to Base64, and saving via Android filesystem.
 * Injects JavaScript safely after PrimeFaces AJAX updates.
 */
public class BlobDownloader {
    private final Context context;

    public BlobDownloader(Context context) {
        this.context = context;
    }

    @JavascriptInterface
    public void saveBase64File(String base64Data, String mimeType) {
        Log.d("BlobDownloader", "saveBase64File() called; mimeType=" + mimeType);
        try {
            String extension = getExtensionFromMimeType(mimeType);
            String fileName = "Download_" + System.currentTimeMillis() + extension;

            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs();
            }

            File file = new File(downloadsDir, fileName);
            byte[] fileData = Base64.decode(base64Data.split(",")[1], Base64.DEFAULT);

            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(fileData);
            }

            context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)));
            Toast.makeText(context, "File saved: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
            Log.d("BlobDownloader", "File saved: " + file.getAbsolutePath());

        } catch (Exception e) {
            Log.e("BlobDownloader", "Download failed", e);
            Toast.makeText(context, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Returns a JavaScript snippet (no "javascript:" prefix) that safely queues blob-download logic
     * after PrimeFaces AJAX updates (if available), or runs immediately.
     * MainActivity should invoke it via:
     *   webView.evaluateJavascript("javascript:" + getBlobDownloadScript(...), null);
     */
    public static String getBlobDownloadScript(String blobUrl, String mimeType) {
        return "(function(){"
                + "  function downloadBlob(){"
                + "    var xhr=new XMLHttpRequest();"
                + "    xhr.open('GET','" + blobUrl + "',true);"
                + "    xhr.responseType='blob';"
                + "    xhr.onload=function(){"
                + "      if(xhr.status===200){"
                + "        var blob=xhr.response;"
                + "        var reader=new FileReader();"
                + "        reader.onloadend=function(){"
                + "          if(window.Android && Android.saveBase64File){"
                + "            Android.saveBase64File(reader.result,'" + mimeType + "');"
                + "          }"
                + "        };"
                + "        reader.readAsDataURL(blob);"
                + "      }"
                + "    };"
                + "    xhr.onerror=function(){ console.error('Blob XHR error'); };"
                + "    xhr.send();"
                + "  }"
                + "  if(window.PrimeFaces && PrimeFaces.ajax && PrimeFaces.ajax.Queue){"
                + "    PrimeFaces.ajax.Queue.addOnComplete(downloadBlob);"
                + "  } else {"
                + "    downloadBlob();"
                + "  }"
                + "})();";
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
