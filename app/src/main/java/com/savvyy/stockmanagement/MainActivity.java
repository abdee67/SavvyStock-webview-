package com.savvyy.stockmanagement;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.facebook.shimmer.ShimmerFrameLayout;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int STORAGE_PERMISSION_CODE = 1001;
    private static final int MANAGE_STORAGE_PERMISSION_CODE = 1002;
    private static final int REQUEST_CAMERA_PERMISSION = 123;
    private static final int FILECHOOSER_RESULTCODE = 1;
    private static final String TARGET_URL = "https://savvystock.techequations.com/stock/signin.xhtml";

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
        setupDoubleTapZoom();

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
                Log.d("WebView", consoleMessage.message() + " at " +
                        consoleMessage.sourceId() + ":" + consoleMessage.lineNumber());
                return true;
            }
        });
        webView.setWebViewClient(new CustomWebViewClient());

        // JavaScript interfaces
        webView.addJavascriptInterface(new BlobDownloader(this), "Android");
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
        // Add this to your setupWebView() method
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
                String js = BlobDownloader.getBlobDownloadScript(url, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                webView.evaluateJavascript(js, null);
                webAppInterface.notifyFileDownload(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Chart_of_Account.xlsx"), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            } else {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(i);
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

                webView.loadUrl(currentUrl);

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
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, MANAGE_STORAGE_PERMISSION_CODE);
            }
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

                String js = "(function() {" +
                        "const observer = new MutationObserver(function(mutations) {" +
                        "   const buttons = document.querySelectorAll('[onclick*=\"print\"]');" +
                        "   if (buttons.length > 0) {" +
                        "       observer.disconnect();" +
                        "       buttons.forEach(function(btn) {" +
                        "           btn.onclick = function(e) {" +
                        "               AndroidInterface.printPage();" +
                        "               e.preventDefault();" +
                        "               return false;" +
                        "           };" +
                        "       });" +
                        "   }" +
                        "});" +
                        "observer.observe(document.body, { childList: true, subtree: true });" +
                        "})();";
                webView.evaluateJavascript(js, null);

                // Delay injection until AJAX queue is clear
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

    private void setupDoubleTapZoom() {
        webView.setOnTouchListener(new View.OnTouchListener() {
            private final GestureDetector gestureDetector = new GestureDetector(getApplicationContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    float scale = webView.getScale();
                    if (scale < 2.0f) {
                        webView.zoomIn();
                    } else {
                        webView.zoomOut();
                    }
                    return true;
                }
            });

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureDetector.onTouchEvent(event);
                return false;
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
            isErrorPageShown = false;
            lastLoadedUrl = url;

            // Stagger these fixes to ensure proper execution order
            new Handler().postDelayed(() -> {
                injectDropdownFixes(view);
            }, 800);

            new Handler().postDelayed(MainActivity.this::injectPrintHandler, 1200);
        }
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
    public void onBackPressed() {
        if (webView.canGoBack() && !isErrorPageShown) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
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
        public void logError(String error) {
            Log.e("WebViewJS", error);
            runOnUiThread(() -> {
                if (error.contains("network error") || error.contains("status: 0")) {
                    Toast.makeText(context,
                            "Connection error. Please ensure you have stable internet and try again.",
                            Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void printPage() {
            ((Activity) context).runOnUiThread(() -> {
                ShimmerFrameLayout shimmer = new ShimmerFrameLayout(context, null, android.R.attr.dropDownListViewStyle);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                );
                ((Activity) context).addContentView(shimmer, params);

                String js = "(function() {" +
                        "   try {" +
                        "       var selectors = [" +
                        "           'form table', " +
                        "           '.ui-datatable-tablewrapper', " +
                        "           '.ui-panel-content table', " +
                        "           'table.ui-datatable-data', " +
                        "           'table.ui-widget-content'" +
                        "       ]; " +
                        "       var content = null; " +
                        "       for (var i = 0; i < selectors.length && !content; i++) { " +
                        "           content = document.querySelector(selectors[i]); " +
                        "       } " +
                        "       if (!content) { " +
                        "           var tables = document.getElementsByTagName('table'); " +
                        "           for (var i = 0; i < tables.length; i++) { " +
                        "               if (tables[i].offsetWidth > 0 && tables[i].offsetHeight > 0) { " +
                        "                   content = tables[i]; " +
                        "                   break; " +
                        "               } " +
                        "           } " +
                        "       } " +
                        "       if (!content) return null; " +

                        "       var cloned = content.cloneNode(true); " +
                        "       var unwantedSelectors = [" +
                        "           'button', 'a', 'input', 'select', 'textarea', " +
                        "           '.ui-button', '.ui-commandlink', '.ui-paginator', " +
                        "           '.ui-datatable-footer', '.ui-panel-footer', " +
                        "           '.ui-toolbar', '.ui-panel-titlebar', " +
                        "           '.ui-button-text-icon-left', '.ui-widget-header', " +
                        "           '.ui-panel-title'" +
                        "       ]; " +
                        "       unwantedSelectors.forEach(function(selector) { " +
                        "           var elements = cloned.querySelectorAll(selector); " +
                        "           elements.forEach(function(el) { el.remove(); }); " +
                        "       }); " +

                        "      var html = '<!DOCTYPE html><html><head><style>' +\n" +
                        "    '@page { size: auto; margin: 10mm; }' +\n" +
                        "    'body { margin: 0; padding-top: 600px; font-family: sans-serif; }' +\n" +
                        "    'table { width: 100%; border-collapse: collapse; page-break-inside: auto; }' +\n" +
                        "    'tr { page-break-inside: avoid; page-break-after: auto; }' +\n" +
                        "    'th, td { padding: 8px; border: 1px solid #ddd; }' +\n" +
                        "    'th { background-color: #f2f2f2; text-align: left; }' +\n" +
                        "    '</style></head><body>' + cloned.outerHTML + '</body></html>';\n " +
                        "       return html; " +
                        "   } catch(e) { " +
                        "       console.error('Print error:', e); " +
                        "       return null; " +
                        "   } " +
                        "})();";

                webView.evaluateJavascript(js, result -> {
                    ((ViewGroup) shimmer.getParent()).removeView(shimmer);

                    if (result != null && !result.equals("null")) {
                        try {
                            String html = URLDecoder.decode(result.substring(1, result.length() - 1), "UTF-8")
                                    .replace("\\u003C", "<")
                                    .replace("\\u003E", ">")
                                    .replace("\\\"", "\"")
                                    .replace("\\\\", "\\")
                                    .replace("\\n", "\n")
                                    .replace("\\t", "\t")
                                    .replace("\\r", "\r");

                            WebView printWebView = new WebView(context);
                            printWebView.setWebViewClient(new WebViewClient() {
                                @Override
                                public void onPageFinished(WebView view, String url) {
                                    new Handler().postDelayed(() -> {
                                        createPrintJob(view);
                                        new Handler().postDelayed(() -> view.destroy(), 1000);
                                    }, 300);
                                }
                            });

                            printWebView.loadDataWithBaseURL(webView.getUrl(), html, "text/html", "UTF-8", null);
                        } catch (Exception e) {
                            Log.e("Print", "Error processing HTML", e);
                            printFallback();
                        }
                    } else {
                        printFallback();
                    }
                });
            });
        }

        private void printFallback() {
            String fallbackJs = "(function() {" +
                    "   var style = document.createElement('style');" +
                    "   style.innerHTML = 'body > * { visibility: hidden; } " +
                    "   table, .ui-datatable, .ui-panel { visibility: visible !important; position: absolute !important; top: 0 !important; left: 0 !important; width: 100% !important; } " +
                    "   @page { size: auto; margin: 5mm; }';" +
                    "   document.head.appendChild(style);" +
                    "   return true;" +
                    "})();";

            webView.evaluateJavascript(fallbackJs, result -> {
                new Handler().postDelayed(() -> createPrintJob(webView), 300);
            });
        }

        private void createPrintJob(WebView webView) {
            try {
                PrintManager printManager = (PrintManager) context.getSystemService(Context.PRINT_SERVICE);
                String jobName = context.getString(R.string.app_name) + " Document";

                PrintDocumentAdapter printAdapter;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    printAdapter = webView.createPrintDocumentAdapter(jobName);
                } else {
                    printAdapter = webView.createPrintDocumentAdapter();
                }

                PrintAttributes.Builder builder = new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS);

                printManager.print(jobName, printAdapter, builder.build());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(context,
                        "Failed to print: " + e.getMessage(), Toast.LENGTH_LONG).show());
                Log.e("Print", "Print job error", e);
            }
        }

        public void showDownloadNotification(File file) {
            String CHANNEL_ID = "download_channel";
            NotificationManager notificationManager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Downloads",
                        NotificationManager.IMPORTANCE_DEFAULT
                );
                notificationManager.createNotificationChannel(channel);
            }

            Uri fileUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".provider",
                    file
            );

            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(fileUri, getMimeType(file.getName()));
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context, 0, viewIntent, PendingIntent.FLAG_IMMUTABLE
            );

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Download complete")
                    .setContentText(file.getName())
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true);

            notificationManager.notify((int) System.currentTimeMillis(), builder.build());
        }

        private String getMimeType(String fileName) {
            String extension = MimeTypeMap.getFileExtensionFromUrl(fileName);
            return extension != null ?
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase()) :
                    "*/*";
        }

        private void notifyFileDownload(File file, String mimeType) {
            runOnUiThread(() -> {
                showDownloadNotification(file);
                Toast.makeText(context, "Download complete: " + file.getName(),
                        Toast.LENGTH_LONG).show();
                MediaScannerConnection.scanFile(
                        context,
                        new String[]{file.getAbsolutePath()},
                        new String[]{mimeType},
                        (path, uri) -> Log.d(TAG, "File scanned: " + path)
                );
            });
        }
    }
}