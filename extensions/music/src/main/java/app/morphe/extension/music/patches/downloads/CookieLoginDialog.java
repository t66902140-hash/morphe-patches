/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
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
 * Cookie capture dialog for YouTube Music.
 *
 * <p>This is a {@link Dialog}, not an {@link Activity}, because the patch cannot inject new
 * activity entries into the host app's manifest. The dialog hosts a WebView only for the
 * duration of the sign-in flow; once the user confirms, it fires {@link Callback#onSuccess()}
 * and dismisses, letting the caller start the Liked Songs download without any further input.
 *
 * <p>Two modes:</p>
 * <ul>
 *   <li>{@link #MODE_WEBVIEW} loads Google's sign-in page in a WebView that exists only for
 *       this purpose. Cookies for {@code music.youtube.com} are polled for; once they contain
 *       {@code SAPISID} or {@code __Secure-3PAPISID}, they are validated server-side and a
 *       success dialog asks the user to confirm.</li>
 *   <li>{@link #MODE_PASTE} shows a text field where the user pastes a {@code cookies.txt}
 *       export. The paste is parsed, injected into {@link CookieManager}, validated, and the
 *       same success dialog appears.</li>
 * </ul>
 */
public final class CookieLoginDialog extends Dialog {

    /** Callback invoked once the dialog finishes. Always called on the main thread. */
    public interface Callback {
        void onSuccess();
        void onCancelled();
    }

    public static final int MODE_WEBVIEW = 0;
    public static final int MODE_PASTE = 1;

    private static final String MUSIC_URL = "https://music.youtube.com";
    private static final String GOOGLE_LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&continue="
            + "https%3A%2F%2Fmusic.youtube.com";

    private static final long POLL_INTERVAL_MS = 1500;

    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WebView webView;
    private EditText pasteField;
    private ProgressBar spinner;
    private TextView statusView;
    private Button switchModeButton;
    private LinearLayout rootLayout;

    private Runnable pollRunnable;
    private boolean validated;
    private boolean completed;
    private int mode = MODE_WEBVIEW;

    public CookieLoginDialog(Context context, Callback callback) {
        super(context);
        this.callback = callback;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        CookieBridge.ensureCookieManager();

        buildLayout();
        configureWindow();
        showMode(MODE_WEBVIEW);

        setOnCancelListener(dialog -> {
            if (!completed && callback != null) callback.onCancelled();
        });
    }

    private void configureWindow() {
        Window window = getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        int width = (int) (dm.widthPixels * 0.95f);
        int height = (int) (dm.heightPixels * 0.90f);
        window.setLayout(width, height);
    }

    private void buildLayout() {
        rootLayout = new LinearLayout(getContext());
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.WHITE);

        statusView = new TextView(getContext());
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusView.setTextColor(Color.WHITE);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        statusView.setPadding(dp(16), dp(12), dp(16), dp(12));
        statusView.setBackgroundColor(Color.rgb(0x20, 0x20, 0x20));
        statusView.setText("Sign in to YouTube Music to continue");
        rootLayout.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        spinner = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        spinner.setIndeterminate(true);
        spinner.setVisibility(View.GONE);
        rootLayout.addView(spinner, new LinearLayout.LayoutParams(-1, dp(4)));

        switchModeButton = new Button(getContext());
        switchModeButton.setText("Paste cookies instead");
        switchModeButton.setOnClickListener(v ->
                showMode(mode == MODE_WEBVIEW ? MODE_PASTE : MODE_WEBVIEW));
        LinearLayout.LayoutParams switchLp =
                new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        switchLp.setMargins(dp(12), dp(8), dp(12), dp(8));
        rootLayout.addView(switchModeButton, switchLp);

        setContentView(rootLayout);
    }

    private void showMode(int newMode) {
        mode = newMode;
        stopPolling();

        // Keep the header (status + progress + switch button), remove everything below.
        while (rootLayout.getChildCount() > 3) {
            rootLayout.removeViewAt(3);
        }

        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }

        if (mode == MODE_WEBVIEW) {
            switchModeButton.setText("Paste cookies instead");
            buildWebViewMode();
            updateStatus("Sign in with your Google account");
        } else {
            switchModeButton.setText("Use sign-in page instead");
            buildPasteMode();
            updateStatus("Paste your cookies.txt export");
        }
    }

    private void buildWebViewMode() {
        webView = new WebView(getContext());
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
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

        rootLayout.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1f));
        webView.loadUrl(GOOGLE_LOGIN_URL);
        startPolling();
    }

    private void buildPasteMode() {
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView instructions = new TextView(getContext());
        instructions.setText(
                "Export your cookies from any browser (Chrome, Firefox, Kiwi) using the "
                + "\"Get cookies.txt LOCALLY\" extension while signed in to music.youtube.com. "
                + "Paste the file content, or just the Cookie header from DevTools, below.");
        instructions.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        instructions.setTextColor(Color.DKGRAY);
        box.addView(instructions);

        pasteField = new EditText(getContext());
        pasteField.setHint("Paste cookies.txt content here");
        pasteField.setGravity(Gravity.TOP | Gravity.START);
        pasteField.setMinLines(10);
        pasteField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pasteField.setBackgroundColor(Color.rgb(0xF2, 0xF2, 0xF2));
        pasteField.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(-1, dp(220));
        fieldLp.topMargin = dp(12);
        box.addView(pasteField, fieldLp);

        Button validate = new Button(getContext());
        validate.setText("Validate & Save");
        validate.setOnClickListener(v -> validatePaste());
        LinearLayout.LayoutParams btnLp =
                new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(12);
        box.addView(validate, btnLp);

        scroll.addView(box);
        rootLayout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    private void startPolling() {
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
                mainHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private void stopPolling() {
        if (pollRunnable != null) {
            mainHandler.removeCallbacks(pollRunnable);
            pollRunnable = null;
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
            mainHandler.post(() -> {
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

    private void validatePaste() {
        String raw = pasteField.getText().toString();
        if (raw.trim().isEmpty()) {
            Toast.makeText(getContext(), "Paste the cookies first", Toast.LENGTH_SHORT).show();
            return;
        }
        spinner.setVisibility(View.VISIBLE);
        updateStatus("Validating...");

        new Thread(() -> {
            boolean injected = CookieBridge.injectCookies(raw);
            boolean ok = injected && CookieBridge.validateCookies();
            mainHandler.post(() -> {
                spinner.setVisibility(View.GONE);
                if (ok) {
                    updateStatus("✓ Cookies validated");
                    showSuccessDialog();
                } else {
                    updateStatus("Cookies rejected");
                    new AlertDialog.Builder(getContext())
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

    private void showSuccessDialog() {
        if (completed) return;
        new AlertDialog.Builder(getContext())
                .setTitle("✓ Cookies found")
                .setMessage("Your Google sign-in was confirmed.\n\n"
                          + "Tap OK to continue and download your Liked Songs.")
                .setPositiveButton("OK", (d, w) -> {
                    completed = true;
                    stopPolling();
                    if (callback != null) callback.onSuccess();
                    dismiss();
                })
                .setCancelable(false)
                .show();
    }

    private void updateStatus(String text) {
        if (statusView != null) statusView.setText(text);
    }

    @Override
    public void dismiss() {
        stopPolling();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.dismiss();
    }

    private int dp(int value) {
        return (int) (value * getContext().getResources().getDisplayMetrics().density + .5f);
    }
}