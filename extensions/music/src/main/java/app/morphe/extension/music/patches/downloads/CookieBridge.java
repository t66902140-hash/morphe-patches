/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.webkit.CookieManager;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;

/**
 * Session cookies for authenticated YouTube Music requests.
 *
 * <p>The extension does not embed a general-purpose browser, but listing a user's Liked Songs
 * through the InnerTube API requires {@code SAPISIDHASH} authentication. That header is
 * derived from Google session cookies, which live in the app-wide {@link CookieManager}.
 * Three paths can fill that jar:</p>
 *
 * <ol>
 *   <li><b>Silent account hint:</b> {@link #hasGoogleAccount(Context)} tells the UI whether
 *       the device already has a Google account, so the sign-in prompt can be more specific.</li>
 *   <li><b>WebView sign-in:</b> {@link CookieLoginActivity} opens Google's sign-in page in a
 *       WebView dedicated to this purpose, and the resulting cookies are picked up
 *       automatically by the shared {@link CookieManager}.</li>
 *   <li><b>Cookie paste:</b> if the WebView flow is cancelled, the user can paste a
 *       {@code cookies.txt} export from any browser; {@link #injectCookies(String)} parses
 *       it and writes the relevant cookies back into the jar.</li>
 * </ol>
 */
public final class CookieBridge {

    private static final String MUSIC_ORIGIN = "https://music.youtube.com";
    private static final String ACCOUNT_MENU_URL =
            "https://music.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false";
    private static final String CLIENT_VERSION = "1.20240101.01.00";

    private static final String[] AUTH_COOKIE_NAMES = {
            "SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID",
            "APISID", "SID", "HSID", "SSID", "PSID", "__Secure-1PSID"
    };

    private CookieBridge() {
    }

    /** Enables cookies on the shared {@link CookieManager} instance. Safe to call repeatedly. */
    public static void ensureCookieManager() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not configure CookieManager", ex);
        }
    }

    /** @return Whether the jar currently holds a SAPISID cookie that can sign InnerTube requests. */
    public static boolean hasCookies() {
        String cookies = getCookies();
        if (cookies == null || cookies.isEmpty()) return false;
        return extractCookieValue(cookies, "__Secure-3PAPISID") != null
                || extractCookieValue(cookies, "SAPISID") != null;
    }

    /** @return The raw {@code Cookie} header for {@code music.youtube.com}, or {@code null}. */
    @Nullable
    public static String getCookies() {
        try {
            return CookieManager.getInstance().getCookie(MUSIC_ORIGIN);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Builds the {@code SAPISIDHASH} authorization header value.
     *
     * @return The header value without the {@code Authorization: } prefix, or {@code null}.
     */
    @Nullable
    public static String getAuthorizationHeader() {
        String cookies = getCookies();
        if (cookies == null) return null;

        String sapisid = extractCookieValue(cookies, "__Secure-3PAPISID");
        if (sapisid == null) sapisid = extractCookieValue(cookies, "SAPISID");
        if (sapisid == null) return null;

        try {
            long timestamp = System.currentTimeMillis() / 1000L;
            String raw = timestamp + " " + sapisid + " " + MUSIC_ORIGIN;
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b));
            return "SAPISIDHASH " + timestamp + "_" + hex;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not build SAPISIDHASH", ex);
            return null;
        }
    }

    /** Clears all cookies for this app. Used by the "Sign out" action. */
    public static void clearCookies() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not clear cookies", ex);
        }
    }

    /**
     * @return Whether the device has at least one Google account. Used by the UI to decide
     *         whether to show "You are already signed in to Google on this device" before
     *         the WebView sign-in screen appears.
     */
    public static boolean hasGoogleAccount(Context context) {
        try {
            AccountManager am = AccountManager.get(context);
            Account[] accounts = am.getAccountsByType("com.google");
            return accounts.length > 0;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Parses a raw cookie paste and writes the cookies that InnerTube needs back into the
     * jar. Accepts two formats:
     *
     * <ul>
     *   <li>Netscape {@code cookies.txt} (tab-separated lines, as exported by the
     *       "Get cookies.txt" browser extension)</li>
     *   <li>A {@code name=value; name=value} line copied from DevTools</li>
     * </ul>
     *
     * @return Whether the paste contained enough cookies to authenticate.
     */
    public static boolean injectCookies(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        try {
            Map<String, String> parsed = parseCookies(raw);
            if (parsed.isEmpty()) return false;

            CookieManager cm = CookieManager.getInstance();
            boolean injected = false;
            for (String name : AUTH_COOKIE_NAMES) {
                String value = parsed.get(name);
                if (value != null && !value.isEmpty()) {
                    cm.setCookie(MUSIC_ORIGIN, name + "=" + value);
                    injected = true;
                }
            }
            if (!injected) return false;

            cm.flush();
            return hasCookies();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not inject cookies", ex);
            return false;
        }
    }

    /**
     * Confirms that the cookies currently in the jar really belong to a signed-in YouTube
     * Music account, by calling {@code /account/account_menu}. Public requests get a
     * sign-in prompt; only a successful, authenticated request returns account metadata.
     */
    public static boolean validateCookies() {
        String cookies = getCookies();
        if (cookies == null || cookies.isEmpty()) return false;

        HttpURLConnection connection = null;
        try {
            String payload = "{\"context\":{\"client\":{"
                    + "\"clientName\":\"WEB_REMIX\","
                    + "\"clientVersion\":\"" + CLIENT_VERSION + "\","
                    + "\"hl\":\"en\",\"gl\":\"US\"}}}";

            connection = (HttpURLConnection) new URL(ACCOUNT_MENU_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Cookie", cookies);
            connection.setRequestProperty("Origin", MUSIC_ORIGIN);
            connection.setRequestProperty("X-Origin", MUSIC_ORIGIN);
            connection.setRequestProperty("X-Goog-AuthUser", "0");
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");

            String authorization = getAuthorizationHeader();
            if (authorization != null) {
                connection.setRequestProperty("Authorization", authorization);
            }

            try (OutputStream os = connection.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            // Read the code once; the lambda below cannot call getResponseCode() because
            // that method declares IOException and `connection` is not effectively final.
            final int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Logger.printDebug(() -> "account_menu HTTP " + responseCode);
                return false;
            }

            try (InputStream in = connection.getInputStream()) {
                String response = readAll(in);
                return response.contains("\"accountName\"")
                        || response.contains("\"email\"")
                        || response.contains("\"activeAccountHeaderRenderer\"");
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Cookie validation failed", ex);
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    // ------------------------------------------------------------------ parsing

    private static Map<String, String> parseCookies(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        String trimmed = raw.trim();

        if (trimmed.contains("\t")) {
            for (String line : trimmed.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\t");
                if (parts.length >= 7) {
                    result.put(parts[5].trim(), parts[6].trim());
                }
            }
            return result;
        }

        if (trimmed.toLowerCase(Locale.ROOT).startsWith("cookie:")) {
            trimmed = trimmed.substring(7).trim();
        }
        for (String pair : trimmed.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (!name.isEmpty()) result.put(name, value);
        }
        return result;
    }

    @Nullable
    private static String extractCookieValue(String cookies, String name) {
        for (String pair : cookies.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (pair.substring(0, eq).trim().equals(name)) {
                return pair.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
        return buffer.toString(StandardCharsets.UTF_8.name());
    }
}