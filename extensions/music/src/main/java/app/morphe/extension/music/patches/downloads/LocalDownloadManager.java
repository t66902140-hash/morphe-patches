/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.scrobbling.ScrobbleManager;
import app.morphe.extension.music.patches.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.ThumbnailEntry;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/** Downloads the already resolved InnerTube audio stream without leaving YouTube Music. */
public final class LocalDownloadManager {

    // === YENİ: Liked Songs toplu indirme dinleyicisi ===
    /** Callback used by the Liked Songs bulk-download flow. All methods run on the main thread. */
    public interface LikedSongsListener {
        /** Fired once, right before the playlist is fetched. */
        void onStarting();
        /** Fired as the playlist is fetched, before any track is downloaded. */
        void onFetching(int foundSoFar);
        /** Fired before each track is downloaded. {@code current} is 1-based. */
        void onProgress(int current, int total);
        /** Fired when the whole batch finished. */
        void onComplete(int successCount, int failedCount);
        /** Fired when the batch could not start at all. */
        void onFailed(String reason);
    }

    private static final String DOWNLOAD_CHANNEL = "morphe_audio_downloads";
    private static final String USER_AGENT =
            "com.google.android.youtube/19.47.53 (Linux; U; Android 14) gzip";

    /** Batch notification key, separate from per-track notifications. */
    private static final String LIKED_SONGS_NOTIFICATION_KEY = "liked_songs_batch";

    // === YENİ: Aynı anda iki batch çalışmasını engeller ===
    private static final AtomicBoolean LIKED_SONGS_RUNNING = new AtomicBoolean(false);

    private static final int SEGMENT_COUNT = 4;
    private static final int BUFFER_SIZE = 64 * 1024;

    private static final long NOTIFICATION_INTERVAL_MILLISECONDS = 1000;
    private static final AtomicLong lastNotificationTime = new AtomicLong();

    private static final int CONNECT_TIMEOUT_MILLISECONDS = 15_000;
    private static final int READ_TIMEOUT_MILLISECONDS = 30_000;
    private static final int MAX_STALLED_ATTEMPTS = 4;

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{1,20}");

    private static final String THUMBNAIL_URL = "https://i.ytimg.com/vi/";

    private static final Set<String> ACTIVE_DOWNLOADS = ConcurrentHashMap.newKeySet();

    private LocalDownloadManager() {
    }

    // ==================================================================
    // PUBLIC ENTRY POINTS
    // ==================================================================

    /** Single-track download. Unchanged from the original API. */
    public static void enqueue(String videoId) {
        if (!VIDEO_ID.matcher(videoId).matches()) {
            Logger.printDebug(() -> "Refusing to download an unusable id: " + videoId);
            Utils.showToastShort(str("morphe_music_downloads_unknown_track"));
            return;
        }

        final boolean isPlayingTrack = videoId.equals(VideoInformation.getVideoId());
        ScrobbleManager metadata = ScrobbleManager.getInstance();

        String title = isPlayingTrack ? metadata.getCurrentTitle() : null;
        String artist = isPlayingTrack ? metadata.getCurrentArtist() : null;
        String album = isPlayingTrack ? metadata.getCurrentAlbum() : null;
        int duration = isPlayingTrack ? metadata.getCurrentDurationSeconds() : 0;
        Bitmap artwork = isPlayingTrack ? metadata.getCurrentArtwork() : null;

        Utils.submitOnBackgroundThread(() -> {
            downloadBlocking(videoId, title, artist, album, duration, artwork, false);
            return null;
        });
    }

    /**
     * Fetches the user's Liked Songs playlist and downloads every track in order.
     *
     * <p>Requires cookies from {@link CookieBridge}. Callers should check
     * {@link CookieBridge#hasCookies()} first and route the user to
     * {@link CookieLoginActivity} when it returns {@code false}.</p>
     *
     * <p>All callbacks are dispatched on the main thread. Passing a {@code null} listener
     * is allowed; the batch will still run and show notifications.</p>
     */
    public static void enqueueLikedSongs(@Nullable LikedSongsListener listener) {
        if (!LIKED_SONGS_RUNNING.compareAndSet(false, true)) {
            postToListener(listener, l -> l.onFailed("already_running"));
            return;
        }

        postToListener(listener, LikedSongsListener::onStarting);

        Utils.submitOnBackgroundThread(() -> {
            try {
                runLikedSongsBatch(listener);
            } catch (Exception ex) {
                Logger.printException(() -> "Liked Songs batch crashed", ex);
                postToListener(listener, l -> l.onFailed("unexpected_error"));
            } finally {
                LIKED_SONGS_RUNNING.set(false);
            }
            return null;
        });
    }

    // ==================================================================
    // LIKED SONGS BATCH
    // ==================================================================

    private static void runLikedSongsBatch(@Nullable LikedSongsListener listener) {
        if (!CookieBridge.hasCookies()) {
            postToListener(listener, l -> l.onFailed("not_authenticated"));
            return;
        }

        Utils.showToastShort("Fetching Liked Songs...");
        awaitNotificationSlot();
        showDownloadNotification(LIKED_SONGS_NOTIFICATION_KEY, "Liked Songs",
                0, true, "Fetching playlist...");

        final List<String> allIds = LikedSongsDownloader.fetchBlocking();
        if (allIds == null) {
            awaitNotificationSlot();
            showDownloadNotification(LIKED_SONGS_NOTIFICATION_KEY, "Liked Songs",
                    0, false, "Failed to fetch playlist");
            Utils.showToastShort("Could not fetch Liked Songs. Please sign in again.");
            postToListener(listener, l -> l.onFailed("fetch_failed"));
            return;
        }

        final int found = allIds.size();
        postToListener(listener, l -> l.onFetching(found));

        if (found == 0) {
            awaitNotificationSlot();
            showDownloadNotification(LIKED_SONGS_NOTIFICATION_KEY, "Liked Songs",
                    100, false, "No liked songs found");
            Utils.showToastShort("No liked songs found on this account.");
            postToListener(listener, l -> l.onComplete(0, 0));
            return;
        }

        // Skip tracks that are already on disk.
        File directory = musicDirectory();
        List<String> toDownload = new ArrayList<>();
        for (String id : allIds) {
            if (!isTrackOnDisk(directory, id)) toDownload.add(id);
        }

        final int total = toDownload.size();
        if (total == 0) {
            awaitNotificationSlot();
            showDownloadNotification(LIKED_SONGS_NOTIFICATION_KEY, "Liked Songs",
                    100, false, "All tracks already downloaded");
            Utils.showToastShort("All Liked Songs are already downloaded.");
            postToListener(listener, l -> l.onComplete(0, 0));
            return;
        }

        Utils.showToastShort("Downloading " + total + " liked songs...");

        int success = 0;
        int failed = 0;

        for (int i = 0; i < total; i++) {
            String id = toDownload.get(i);
            final int current = i + 1;
            final int percentage = (current * 100) / total;

            showDownloadNotification(LIKED_SONGS_NOTIFICATION_KEY, "Liked Songs",
                    percentage, true, current + " / " + total + " tracks");
            postToListener(listener, l -> l.onProgress(current, total));

            try {
                boolean ok = downloadBlocking(id, null, null, null, 0, null, true);
                if (ok) success++;
                else failed++;
            } catch (Exception ex) {
                Logger.printException(() -> "Batch track failed: " + id, ex);
                failed++;
            }
        }

        awaitNotificationSlot();
        String summary = success + " downloaded" + (failed > 0 ? ", " + failed + " failed" : "");
        showDownloadNotification(LIKED_SONGS_NOTIFICATION_KEY, "Liked Songs",
                100, false, summary);
        Utils.showToastShort(summary);

        final int s = success;
        final int f = failed;
        postToListener(listener, l -> l.onComplete(s, f));
    }

    private static void postToListener(@Nullable LikedSongsListener listener,
                                       Consumer<LikedSongsListener> action) {
        if (listener == null) return;
        Utils.runOnMainThread(() -> {
            try {
                action.accept(listener);
            } catch (Exception ex) {
                Logger.printException(() -> "Liked Songs listener failed", ex);
            }
        });
    }

    private static File musicDirectory() {
        Context context = Utils.getContext();
        return new File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");
    }

    private static boolean isTrackOnDisk(File directory, String videoId) {
        return new File(directory, videoId + ".webm").isFile()
                || new File(directory, videoId + ".m4a").isFile();
    }

    // ==================================================================
    // SINGLE-TRACK DOWNLOAD (unchanged except for the new `quiet` flag)
    // ==================================================================

    /**
     * @param quiet When {@code true}, per-track toasts and notifications are suppressed so the
     *              caller can drive a single aggregate notification instead. Used by
     *              {@link #enqueueLikedSongs}.
     * @return {@code true} if the track was saved (or already existed), {@code false} on error.
     */
    private static boolean downloadBlocking(String videoId, String title, String artist, String album,
                                            int duration, Bitmap artwork, boolean quiet) {
        if (!ACTIVE_DOWNLOADS.add(videoId)) {
            if (!quiet) Utils.showToastShort(str("morphe_music_downloads_already_running"));
            return false;
        }

        try {
            Context context = Utils.getContext();
            File directory = new File(
                    context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");

            if (isTrackOnDisk(directory, videoId)) {
                Logger.printDebug(() -> "Already downloaded: " + videoId);
                if (!quiet) Utils.showToastShort(str("morphe_music_downloads_already_saved"));
                return true;
            }

            ResolvedStream resolved = resolveStream(videoId);
            if (resolved == null || resolved.format().getUrl().isBlank()) {
                throw new IllegalStateException("Audio stream unavailable");
            }

            Format format = resolved.format();
            Logger.printDebug(() -> "Downloading " + videoId + " as itag " + format.getItag()
                    + " (" + format.getMimeType() + ")");

            VideoDetails details = resolved.details();

            title = firstNotBlank(title, details.getTitle(), videoId);
            artist = firstNotBlank(artist, stripTopic(details.getOwnerChannelName()), "");
            if (duration <= 0) duration = details.getLengthSeconds();
            if (artwork == null) artwork = resolveArtwork(videoId, details);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("Could not create download directory");
            }

            String extension = format.getMimeType().contains("mp4") ? ".m4a" : ".webm";
            File destination = new File(directory, videoId + extension);
            File temporary = new File(directory, destination.getName() + ".part");

            if (temporary.exists() && !temporary.delete()) {
                throw new IllegalStateException("Could not reset partial audio file");
            }

            downloadWithResume(videoId, title, format.getUrl(), temporary,
                    format.getContentLength(), quiet);

            if (destination.exists() && !destination.delete()) {
                throw new IllegalStateException("Could not replace audio file");
            }
            if (!temporary.renameTo(destination)) {
                throw new IllegalStateException("Could not finish audio file");
            }
            Logger.printDebug(() -> "Saved " + destination + " (" + destination.length() + " bytes)");

            OfflineTrack.save(directory, videoId, title, artist, album, duration, artwork);
            if (!quiet) Utils.showToastShort(str("morphe_music_downloads_complete"));
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Local audio download failed: " + videoId, ex);
            if (!quiet) {
                awaitNotificationSlot();
                showDownloadNotification(videoId, title, 0, false,
                        str("morphe_music_downloads_failed"));
                Utils.showToastShort(str("morphe_music_downloads_failed"));
            }
            return false;
        } finally {
            ACTIVE_DOWNLOADS.remove(videoId);
        }
    }

    private record ResolvedStream(Format format, VideoDetails details) {
    }

    private static String firstNotBlank(@Nullable String value, String fallback, String lastResort) {
        if (value != null && !value.isBlank()) return value;
        if (!fallback.isBlank()) return fallback;
        return lastResort;
    }

    private static String stripTopic(String channelName) {
        return channelName.endsWith(" - Topic")
                ? channelName.substring(0, channelName.length() - " - Topic".length())
                : channelName;
    }

    private static boolean canPostProgress() {
        long now = System.currentTimeMillis();
        long previous = lastNotificationTime.get();
        return now - previous >= NOTIFICATION_INTERVAL_MILLISECONDS
                && lastNotificationTime.compareAndSet(previous, now);
    }

    private static void awaitNotificationSlot() {
        while (true) {
            long now = System.currentTimeMillis();
            long previous = lastNotificationTime.get();
            long remaining = NOTIFICATION_INTERVAL_MILLISECONDS - (now - previous);

            if (remaining <= 0) {
                if (lastNotificationTime.compareAndSet(previous, now)) return;
                continue;
            }

            try {
                //noinspection BusyWait
                Thread.sleep(remaining);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Nullable
    private static Bitmap resolveArtwork(String videoId, VideoDetails details) {
        Bitmap artwork = downloadArtwork(largestThumbnail(details));
        if (artwork != null) return artwork;

        artwork = downloadArtwork(THUMBNAIL_URL + videoId + "/maxresdefault.jpg");
        return artwork != null ? artwork : downloadArtwork(THUMBNAIL_URL + videoId + "/hqdefault.jpg");
    }

    @Nullable
    private static String largestThumbnail(VideoDetails details) {
        String url = null;
        int widest = 0;
        for (ThumbnailEntry entry : details.getThumbnail().getThumbnailsList()) {
            if (entry.getWidth() >= widest && !entry.getUrl().isBlank()) {
                widest = entry.getWidth();
                url = entry.getUrl();
            }
        }
        return url;
    }

    @Nullable
    private static Bitmap downloadArtwork(@Nullable String url) {
        if (url == null || url.isBlank()) return null;
        try (InputStream input = Requester.openConnection(url).getInputStream()) {
            return BitmapFactory.decodeStream(input);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch artwork: " + url, ex);
            return null;
        }
    }

    private static void downloadWithResume(String videoId, String title, String url,
                                           File temporary, long expectedLength, boolean quiet) throws Exception {
        if (!quiet) {
            Utils.showToastShort(str("morphe_music_downloads_started"));
            awaitNotificationSlot();
            showDownloadNotification(videoId, title, 0, true,
                    str("morphe_music_downloads_starting"));
        }

        if (expectedLength <= 0) expectedLength = probeContentLength(url);
        final long totalLength = expectedLength;

        if (totalLength <= 0) {
            downloadUnknownLength(url, temporary);
        } else {
            downloadInParallel(videoId, title, url, temporary, totalLength, quiet);
        }

        if (totalLength > 0 && temporary.length() != totalLength) {
            throw new IllegalStateException(
                    "Incomplete audio file: " + temporary.length() + "/" + totalLength);
        }
        if (!quiet) {
            awaitNotificationSlot();
            showDownloadNotification(videoId, title, 100, false,
                    str("morphe_music_downloads_complete"));
        }
    }

    private static void downloadInParallel(String videoId, String title, String url,
                                           File temporary, long totalLength, boolean quiet) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(temporary, "rw")) {
            file.setLength(totalLength);
        }

        AtomicLong downloaded = new AtomicLong();
        long segmentSize = (totalLength + SEGMENT_COUNT - 1) / SEGMENT_COUNT;

        //noinspection resource
        ExecutorService executor = Executors.newFixedThreadPool(SEGMENT_COUNT);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < SEGMENT_COUNT; index++) {
                long start = index * segmentSize;
                long end = Math.min(totalLength - 1, start + segmentSize - 1);
                if (start > end) continue;

                futures.add(executor.submit(() -> {
                    downloadRange(url, temporary, start, end, downloaded, bytes -> {
                        if (quiet || !canPostProgress()) return;
                        int progress = (int) Math.min(99, bytes * 100 / totalLength);
                        showDownloadNotification(videoId, title, progress, true,
                                str("morphe_music_downloads_progress", progress));
                    });
                    return null;
                }));
            }

            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private interface ProgressCallback {
        void update(long bytes);
    }

    private static void downloadRange(String url, File destination, long start, long end,
                                      AtomicLong downloaded, ProgressCallback progress) throws Exception {
        long position = start;
        int failures = 0;

        while (position <= end) {
            long before = position;
            HttpURLConnection connection = openAudioConnection(url, position, end);

            try {
                if (connection.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) {
                    throw new IllegalStateException(
                            "Range request returned HTTP " + connection.getResponseCode());
                }

                try (InputStream input = connection.getInputStream();
                     RandomAccessFile output = new RandomAccessFile(destination, "rw")) {
                    output.seek(position);
                    byte[] buffer = new byte[BUFFER_SIZE];

                    while (position <= end) {
                        int read = input.read(buffer, 0,
                                (int) Math.min(buffer.length, end - position + 1));
                        if (read < 0) break;

                        output.write(buffer, 0, read);
                        position += read;
                        progress.update(downloaded.addAndGet(read));
                    }
                }
            } catch (IOException ex) {
                Logger.printDebug(() -> "Audio range interrupted; retrying", ex);
            } finally {
                connection.disconnect();
            }

            failures = position > before ? 0 : failures + 1;
            if (failures >= MAX_STALLED_ATTEMPTS) {
                throw new IllegalStateException("Download range stalled at " + position);
            }
        }
    }

    private static long probeContentLength(String url) {
        HttpURLConnection connection = null;
        try {
            connection = openAudioConnection(url, 0, 0);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) return -1;

            String contentRange = connection.getHeaderField("Content-Range");
            if (contentRange == null) return -1;

            int slash = contentRange.lastIndexOf('/');
            return slash < 0 ? -1 : Long.parseLong(contentRange.substring(slash + 1));
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not probe audio length", ex);
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static HttpURLConnection openAudioConnection(String url, long start, long end) throws Exception {
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", "https://music.youtube.com/");
        connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
        return connection;
    }

    private static void downloadUnknownLength(String url, File destination) throws Exception {
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
        connection.setRequestProperty("User-Agent", USER_AGENT);

        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + connection.getResponseCode());
            }

            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    static void showDownloadNotification(String key, String title, int progress,
                                         boolean ongoing, String text) {
        Utils.runOnMainThread(() -> postDownloadNotification(key, title, progress, ongoing, text));
    }

    @SuppressLint("NotificationPermission") // The patch declares POST_NOTIFICATIONS.
    private static void postDownloadNotification(String key, String title, int progress,
                                                 boolean ongoing, String text) {
        Context context = Utils.getContext();
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        manager.createNotificationChannel(new NotificationChannel(DOWNLOAD_CHANNEL,
                str("morphe_music_downloads_notification_channel"),
                NotificationManager.IMPORTANCE_LOW));

        Notification.Builder builder = new Notification.Builder(context, DOWNLOAD_CHANNEL);
        builder.setSmallIcon(ongoing
                        ? android.R.drawable.stat_sys_download
                        : android.R.drawable.stat_sys_download_done)
                .setContentTitle(title == null || title.isBlank() ? "YouTube Music" : title)
                .setContentText(text)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true);

        if (ongoing) builder.setProgress(100, Math.max(0, progress), progress < 0);
        manager.notify(key.hashCode(), builder.build());
    }

    @Nullable
    private static ResolvedStream resolveStream(String videoId) {
        StreamingDataRequest request = StreamingDataRequest.getRequestForVideoId(videoId);
        if (request == null) {
            Logger.printDebug(() -> "No cached streaming request for download, resolving: " + videoId);
            request = StreamingDataRequest.fetchRequestForDownload(videoId,
                    SpoofVideoStreamsPatch.getAvailableClients(),
                    Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get());
        }

        StreamingDataRequest.StreamData stream = request.getStream();
        if (stream == null) {
            Logger.printDebug(() -> "No stream data resolved for " + videoId);
            return null;
        }

        try {
            PlayerResponse response = PlayerResponse.parseFrom(stream.streamingData());
            Format best = response.getStreamingData()
                    .getAdaptiveFormatsList()
                    .stream()
                    .filter(format -> format.getMimeType().startsWith("audio/")
                            && !format.getUrl().isBlank())
                    .max(Comparator.comparingInt(Format::getBitrate))
                    .orElse(null);
            return best == null ? null : new ResolvedStream(best, response.getVideoDetails());
        } catch (Exception ex) {
            Logger.printException(() -> "Could not parse audio formats: " + videoId, ex);
            return null;
        }
    }
}