package com.savvyy.stockmanagement;
import static android.content.ContentValues.TAG;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

public class PrintInterface {
    private Activity activity;
    private WebView webView;
    private static final String TAG = "PrintInterface";

    public PrintInterface(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public void printCashFlowTable() {
        Log.d(TAG, "printCashFlowTable called from JavaScript");

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Looking for cash flow table...");

                // First, try to find the specific table by ID or class
                String findTableJS =
                        "(function() {" +
                                "   console.log('[AndroidPrint] Looking for cash flow table');" +
                                "   " +
                                "   // Try specific IDs first" +
                                "   var specificIds = ['pnlPrintCustomerList', 'contactList', 'contactList1', 'cashFlowTable', 'printTable'];" +
                                "   for (var i = 0; i < specificIds.length; i++) {" +
                                "       var table = document.getElementById(specificIds[i]);" +
                                "       if (table) {" +
                                "           console.log('[AndroidPrint] Found table by ID:', specificIds[i]);" +
                                "           return {html: table.outerHTML, type: 'id', id: specificIds[i]};" +
                                "       }" +
                                "   }" +
                                "   " +
                                "   // Try specific classes" +
                                "   var specificClasses = ['.ui-datatable', '.ui-table', '.print-table', '.cash-flow-table'];" +
                                "   for (var j = 0; j < specificClasses.length; j++) {" +
                                "       var table = document.querySelector(specificClasses[j]);" +
                                "       if (table) {" +
                                "           console.log('[AndroidPrint] Found table by class:', specificClasses[j]);" +
                                "           return {html: table.outerHTML, type: 'class', class: specificClasses[j]};" +
                                "       }" +
                                "   }" +
                                "   " +
                                "   // Fallback: find any table with cash flow content" +
                                "   var allTables = document.querySelectorAll('table');" +
                                "   console.log('[AndroidPrint] Found ' + allTables.length + ' tables');" +
                                "   " +
                                "   for (var k = 0; k < allTables.length; k++) {" +
                                "       var table = allTables[k];" +
                                "       var tableText = table.textContent || table.innerText;" +
                                "       if (tableText.includes('Cash Flow') || tableText.includes('Transaction')) {" +
                                "           console.log('[AndroidPrint] Found cash flow table by content');" +
                                "           return {html: table.outerHTML, type: 'content'};" +
                                "       }" +
                                "   }" +
                                "   " +
                                "   // Return the first table if nothing else found" +
                                "   if (allTables.length > 0) {" +
                                "       console.log('[AndroidPrint] Using first table');" +
                                "       return {html: allTables[0].outerHTML, type: 'first'};" +
                                "   }" +
                                "   " +
                                "   console.log('[AndroidPrint] No table found');" +
                                "   return null;" +
                                "})()";

                webView.evaluateJavascript(findTableJS, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (value != null && !value.equals("null")) {
                            try {
                                // Parse the JSON response
                                String jsonStr = value.replaceAll("^\"|\"$", "");
                                JSONObject jsonObject = new JSONObject(jsonStr);
                                String htmlContent = jsonObject.getString("html");
                                String foundType = jsonObject.getString("type");

                                Log.d(TAG, "Table found via: " + foundType);
                                printHtmlContent(htmlContent);

                            } catch (Exception e) {
                                Log.e(TAG, "Error parsing table data: " + e.getMessage());
                                Toast.makeText(activity, "Error preparing print content", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            Toast.makeText(activity, "No printable content found", Toast.LENGTH_SHORT).show();
                            Log.d(TAG, "No table found for printing");
                        }
                    }
                });
            }
        });
    }

    private void printHtmlContent(String htmlContent) {
        try {
            Log.d(TAG, "Preparing HTML content for printing...");

            String fullHtml = "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Cash Flow Report</title>" +
                    "<style>" +
                    "body { margin: 15mm; font-family: Arial, sans-serif; font-size: 12pt; } " +
                    "table { width: 100%; border-collapse: collapse; margin-bottom: 20px; } " +
                    "th, td { border: 1px solid #000; padding: 8px; text-align: left; } " +
                    "th { background-color: #f0f0f0; font-weight: bold; } " +
                    ".header { text-align: center; margin-bottom: 20px; } " +
                    ".footer { text-align: center; margin-top: 20px; font-size: 10pt; color: #666; } " +
                    "@media print { " +
                    "  body { margin: 15mm; } " +
                    "  .no-print { display: none !important; } " +
                    "}" +
                    "</style></head><body>" +
                    "<div class='header'>" +
                    "<h2>Cash Flow Transaction Report</h2>" +
                    "<p>Generated on: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date()) + "</p>" +
                    "</div>" +
                    htmlContent +
                    "<div class='footer'>" +
                    "<p>Generated by Savvy Stock Management</p>" +
                    "</div>" +
                    "</body></html>";

            final WebView printWebView = new WebView(activity);
            WebSettings settings = printWebView.getSettings();
            settings.setJavaScriptEnabled(true);

            printWebView.setWebViewClient(new WebViewClient() {
                private boolean printed = false;

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (!printed) {
                        printed = true;
                        Log.d(TAG, "Print WebView page finished, starting print job...");

                        PrintManager printManager = (PrintManager) activity.getSystemService(Context.PRINT_SERVICE);
                        if (printManager != null) {
                            PrintDocumentAdapter printAdapter;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                printAdapter = printWebView.createPrintDocumentAdapter("Cash Flow Report");
                            } else {
                                printAdapter = printWebView.createPrintDocumentAdapter();
                            }

                            PrintAttributes attributes = new PrintAttributes.Builder()
                                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                                    .build();

                            String jobName = "Cash Flow Report - " + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
                            printManager.print(jobName, printAdapter, attributes);

                            Log.d(TAG, "Print job started: " + jobName);
                            Toast.makeText(activity, "Printing started...", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(activity, "Print service not available", Toast.LENGTH_SHORT).show();
                        }

                        // Clean up after a delay
                        new Handler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (printWebView.getParent() != null) {
                                    ((ViewGroup) printWebView.getParent()).removeView(printWebView);
                                    Log.d(TAG, "Print WebView cleaned up");
                                }
                            }
                        }, 3000);
                    }
                }
            });

            // Add to layout temporarily
            ViewGroup root = (ViewGroup) activity.getWindow().getDecorView().getRootView();
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1, 1);
            root.addView(printWebView, params);

            // Load the HTML content
            printWebView.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null);

        } catch (Exception e) {
            Log.e(TAG, "Print error: " + e.getMessage(), e);
            Toast.makeText(activity, "Print failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // Add a test method for debugging
    @JavascriptInterface
    public void testConnection() {
        Log.d(TAG, "AndroidPrint interface is working!");
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, "AndroidPrint is connected!", Toast.LENGTH_SHORT).show();
            }
        });
    }
}