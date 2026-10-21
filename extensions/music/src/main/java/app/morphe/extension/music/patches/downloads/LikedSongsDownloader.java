/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;

/**
 * Fetches the video IDs of the signed-in user's Liked Songs playlist.
 *
 * <p>Uses the {@code WEB_REMIX} InnerTube client with {@code browseId=VLLM}, which is the
 * internal identifier YouTube Music assigns to the special "Liked Music" auto-playlist. The
 * endpoint requires {@code SAPISIDHASH} authentication; the response for an anonymous
 * request contains no items at all. Continuation tokens are followed page by page until the
 * server stops returning them.</p>
 *
 * <p>This class only returns video IDs. It does not resolve audio streams, save files, or
 * talk to the download queue; that is the caller's job.</p>
 */
public final class LikedSongsDownloader {

    private static final String BROWSE_URL =
            "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false";
    private static final String BROWSE_ID = "VLLM";
    private static final String CLIENT_NAME = "WEB_REMIX";
    private static final String CLIENT_VERSION = "1.20260101.01.00";

    /** Hard cap so a corrupted continuation token can never spin forever. */
    private static final int MAX_PAGES = 500;

    private static final Pattern VIDEO_ID =
            Pattern.compile("\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{11})\"");
    private static final Pattern CONTINUATION =
            Pattern.compile("\"continuation\"\\s*:\\s*\"([A-Za-z0-9_%=-]{16,})\"");

    private LikedSongsDownloader() {
    }

    /**
     * Blocking fetch of every video ID in the Liked Songs playlist.
     *
     * @return The IDs in playlist order, or {@code null} if cookies are missing or the
     *         server refused the request.
     */
    @Nullable
    public static List<String> fetchBlocking() {
        if (!CookieBridge.hasCookies()) {
            Logger.printDebug(() -> "No cookies available for Liked Songs fetch");
            return null;
        }

        Set<String> ids = new LinkedHashSet<>();
        String continuation = null;

        for (int page = 0; page < MAX_PAGES; page++) {
            String response = browse(continuation);
            if (response == null) return null;

            int before = ids.size();
            Matcher m = VIDEO_ID.matcher(response);
            while (m.find()) ids.add(m.group(1));

            String next = findNextContinuation(response, continuation);
            if (next == null || ids.size() == before) break;
            continuation = next;
        }

        return new ArrayList<>(ids);
    }

    @Nullable
    private static String browse(@Nullable String continuation) {
        HttpURLConnection connection = null;
        try {
            String payload = continuation == null
                    ? "{\"context\":{\"client\":{\"clientName\":\"" + CLIENT_NAME
                      + "\",\"clientVersion\":\"" + CLIENT_VERSION
                      + "\",\"hl\":\"en\",\"gl\":\"US\"}},\"browseId\":\"" + BROWSE_ID + "\"}"
                    : "{\"context\":{\"client\":{\"clientName\":\"" + CLIENT_NAME
                      + "\",\"clientVersion\":\"" + CLIENT_VERSION
                      + "\",\"hl\":\"en\",\"gl\":\"US\"}},\"continuation\":\""
                      + continuation + "\"}";

            connection = (HttpURLConnection) new URL(BROWSE_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
            connection.setRequestProperty("Origin", "https://music.youtube.com");
            connection.setRequestProperty("X-Goog-AuthUser", "0");
            connection.setRequestProperty("X-YouTube-Client-Name", "67");
            connection.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION);

            String cookies = CookieBridge.getCookies();
            if (cookies != null && !cookies.isEmpty()) {
                connection.setRequestProperty("Cookie", cookies);
            }
            String authorization = CookieBridge.getAuthorizationHeader();
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
                Logger.printDebug(() -> "browse returned HTTP " + responseCode);
                return null;
            }

            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
                return buffer.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Liked Songs browse failed", ex);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Nullable
    private static String findNextContinuation(String response, @Nullable String current) {
        Matcher m = CONTINUATION.matcher(response);
        while (m.find()) {
            String candidate = m.group(1);
            if (!candidate.equals(current)) return candidate;
        }
        return null;
    }

    /**
     * Sanity check used by callers before showing the sign-in prompt. A non-empty list
     * indicates the fetch succeeded; a {@code null} means cookies are stale or missing.
     */
    public static boolean isReady() {
        return CookieBridge.hasCookies();
    }

    /**
     * Diagnostic helper retained for callers that want to inspect the raw response without
     * running the full pipeline. Not used by the download path.
     */
    @Nullable
    public static JSONObject fetchFirstPageRaw() {
        String response = browse(null);
        if (response == null) return null;
        try {
            return new JSONObject(response);
        } catch (Exception ex) {
            return null;
        }
    }
}