/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import app.morphe.extension.shared.Logger;

/**
 * Cookie capture screen for YouTube Music.
 *
 * <p>The activity runs in one of two modes:</p>
 * <ul>
 *   <li>{@link #MODE_WEBVIEW} opens Google's sign-in page in a WebView that exists only for
 *       this purpose. Cookies for {@code music.youtube.com} are polled for; once they contain
 *       {@code SAPISID} or {@code __Secure-3PAPISID}, the cookies are validated against
 *       {@code /account/account_menu} and a <b>success dialog</b> asks the user to confirm.</li>
 *   <li>{@link #MODE_PASTE} shows a text field where the user can paste a {@code cookies.txt}
 *       export. The paste is parsed, injected into {@link CookieManager}, validated, and the
 *       same success dialog appears.</li>
 * </ul>
 *
 * <p>The screen never closes on its own. Only when the user taps <b>OK</b> on the success
 * dialog does the activity return {@link #RESULT_OK}, so the caller can safely start the
 * download afterwards.</p>
 */
public final class CookieLoginActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    public static final int MODE_WEBVIEW = 0;
    public static final int MODE_PASTE = 1;

    private static final String MUSIC_URL = "https://music.youtube.com";
    private static final String GOOGLE_LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&continue="
            + "https%3A%2F%2Fmusic.youtube.com";

    private static final long POLL_INTERVAL_MS = 1500;

    private WebView webView;
    private EditText pasteField;
    private ProgressBar spinner;
    private TextView statusView;
    private Button switchModeButton;

    private Handler pollHandler;
    private Runnable pollRunnable;
    private boolean validated;
    private int mode = MODE_WEBVIEW;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_WEBVIEW);

        CookieBridge.ensureCookieManager();
        buildLayout();
        showMode(mode);
    }

    // ------------------------------------------------------------------ layout

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusView.setTextColor(Color.WHITE);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        statusView.setPadding(dp(16), dp(12), dp(16), dp(12));
        statusView.setBackgroundColor(Color.rgb(0x20, 0x20, 0x20));
        statusView.setText("Sign in to YouTube Music to continue");
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        spinner.setIndeterminate(true);
        spinner.setVisibility(View.GONE);
        root.addView(spinner, new LinearLayout.LayoutParams(-1, dp(4)));

        switchModeButton = new Button(this);
        switchModeButton.setText("Paste cookies instead");
        switchModeButton.setOnClickListener(v -> {
            showMode(mode == MODE_WEBVIEW ? MODE_PASTE : MODE_WEBVIEW);
        });
        LinearLayout.LayoutParams switchLp =
                new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        switchLp.setMargins(dp(12), dp(8), dp(12), dp(8));
        root.addView(switchModeButton, switchLp);

        setContentView(root);
    }

    private void showMode(int newMode) {
        mode = newMode;
        stopPolling();

        LinearLayout root = (LinearLayout) statusView.getParent();
        // Remove everything after the switch button, then rebuild
        int childCount = root.getChildCount();
        for (int i = childCount - 1; i >= 3; i--) root.removeViewAt(i);

        if (mode == MODE_WEBVIEW) {
            switchModeButton.setText("Paste cookies instead");
            buildWebViewMode(root);
            updateStatus("Sign in with your Google account");
        } else {
            switchModeButton.setText("Use sign-in page instead");
            buildPasteMode(root);
            updateStatus("Paste your cookies.txt export");
        }
    }

    private void buildWebViewMode(LinearLayout root) {
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        // Chrome-like UA is required, otherwise Google blocks WebView sign-in.
        settings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.contains("music.youtube.com") && hasSapisidCookie()) {
                    onCookiesDetected();
                }
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1f));
        webView.loadUrl(GOOGLE_LOGIN_URL);
        startPolling();
    }

    private void buildPasteMode(LinearLayout root) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView instructions = new TextView(this);
        instructions.setText(
                "Export your cookies from any browser (Chrome, Firefox, Kiwi) using the "
                + "\"Get cookies.txt LOCALLY\" extension while signed in to music.youtube.com. "
                + "Paste the file content, or just the Cookie header from DevTools, below.");
        instructions.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        instructions.setTextColor(Color.DKGRAY);
        box.addView(instructions);

        pasteField = new EditText(this);
        pasteField.setHint("Paste cookies.txt content here");
        pasteField.setGravity(Gravity.TOP | Gravity.START);
        pasteField.setMinLines(10);
        pasteField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pasteField.setBackgroundColor(Color.rgb(0xF2, 0xF2, 0xF2));
        pasteField.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams fieldLp =
                new LinearLayout.LayoutParams(-1, dp(220));
        fieldLp.topMargin = dp(12);
        box.addView(pasteField, fieldLp);

        Button validate = new Button(this);
        validate.setText("Validate & Save");
        validate.setOnClickListener(v -> validatePaste());
        LinearLayout.LayoutParams btnLp =
                new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(12);
        box.addView(validate, btnLp);

        scroll.addView(box);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    // ------------------------------------------------------------------ webview polling

    private void startPolling() {
        if (pollHandler == null) pollHandler = new Handler(Looper.getMainLooper());
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (hasSapisidCookie()) {
                    String url = webView != null ? webView.getUrl() : null;
                    if (url != null && url.contains("music.youtube.com")) {
                        onCookiesDetected();
                        return;
                    }
                }
                if (pollHandler != null) pollHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        };
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private void stopPolling() {
        if (pollHandler != null && pollRunnable != null) {
            pollHandler.removeCallbacks(pollRunnable);
        }
    }

    private boolean hasSapisidCookie() {
        try {
            String cookies = CookieManager.getInstance().getCookie(MUSIC_URL);
            return cookies != null
                    && (cookies.contains("SAPISID=") || cookies.contains("__Secure-3PAPISID="));
        } catch (Exception ex) {
            return false;
        }
    }

    private void onCookiesDetected() {
        if (validated) return;
        validated = true;
        stopPolling();
        spinner.setVisibility(View.VISIBLE);
        updateStatus("✓ Cookies found, validating...");

        new Thread(() -> {
            boolean ok = CookieBridge.validateCookies();
            runOnUiThread(() -> {
                spinner.setVisibility(View.GONE);
                if (ok) {
                    CookieManager.getInstance().flush();
                    updateStatus("✓ Cookies validated");
                    showSuccessDialog();
                } else {
                    validated = false;
                    updateStatus("Cookies rejected, please sign in again");
                    startPolling();
                }
            });
        }, "cookie-validation").start();
    }

    // ------------------------------------------------------------------ paste mode

    private void validatePaste() {
        String raw = pasteField.getText().toString();
        if (raw.trim().isEmpty()) {
            Toast.makeText(this, "Paste the cookies first", Toast.LENGTH_SHORT).show();
            return;
        }
        spinner.setVisibility(View.VISIBLE);
        updateStatus("Validating...");

        new Thread(() -> {
            boolean injected = CookieBridge.injectCookies(raw);
            boolean ok = injected && CookieBridge.validateCookies();
            runOnUiThread(() -> {
                spinner.setVisibility(View.GONE);
                if (ok) {
                    updateStatus("✓ Cookies validated");
                    showSuccessDialog();
                } else {
                    updateStatus("Cookies rejected");
                    new AlertDialog.Builder(this)
                            .setTitle("Cookies rejected")
                            .setMessage("The paste did not contain valid YouTube Music cookies. "
                                      + "Make sure you are signed in to music.youtube.com in the "
                                      + "browser you exported from.")
                            .setPositiveButton("Try again", null)
                            .show();
                }
            });
        }, "cookie-paste-validation").start();
    }

    // ------------------------------------------------------------------ dialogs

    private void showSuccessDialog() {
        new AlertDialog.Builder(this)
                .setTitle("✓ Cookies found")
                .setMessage("Your Google sign-in was confirmed.\n\n"
                          + "Tap OK to continue and download your Liked Songs.")
                .setPositiveButton("OK", (d, w) -> {
                    setResult(RESULT_OK, new Intent());
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    private void updateStatus(String text) {
        if (statusView != null) statusView.setText(text);
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Do not auto-close if a success dialog is on screen; otherwise allow the user to cancel.
        if (!validated) {
            super.onBackPressed();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }
}