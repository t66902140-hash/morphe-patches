/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

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
 * Fetches arbitrary YouTube Music playlists from a URL or bare id.
 *
 * <p>Handles the common playlist shapes:</p>
 * <ul>
 *   <li>{@code https://music.youtube.com/playlist?list=OLAK5uy_...} → {@code VLOLAK5uy_...}</li>
 *   <li>{@code ?list=PL...} → {@code VLPL...}</li>
 *   <li>Bare {@code PL...}, {@code OLAK5uy_...}, {@code VL...}</li>
 *   <li>Special auto-playlists {@code VLLM}, {@code FEmusic_liked_videos}, {@code FEmusic_history}</li>
 * </ul>
 *
 * <p>The fetch requires cookies for authenticated playlists and follows continuation tokens
 * until the server stops returning them. The playlist title is extracted from the response
 * header for use as a playlist name, falling back to the browse id when it cannot be read.</p>
 */
public final class RemotePlaylistFetcher {

    /** Result of a successful fetch: display title and ordered video ids. */
    public record Result(String title, List<String> videoIds) {
    }

    private static final String BROWSE_URL =
            "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false";
    private static final String CLIENT_NAME = "WEB_REMIX";
    private static final String CLIENT_VERSION = "1.20260101.01.00";
    private static final int MAX_PAGES = 500;

    private static final Pattern VIDEO_ID =
            Pattern.compile("\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{11})\"");
    private static final Pattern CONTINUATION =
            Pattern.compile("\"continuation\"\\s*:\\s*\"([A-Za-z0-9_%=-]{16,})\"");
    private static final Pattern PLAYLIST_PARAM =
            Pattern.compile("[?&]list=([A-Za-z0-9_-]+)");
    private static final Pattern TITLE_PATTERN =
            Pattern.compile(
                    "\"(?:musicDetailHeaderRenderer|musicResponsiveHeaderRenderer"
                    + "|musicEditablePlaylistDetailHeaderRenderer)\""
                    + "[\\s\\S]{0,1000}?"
                    + "\"title\"\\s*:\\s*\\{\\s*\"runs\"\\s*:\\s*\\[\\s*\\{\\s*\"text\"\\s*:\\s*\"([^\"]+)\"");

    private RemotePlaylistFetcher() {
    }

    /**
     * Convenience entry point. Parses the input, then fetches the corresponding playlist.
     *
     * @return {@code null} when cookies are missing, the input cannot be parsed, or the
     *         server refuses the request.
     */
    @Nullable
    public static Result fetchBlocking(String input) {
        if (!CookieBridge.hasCookies()) {
            Logger.printDebug(() -> "No cookies for remote playlist fetch");
            return null;
        }
        String browseId = parseBrowseId(input);
        if (browseId == null) {
            Logger.printDebug(() -> "Could not parse playlist id from: " + input);
            return null;
        }
        return fetchByBrowseId(browseId);
    }

    /**
     * Blocking fetch by browse id. Follows continuation tokens until the server stops
     * returning them, capped at {@link #MAX_PAGES} pages.
     */
    @Nullable
    public static Result fetchByBrowseId(String browseId) {
        Set<String> ids = new LinkedHashSet<>();
        String title = null;
        String continuation = null;

        for (int page = 0; page < MAX_PAGES; page++) {
            String response = browse(browseId, continuation);
            if (response == null) return null;

            if (title == null) title = extractTitle(response);

            int before = ids.size();
            Matcher m = VIDEO_ID.matcher(response);
            while (m.find()) ids.add(m.group(1));

            String next = findNextContinuation(response, continuation);
            if (next == null || ids.size() == before) break;
            continuation = next;
        }

        return new Result(title != null ? title : browseId, new ArrayList<>(ids));
    }

    @Nullable
    private static String parseBrowseId(@Nullable String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return null;

        // Special auto-playlists that are used without a VL prefix.
        if (trimmed.matches("(?i)(VLLM|VLLL|LL|LM|FEmusic_liked_videos|FEmusic_history|FEmusic_liked_playlists)")) {
            return trimmed;
        }

        Matcher listMatch = PLAYLIST_PARAM.matcher(trimmed);
        if (listMatch.find()) {
            String raw = listMatch.group(1);
            if (raw != null && !raw.isEmpty()) {
                return raw.startsWith("VL") ? raw : "VL" + raw;
            }
        }

        if (trimmed.matches("[A-Za-z0-9_-]{6,}")) {
            if (trimmed.startsWith("VL") || trimmed.startsWith("FEmusic")) return trimmed;
            return "VL" + trimmed;
        }

        return null;
    }

    @Nullable
    private static String browse(String browseId, @Nullable String continuation) {
        HttpURLConnection connection = null;
        try {
            String payload = continuation == null
                    ? "{\"context\":{\"client\":{\"clientName\":\"" + CLIENT_NAME
                      + "\",\"clientVersion\":\"" + CLIENT_VERSION
                      + "\",\"hl\":\"en\",\"gl\":\"US\"}},\"browseId\":\"" + browseId + "\"}"
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
            Logger.printException(() -> "Remote playlist browse failed", ex);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Nullable
    private static String extractTitle(String response) {
        Matcher m = TITLE_PATTERN.matcher(response);
        if (m.find()) return m.group(1);
        return null;
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
}