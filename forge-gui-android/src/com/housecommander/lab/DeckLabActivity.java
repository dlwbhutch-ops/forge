package com.housecommander.lab;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Read-only offline Deck Lab companion. Gameplay/database state is never modified. */
public final class DeckLabActivity extends Activity {
    private static final int PICK_DOCUMENT = 8571;
    private static final int SAVE_DOCUMENT = 8572;
    private ValueCallback<Uri[]> pendingFiles;
    private byte[] pendingExport;
    private WebView browser;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        browser = new WebView(this);
        browser.getSettings().setJavaScriptEnabled(true);
        browser.getSettings().setDomStorageEnabled(true);
        browser.getSettings().setAllowFileAccess(true);
        browser.getSettings().setAllowFileAccessFromFileURLs(false);
        browser.getSettings().setAllowUniversalAccessFromFileURLs(false);
        browser.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Never navigate the privileged Javascript bridge to a remote page.
                String location = request.getUrl().toString();
                return !location.startsWith("file:///android_asset/house-decklab/");
            }
        });
        browser.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try {
                    startActivityForResult(intent, PICK_DOCUMENT);
                    return true;
                } catch (Exception error) {
                    pendingFiles = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(DeckLabActivity.this, "File picker unavailable", Toast.LENGTH_LONG).show();
                    return true;
                }
            }
        });
        browser.addJavascriptInterface(new ExportHost(), "HOUSE_DECKLAB_HOST");
        setContentView(browser);
        browser.loadUrl("file:///android_asset/house-decklab/index.html");
    }

    private final class ExportHost {
        @JavascriptInterface
        public void exportDeck(String suggestedName, String content) {
            runOnUiThread(() -> {
                if (pendingExport != null) {
                    Toast.makeText(DeckLabActivity.this, "Finish the previous export first", Toast.LENGTH_SHORT).show();
                    return;
                }
                pendingExport = content.getBytes(StandardCharsets.UTF_8);
                String safeName = suggestedName.replaceAll("[^A-Za-z0-9_.-]", "_");
                if (!safeName.endsWith(".dck")) safeName += ".dck";
                Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                save.addCategory(Intent.CATEGORY_OPENABLE);
                save.setType("text/plain");
                save.putExtra(Intent.EXTRA_TITLE, safeName);
                try {
                    startActivityForResult(save, SAVE_DOCUMENT);
                } catch (Exception error) {
                    pendingExport = null;
                    Toast.makeText(DeckLabActivity.this, "Export dialog unavailable", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_DOCUMENT) {
            ValueCallback<Uri[]> callback = pendingFiles;
            pendingFiles = null;
            if (callback != null) {
                callback.onReceiveValue(result == RESULT_OK && data != null && data.getData() != null
                        ? new Uri[]{data.getData()} : null);
            }
        } else if (request == SAVE_DOCUMENT) {
            byte[] export = pendingExport;
            pendingExport = null;
            if (result != RESULT_OK || data == null || data.getData() == null || export == null) return;
            try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                if (output == null) throw new java.io.IOException("Destination unavailable");
                output.write(export);
                Toast.makeText(this, "Saved proposal; installed deck unchanged", Toast.LENGTH_LONG).show();
            } catch (Exception error) {
                Toast.makeText(this, "Could not save Deck Lab proposal: " + error.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (pendingFiles != null) pendingFiles.onReceiveValue(null);
        if (browser != null) {
            browser.removeJavascriptInterface("HOUSE_DECKLAB_HOST");
            browser.destroy();
        }
        super.onDestroy();
    }
}
