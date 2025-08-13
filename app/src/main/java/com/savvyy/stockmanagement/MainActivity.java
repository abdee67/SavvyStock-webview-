package com.savvyy.stockmanagement;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.facebook.shimmer.ShimmerFrameLayout;

import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int STORAGE_PERMISSION_CODE = 1001;
    private static final int MANAGE_STORAGE_PERMISSION_CODE = 1002;
    private static final int REQUEST_CAMERA_PERMISSION = 123;
    private static final int FILECHOOSER_RESULTCODE = 1;
    private static final String PRINT_RECRUIT_LAYOUT = "recruitPrintLayout";
    private static final String TARGET_URL = "https://savvystock.techequations.com/stock/index.xhtml";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private String lastLoadedUrl = TARGET_URL;
    private boolean isErrorPageShown = false;
    private FloatingActionButton fabRefresh;
    private WebAppInterface webAppInterface;
    private BroadcastReceiver networkReceiver;
    private ShimmerFrameLayout shimmerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Make sure you have this layout file

        webView = findViewById(R.id.webview);
        fabRefresh = findViewById(R.id.fabRefresh);
        shimmerView = findViewById(R.id.shimmerView);

        webAppInterface = new WebAppInterface(this, webView);
        requestCameraPermissionIfNeeded();
        checkAndRequestPermissions();

        setupWebView();
        setupFabRefresh();
        setupNetworkReceiver();

        // Load initial URL
        checkNetworkAndLoad();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();

        // Core settings
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);


        // Touch and rendering settings
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(true);
        webSettings.setSupportZoom(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        webSettings.setGeolocationEnabled(true);

        // Performance settings
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webSettings.setAllowUniversalAccessFromFileURLs(true);
        webSettings.setAllowFileAccessFromFileURLs(true);

        // Critical for PrimeFaces
        webSettings.setUserAgentString("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36");

        // Hardware acceleration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        } else {
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        // Cookie management
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // Touch handling
        webView.setLongClickable(false);
        webView.setOnLongClickListener(v -> true);
        webView.setScrollContainer(true);
        webView.setVerticalScrollBarEnabled(true);
        webView.setHorizontalScrollBarEnabled(true);

        // Custom clients
        webView.setWebChromeClient(new CustomWebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d("WebViewConsole", consoleMessage.message() + " -- line "
                        + consoleMessage.lineNumber() + " of "
                        + consoleMessage.sourceId());
                return true;
            }
        });
        webView.setWebViewClient(new CustomWebViewClient());

        // JavaScript interfaces
        webView.addJavascriptInterface(new BlobDownloader(this), "BlobDownloader");
        injectBlobHook();
        webView.addJavascriptInterface(webAppInterface, "AndroidInterface");
        webView.addJavascriptInterface(webAppInterface, "Android");
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void log(String message) {
                Log.d("WebViewJS", message);
            }
        }, "AndroidLogger");

        // Initial JavaScript for debugging
        webView.evaluateJavascript(
                "console.log = function(message) { AndroidLogger.log(message); };",
                null
        );

        // Other setup
        injectPrintHandler();
        setupDownloadListener();

        // Hardware acceleration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        } else {
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        webView.setOnTouchListener(new View.OnTouchListener() {
            private float startY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getY();
                        break;
                    case MotionEvent.ACTION_UP:
                        float endY = event.getY();
                        // If it's a tap (not scroll)
                        if (Math.abs(endY - startY) < 10) {
                            // Force focus to handle dropdowns
                            v.requestFocusFromTouch();
                        }
                        break;
                }
                return false;
            }
        });
    }

    public class CustomWebView extends WebView {
        public CustomWebView(Context context) {
            super(context);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            requestDisallowInterceptTouchEvent(true);
            return super.onTouchEvent(event);
        }
    }

    private void setupDownloadListener() {
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            Log.d(TAG, "Download requested: " + url);
            if (url.startsWith("blob:")) {

                try {
                    String js = BlobDownloader.getBlobDownloadScript(
                            mimeType != null ? mimeType : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            URLUtil.guessFileName(url, contentDisposition, mimeType)
                    );

                    webView.evaluateJavascript(js, value -> {
                        if (value == null || value.equals("null")) {
                           // Toast.makeText(this, "Failed to prepare download", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception e) {
                    Toast.makeText(this, "Download error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            } else {
                // Handle regular HTTP downloads
                 try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                            .setMimeType(mimeType)
                            .addRequestHeader("User-Agent", userAgent)
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

                    // Only set direct path for pre-Android 10
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        if (ContextCompat.checkSelfPermission(this,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(this,
                                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                    STORAGE_PERMISSION_CODE);
                            return;
                        }

                        request.setDestinationInExternalPublicDir(
                                Environment.DIRECTORY_DOWNLOADS,
                                URLUtil.guessFileName(url, contentDisposition, mimeType)
                        );
                    }

                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(request);
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            });
        }


    private void setupFabRefresh() {
        fabRefresh.setOnClickListener(view -> {
            if (isNetworkAvailable()) {
                ObjectAnimator rotateAnim = ObjectAnimator.ofFloat(fabRefresh, "rotation", 0f, 360f);
                rotateAnim.setDuration(1000);
                rotateAnim.setRepeatCount(ValueAnimator.INFINITE);
                rotateAnim.start();

                String currentUrl = webView.getUrl();
                if (currentUrl == null || currentUrl.isEmpty()) {
                    currentUrl = TARGET_URL;
                }

                webView.reload();

                webView.setWebViewClient(new WebViewClient() {

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        rotateAnim.cancel();
                        fabRefresh.setRotation(0f);
                        webView.setWebViewClient(new CustomWebViewClient());
                    }
                });
            } else {
                Toast.makeText(this, "Oops! No internet connection", Toast.LENGTH_SHORT).show();
            }
        });

        setupFabDragListener();
    }

    private void setupNetworkReceiver() {
        networkReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (isNetworkAvailable()) {
                    if (isErrorPageShown) {
                        loadWebsite();
                    }
                } else {
                    showErrorPage("No internet connection");
                }
            }
        };
    }

    private void setupFabDragListener() {
        final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) fabRefresh.getLayoutParams();
        fabRefresh.setOnTouchListener(new View.OnTouchListener() {
            private float dX, dY;
            private float startX, startY;
            private boolean isDragging = false;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = view.getX() - event.getRawX();
                        dY = view.getY() - event.getRawY();
                        startX = view.getX();
                        startY = view.getY();
                        isDragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float deltaX = Math.abs(event.getRawX() + dX - startX);
                        float deltaY = Math.abs(event.getRawY() + dY - startY);

                        if (deltaX > 10 || deltaY > 10) {
                            isDragging = true;
                            float newX = event.getRawX() + dX;
                            float newY = event.getRawY() + dY;

                            DisplayMetrics displayMetrics = new DisplayMetrics();
                            getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
                            int screenWidth = displayMetrics.widthPixels;
                            int screenHeight = displayMetrics.heightPixels;

                            newX = Math.max(0, Math.min(newX, screenWidth - view.getWidth()));
                            newY = Math.max(0, Math.min(newY, screenHeight - view.getHeight()));

                            view.animate()
                                    .x(newX)
                                    .y(newY)
                                    .setDuration(0)
                                    .start();
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!isDragging) {
                            String currentUrl = webView.getUrl();
                            if (currentUrl == null || currentUrl.isEmpty()) {
                                currentUrl = TARGET_URL;
                            }
                            webView.loadUrl(currentUrl);
                        }
                        return true;

                    default:
                        return false;
                }
            }
        });
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // No need to request MANAGE_EXTERNAL_STORAGE
            // Just proceed with normal operations
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        STORAGE_PERMISSION_CODE);
            }
        }
    }

    private void requestCameraPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            }
        }
    }

    private void injectPrintHandler() {
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                shimmerView.stopShimmer();
                shimmerView.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                // More robust print handler injection
                String overridePrintJS = "(function() {" +
                        "   window._originalPrintSection = window.printSection;" +
                        "   window._originalWindowPrint = window.print;" +
                        "   window.printSection = function(id) {" +
                        "       console.log('[Android] Intercepted printSection with ID:', id);" +
                        "       if (typeof AndroidInterface !== 'undefined') {" +
                        "           AndroidInterface.printPage(id);" +
                        "       }" +
                        "else if (window._originalPrintSection) {" +
                        "           window._originalPrintSection(id);" +
                        "       }" +
                        "   };" +
                        "   window.print = function() {" +
                        "       console.log('[Android] Intercepted window.print');" +
                        "       const printable = document.querySelector('.print-only');" +
                        "       if (printable && typeof AndroidInterface !== 'undefined') {" +
                        "           AndroidInterface.printPage(printable.id);" +
                        "       } else if (window._originalWindowPrint) {" +
                        "           window._originalWindowPrint();" +
                        "       }" +
                        "   };" +
                        "})();";


                webView.evaluateJavascript(overridePrintJS, null);// Delay injection until AJAX queue is clear
                String safeInjectJS =
                        "(function waitUntilReady() {" +
                                "   if (typeof PrimeFaces !== 'undefined' && PrimeFaces.ajax.Queue.isEmpty() && document.readyState === 'complete') {" +
                                "       setTimeout(function() {" +
                                "           try {" +
                                "               // Fix for dropdowns" +
                                "               var selects = document.querySelectorAll('.ui-selectonemenu');" +
                                "               selects.forEach(function(select) {" +
                                "                   select.style.pointerEvents = 'auto';" +
                                "               });" +
                                "           } catch (e) { console.error('Dropdown fix error', e); }" +
                                "       }, 500);" +
                                "   } else {" +
                                "       setTimeout(waitUntilReady, 300);" +
                                "   }" +
                                "})();";

                webView.evaluateJavascript(safeInjectJS, null);
            }
        });
    }

    private void checkNetworkAndLoad() {
        if (isNetworkAvailable()) {
            loadWebsite();
        } else {
            showErrorPage("No internet connection");
        }
    }

    private void loadWebsite() {
        webView.loadUrl(lastLoadedUrl);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Camera permission is required for scanning", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted
            } else {
                Toast.makeText(this, "Storage permission required for downloads",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private abstract class CustomWebChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (newProgress < 100) {
                shimmerView.setVisibility(View.VISIBLE);
                shimmerView.startShimmer();
            } else {
                shimmerView.setVisibility(View.GONE);
            }
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    request.grant(request.getResources());
                }
            });
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
            if (MainActivity.this.filePathCallback != null) {
                MainActivity.this.filePathCallback.onReceiveValue(null);
            }
            MainActivity.this.filePathCallback = filePathCallback;
            Intent intent = fileChooserParams.createIntent();
            try {
                startActivityForResult(intent, FILECHOOSER_RESULTCODE);
            } catch (ActivityNotFoundException e) {
                MainActivity.this.filePathCallback = null;
                Toast.makeText(MainActivity.this, "Cannot open file chooser", Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }

        public abstract boolean onConsoleMessage(ConsoleMessage consoleMessage);
    }

    private class CustomWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            view.loadUrl(request.getUrl().toString());
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                handleError(view, error.getDescription().toString());
            }
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            shimmerView.setVisibility(View.VISIBLE);
            shimmerView.startShimmer();
            webView.setVisibility(View.GONE);
        }


        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            Log.d(TAG, "Page finished loading: " + url);
            isErrorPageShown = false;
            lastLoadedUrl = url;

            // Stagger these fixes to ensure proper execution order
            new Handler().postDelayed(() -> {
                injectDropdownFixes(view);
                injectPrintHandler();
                injectBlobHook();
            }, 800);

            new Handler().postDelayed(MainActivity.this::injectPrintHandler, 1200);

            setupDownloadListener();
        }
    }
    private void injectBlobHook() {
        String js = BlobDownloader.getBlobDownloadScript("", "");
        webView.evaluateJavascript(js, value -> {
            Log.d(TAG, "Blob hook injected.");
        });
    }


    private void injectDropdownFixes(WebView webView) {
        String fixes =
                "(function() {" +
                        "   try {" +
                        "       // 1. Store original PrimeFaces functions" +
                        "       if (typeof PrimeFaces !== 'undefined' && PrimeFaces.widget.SelectOneMenu) {" +
                        "           var originalHide = PrimeFaces.widget.SelectOneMenu.prototype.hide;" +
                        "           var originalShow = PrimeFaces.widget.SelectOneMenu.prototype.show;" +
                        "           " +
                        "           // 2. Override show/hide methods with WebView-specific fixes" +
                        "           PrimeFaces.widget.SelectOneMenu.prototype.show = function() {" +
                        "               this.panel.style.zIndex = '999999';" +
                        "               this.panel.style.position = 'fixed';" +
                        "               originalShow.apply(this, arguments);" +
                        "               this.shouldClose = false;" +
                        "               setTimeout(function() { this.shouldClose = true; }.bind(this), 300);" +
                        "           };" +
                        "           " +
                        "           PrimeFaces.widget.SelectOneMenu.prototype.hide = function() {" +
                        "               if (this.shouldClose !== false) {" +
                        "                   originalHide.apply(this, arguments);" +
                        "               }" +
                        "           };" +
                        "           " +
                        "           // 3. Modify event handling for WebView" +
                        "           PrimeFaces.widget.SelectOneMenu.prototype.bindPanelEvents = function() {" +
                        "               var $this = this;" +
                        "               this.panel.off('mouseup.selectonemenu').on('mouseup.selectonemenu', function(e) {" +
                        "                   e.preventDefault();" +
                        "                   e.stopPropagation();" +
                        "                   $this.shouldClose = false;" +
                        "                   setTimeout(function() { $this.shouldClose = true; }, 500);" +
                        "                   $this.handlePanelClick(e);" +
                        "               });" +
                        "           };" +
                        "       }" +
                        "       " +
                        "})();";

        // Execute after a delay to ensure PrimeFaces is loaded
        new Handler().postDelayed(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                webView.evaluateJavascript(fixes, null);
            } else {
                webView.loadUrl("javascript:" + fixes);
            }
        }, 1000);
    }

    private void handleError(WebView view, String description) {
        runOnUiThread(() -> {
            if (!isErrorPageShown) {
                isErrorPageShown = true;
                Log.e(TAG, "WebView Error: " + description);
                showErrorPage(description);
            }
        });
    }

    private void showErrorPage(String description) {
        webView.loadUrl("file:///android_asset/error.html");
        Toast.makeText(this, description, Toast.LENGTH_LONG).show();
        shimmerView.setVisibility(View.GONE);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && intent != null) {
                    Uri data = intent.getData();
                    if (data != null) {
                        results = new Uri[]{data};
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        } else if (requestCode == MANAGE_STORAGE_PERMISSION_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "Storage permission required for downloads", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(networkReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(networkReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver was not registered
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        if (webView.canGoBack() && !isErrorPageShown) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void showPrintError(String error) {
        runOnUiThread(() -> {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        });
    }

    public class WebAppInterface {
        private Context context;
        private WebView webView;
        private static final String TAG = "WebAppInterface";

        public WebAppInterface(Context context, WebView webView) {
            this.context = context;
            this.webView = webView;
        }

        @JavascriptInterface
        public void retryLastPage() {
            runOnUiThread(() -> {
                webView.loadUrl(lastLoadedUrl);
            });
        }
        @JavascriptInterface
        public void printPage(final String elementId) {
            // Proper CSS with selector
            String printCss = "<style>" +
                    "body, html { margin: 0; padding: 0; height: 100%; width: 100%; }" +
                    ".print-only {" +
                    "  display: flex !important;" +
                    "  flex-direction: column;" +
                    "  justify-content: center;" +
                    "  align-items: center;" +
                    "  height: 100vh;" +
                    "  width: 100vw;" +
                    "  box-sizing: border-box;" +
                    "  padding: 40px;" +
                    "  font-family: sans-serif;" +
                    "}" +
                    ".logo-image1 {" +
                    "  width: 120px;" +
                    "  margin-bottom: 20px;" +
                    "}" +
                    ".text-center {" +
                    "  text-align: center;" +
                    "}" +
                    ".text-2xl { font-size: 24px; font-weight: bold; margin: 12px 0; }" +
                    ".text-lg { font-size: 18px; margin: 6px 0; }" +
                    ".text-xl { font-size: 22px; margin: 8px 0; }" +
                    ".text-sm { font-size: 14px; }" +
                    ".qr-container {" +
                    "  border: 1px solid #ccc;" +
                    "  padding: 16px;" +
                    "  margin-top: 20px;" +
                    "}" +
                    "@media print {" +
                    "  body * { visibility: hidden; }" +
                    "  .print-only, .print-only * { visibility: visible; }" +
                    "  .print-only {" +
                    "    position: absolute;" +
                    "    top: 0; left: 0; right: 0; bottom: 0;" +
                    "    display: flex !important;" +
                    "    flex-direction: column;" +
                    "    justify-content: center;" +
                    "    align-items: center;" +
                    "  }" +
                    "}" +
                    "</style>";

            runOnUiThread(() -> webView.evaluateJavascript(
                    "(function() {" +
                            "var el = document.getElementById('" + elementId + "');" +
                            "if (!el) return null;" +
                            "var html = '<html><head>' + `" + printCss + "` + '</head><body>' + el.outerHTML + '</body></html>';" +
                            "return btoa(unescape(encodeURIComponent(html)));" +
                            "})()",
                    base64Html -> {
                        if (base64Html == null || base64Html.equals("null")) {
                            showPrintError("Element not found");
                            return;
                        }
                        byte[] data = Base64.decode(base64Html, Base64.DEFAULT);
                        String decodedHtml;
                        try {
                            decodedHtml = new String(data, StandardCharsets.UTF_8);
                        } catch (Exception e) {
                            showPrintError("Failed to decode HTML: " + e.getMessage());
                            return;
                        }

                        printHtmlContent(decodedHtml);
                    }
            ));
        }

        private void printHtmlContent(String htmlContent) {
            // Create a temporary WebView for printing
            final WebView printWebView = new WebView(context);

            // Enable JavaScript and other necessary settings
            WebSettings settings = printWebView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);

            printWebView.setWebViewClient(new WebViewClient() {
                private boolean pageLoaded = false;

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (!pageLoaded) {
                        pageLoaded = true;

                        // Add a small delay to ensure all resources are loaded
                        new Handler().postDelayed(() -> {
                            createPrintJob(printWebView);

                            // Clean up after printing
                            new Handler().postDelayed(() -> {
                                if (printWebView.getParent() != null) {
                                    ((ViewGroup) printWebView.getParent()).removeView(printWebView);
                                }
                            }, 5000);
                        }, 500);
                    }
                }
            });

            // Add to layout temporarily (required for printing)
            ViewGroup rootView = (ViewGroup) ((Activity) context).getWindow().getDecorView().getRootView();
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1, 1);
            params.gravity = Gravity.START;
            rootView.addView(printWebView, params);

            // Load the HTML content with proper base URL
            printWebView.loadDataWithBaseURL("https://savvystock.techequations.com",
                    htmlContent,
                    "text/html",
                    "UTF-8",
                    null);
        }

        private void createPrintJob(WebView webViewToPrint) {
            try {
                PrintManager printManager = (PrintManager) context.getSystemService(Context.PRINT_SERVICE);
                if (printManager == null) {
                    showPrintError("Print service not available");
                    return;
                }

                String jobName = context.getString(R.string.app_name) + " Document";
                PrintDocumentAdapter printAdapter;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    printAdapter = webViewToPrint.createPrintDocumentAdapter(jobName);
                } else {
                    printAdapter = webViewToPrint.createPrintDocumentAdapter();
                }

                PrintAttributes.Builder builder = new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS);

                printManager.print(jobName, printAdapter, builder.build());
                showPrintSuccess("Print job started");

            } catch (Exception e) {
                Log.e(TAG, "Print job creation failed", e);
                showPrintError("Print failed: " + e.getMessage());
            }
        }

        private void showPrintSuccess(String message) {
            runOnUiThread(() -> {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            });
        }

        private void showPrintError(String message) {
            runOnUiThread(() -> {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            });
        }
    }
}