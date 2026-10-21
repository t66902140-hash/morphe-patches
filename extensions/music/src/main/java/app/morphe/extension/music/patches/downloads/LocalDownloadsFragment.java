/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceFragment;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Pair;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.IntConsumer;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;

/** Local catalogue with playlist management, grid/list switch and stock mini player. */
@SuppressWarnings("deprecation")
public final class LocalDownloadsFragment extends PreferenceFragment
        implements OfflinePlaybackService.PlaybackListener {

    private static final int SEEK_BAR_HEIGHT_DP = 3;
    private static final int SEEK_BAR_TOUCH_DP = 18;
    private static final int SEEK_THUMB_DP = 12;

    private static final int SEARCH_BAR_HEIGHT_DP = 46;
    private static final long SEARCH_ANIMATION_MILLISECONDS = 180;

    private static final float SECONDARY_DIM = 0.72f;
    private static final float PLAYING_ROW_BRIGHTNESS = 1.35f;

    private static final int ROW_CORNER_DP = 8;
    private static final int CARD_CORNER_DP = 12;
    private static final int ROW_ARTWORK_PIXELS = 256;
    private static final int CUSTOM_COVER_PIXELS = 600;
    private static final int ARTWORK_CORNER_DP = 6;

    private static final int MENU_WIDTH_DP = 220;
    private static final int MENU_CORNER_DP = 12;
    private static final int ICON_BUTTON_DP = 48;

    private static final int REQUEST_PICK_COVER = 9401;

    private File musicRoot;
    private File playlistsDir;

    private ImageView miniArtwork;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlay;
    private ImageButton miniPrevious;
    private ImageButton miniNext;
    private SeekBar miniSeek;
    private LinearLayout miniPlayer;
    private LinearLayout contentContainer;
    private ImageButton viewToggleBtn;

    /** "♥ Download Liked Songs" chip button. Null until the header is built. */
    @Nullable
    private TextView likedSongsButton;

    private boolean showingPause;
    private boolean isGridView = true;

    private List<OfflineTrack> tracks = new ArrayList<>();
    private List<OfflinePlaylist> playlists = new ArrayList<>();

    /** Null = top-level library view. Non-null = the currently opened playlist. */
    @Nullable
    private OfflinePlaylist activePlaylist = null;

    /** Playlist waiting for a user-picked cover. */
    @Nullable
    private OfflinePlaylist pendingCoverPlaylist = null;

    private final List<View> rowViews = new ArrayList<>();
    private List<String> displayedPaths = new ArrayList<>();
    private List<String> queuePaths = new ArrayList<>();
    private String playingPath = "";
    private String query = "";
    private TextView summary;
    private EditText searchBar;
    private boolean userSeeking;

    /** RAM cache that keeps disk reads and collage generation out of the render path. */
    private final LruCache<String, Bitmap> artworkCache = new LruCache<>(60);

    private enum SortOrder {
        ARTIST, TITLE, SIZE;

        static SortOrder saved() {
            try {
                return valueOf(Settings.DOWNLOADS_SORT.get());
            } catch (Exception ignored) {
                return ARTIST;
            }
        }
    }

    /** Playlist data model. */
    private static final class OfflinePlaylist {
        final String id;
        String name;
        final List<String> videoIds;
        final File jsonFile;
        final File coverFile;

        OfflinePlaylist(String id, String name, List<String> videoIds, File dir) {
            this.id = id;
            this.name = name;
            this.videoIds = videoIds;
            this.jsonFile = new File(dir, id + ".json");
            this.coverFile = new File(dir, id + ".jpg");
        }

        void save() {
            try {
                JSONArray arr = new JSONArray();
                for (String vId : videoIds) arr.put(vId);
                JSONObject obj = new JSONObject()
                        .put("id", id)
                        .put("name", name)
                        .put("videoIds", arr);
                OfflineStorage.writeText(jsonFile, obj.toString());
            } catch (Exception ex) {
                Logger.printException(() -> "Could not save playlist: " + id, ex);
            }
        }

        static OfflinePlaylist load(File jsonFile) {
            String fallbackId = jsonFile.getName().replace(".json", "");
            try {
                JSONObject obj = new JSONObject(OfflineStorage.readText(jsonFile));
                String id = obj.optString("id", fallbackId);
                String name = obj.optString("name", "Playlist");
                JSONArray arr = obj.optJSONArray("videoIds");
                List<String> ids = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String v = arr.optString(i, "");
                        if (!v.isEmpty()) ids.add(v);
                    }
                }
                return new OfflinePlaylist(id, name, ids, jsonFile.getParentFile());
            } catch (Exception ex) {
                Logger.printException(() -> "Could not load playlist: " + jsonFile, ex);
                return new OfflinePlaylist(fallbackId, "Playlist", new ArrayList<>(), jsonFile.getParentFile());
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        musicRoot = new File(getActivity().getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");
        playlistsDir = new File(musicRoot, "playlists");
        if (!playlistsDir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            playlistsDir.mkdirs();
        }

        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());

        root.addView(createHeader(), new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(getActivity());
        contentContainer = new LinearLayout(getActivity());
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(dp(10), 0, dp(10), dp(16));
        scroll.addView(contentContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        refreshDataAndRender();

        miniPlayer = createStockMiniPlayer();
        miniPlayer.setVisibility(View.GONE);
        root.addView(miniPlayer, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        OfflinePlaybackService.addListener(this);
    }

    @Override
    public void onStop() {
        OfflinePlaybackService.removeListener(this);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        artworkCache.evictAll();
        likedSongsButton = null;
        super.onDestroyView();
    }

    private void loadTracks() {
        tracks = new ArrayList<>();
        File[] files = musicRoot.listFiles(file -> file.isFile()
                && (file.getName().endsWith(".webm") || file.getName().endsWith(".m4a")));
        if (files == null) return;

        for (File file : files) {
            tracks.add(OfflineTrack.load(file));
        }
        tracks.sort(comparator());
    }

    private void loadPlaylists() {
        playlists = new ArrayList<>();
        File[] files = playlistsDir.listFiles(file -> file.isFile() && file.getName().endsWith(".json"));
        if (files == null) return;

        for (File file : files) {
            playlists.add(OfflinePlaylist.load(file));
        }
        playlists.sort(Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT)));

        if (activePlaylist != null) {
            OfflinePlaylist updated = null;
            for (OfflinePlaylist p : playlists) {
                if (p.id.equals(activePlaylist.id)) {
                    updated = p;
                    break;
                }
            }
            activePlaylist = updated;
        }
    }

    private void refreshDataAndRender() {
        loadTracks();
        loadPlaylists();
        renderContent();
    }

    private Comparator<OfflineTrack> comparator() {
        Comparator<OfflineTrack> byTitle =
                Comparator.comparing(track -> track.displayTitle().toLowerCase(Locale.ROOT));

        return switch (SortOrder.saved()) {
            case TITLE -> byTitle;
            case SIZE -> Comparator.comparingLong(
                    (OfflineTrack track) -> track.audioFile().length()).reversed();
            default -> Comparator.comparing(
                    (OfflineTrack track) -> track.displayArtist().toLowerCase(Locale.ROOT))
                    .thenComparing(byTitle);
        };
    }

    private boolean matchesQuery(OfflineTrack track) {
        if (query.isEmpty()) return true;
        return track.displayTitle().toLowerCase(Locale.ROOT).contains(query)
                || track.displayArtist().toLowerCase(Locale.ROOT).contains(query);
    }

    private boolean matchesPlaylistQuery(OfflinePlaylist playlist) {
        if (query.isEmpty()) return true;
        return playlist.name.toLowerCase(Locale.ROOT).contains(query);
    }

    private LinearLayout createHeader() {
        LinearLayout header = new LinearLayout(getActivity());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(8), dp(16), dp(6));

        // ---------- Liked Songs download chip ----------
        LinearLayout likedRow = new LinearLayout(getActivity());
        likedRow.setGravity(Gravity.CENTER_VERTICAL);
        likedRow.setPadding(0, 0, 0, dp(6));

        likedSongsButton = new TextView(getActivity());
        likedSongsButton.setText("♥  Download Liked Songs");
        likedSongsButton.setTextSize(14);
        likedSongsButton.setTextColor(foreground());
        likedSongsButton.setTypeface(Typeface.DEFAULT_BOLD);
        likedSongsButton.setGravity(Gravity.CENTER);
        likedSongsButton.setPadding(dp(18), dp(10), dp(18), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(artworkPlaceholder());
        likedSongsButton.setBackground(new RippleDrawable(
                ColorStateList.valueOf(rippleColor()), bg, bg));
        likedSongsButton.setOnClickListener(v -> onLikedSongsClicked());
        likedRow.addView(likedSongsButton, new LinearLayout.LayoutParams(-1, -2));

        header.addView(likedRow, new LinearLayout.LayoutParams(-1, -2));

        // ---------- Search bar ----------
        searchBar = CustomDialog.createSearchBar(getActivity(),
                str("morphe_music_downloads_search_hint"), text -> {
                    query = text.trim().toLowerCase(Locale.ROOT);
                    renderContent();
                });
        searchBar.setVisibility(View.GONE);
        searchBar.setAlpha(0f);
        header.addView(searchBar, new LinearLayout.LayoutParams(-1, 0));

        // ---------- Toolbar row ----------
        LinearLayout line = new LinearLayout(getActivity());
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(8), 0, 0);

        summary = text("", 13, secondary());
        line.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton addPlaylistBtn = createTextIconButton("+", "New playlist");
        addPlaylistBtn.setOnClickListener(v -> showCreatePlaylistDialog());
        line.addView(addPlaylistBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        viewToggleBtn = createTextIconButton(isGridView ? "☰" : "⊞", "Change view");
        viewToggleBtn.setOnClickListener(v -> {
            isGridView = !isGridView;
            updateViewToggleIcon();
            renderContent();
        });
        line.addView(viewToggleBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageButton search = icon(BaseSearchViewController.getSearchIconDrawable(),
                str("morphe_music_downloads_search_hint"));
        search.setOnClickListener(v -> toggleSearch());
        line.addView(search, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageButton sort = icon("yt_outline_experimental_sort_vd_theme_24",
                str("morphe_music_downloads_sort"));
        sort.setOnClickListener(v -> showSortMenu(sort));
        line.addView(sort, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageButton deleteAll = icon("yt_outline_experimental_trash_can_vd_theme_24",
                str("morphe_music_downloads_delete_all"));
        deleteAll.setOnClickListener(v -> confirmDeleteAll());
        line.addView(deleteAll, new LinearLayout.LayoutParams(dp(40), dp(40)));

        header.addView(line, new LinearLayout.LayoutParams(-1, -2));
        return header;
    }

    // ==================================================================
    // LIKED SONGS FLOW
    // ==================================================================

    private void onLikedSongsClicked() {
        if (getActivity() == null) return;

        if (!CookieBridge.hasCookies()) {
            promptForSignIn();
            return;
        }
        startLikedSongsDownload();
    }

    private void promptForSignIn() {
        if (getActivity() == null) return;

        // Optional hint if the device already has a Google account configured.
        final boolean hasAccount = CookieBridge.hasGoogleAccount(getActivity());
        final String message = hasAccount
                ? "Your Google account is already on this device.\n\n"
                  + "Liked Songs is a private playlist, so we need to confirm your YouTube Music "
                  + "sign-in once. Tap Continue to open the sign-in page — your cookies will "
                  + "stay on this device."
                : "Liked Songs is a private playlist and requires signing in to YouTube Music.\n\n"
                  + "Tap Continue to sign in. Your cookies will stay on this device.";

        new AlertDialog.Builder(getActivity())
                .setTitle("Sign in required")
                .setMessage(message)
                .setPositiveButton("Continue", (dialog, which) -> showCookieLoginDialog())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCookieLoginDialog() {
        if (getActivity() == null || getActivity().isFinishing()) return;
        CookieLoginDialog dialog = new CookieLoginDialog(getActivity(),
                new CookieLoginDialog.Callback() {
                    @Override
                    public void onSuccess() {
                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(() -> startLikedSongsDownload());
                    }

                    @Override
                    public void onCancelled() {
                        // User dismissed the dialog; nothing else to do.
                    }
                });
        dialog.show();
    }

    private void startLikedSongsDownload() {
        if (likedSongsButton == null) return;
        likedSongsButton.setEnabled(false);
        likedSongsButton.setText("♥  Downloading…");

        LocalDownloadManager.enqueueLikedSongs(new LocalDownloadManager.LikedSongsListener() {
            @Override
            public void onStarting() {
                // Manager shows its own toast.
            }

            @Override
            public void onFetching(int foundSoFar) {
                // Manager shows its own toast.
            }

            @Override
            public void onProgress(int current, int total) {
                // Batch notification is updated by the manager itself.
            }

            @Override
            public void onComplete(int successCount, int failedCount) {
                restoreLikedButton();
                refreshDataAndRender();
            }

            @Override
            public void onFailed(String reason) {
                restoreLikedButton();
                if (getActivity() == null) return;
                if ("not_authenticated".equals(reason)) {
                    promptForSignIn();
                } else if ("already_running".equals(reason)) {
                    Utils.showToastShort("A Liked Songs download is already running.");
                } else {
                    Utils.showToastShort("Liked Songs download failed: " + reason);
                }
            }
        });
    }

    private void restoreLikedButton() {
        if (likedSongsButton == null) return;
        likedSongsButton.setEnabled(true);
        likedSongsButton.setText("♥  Download Liked Songs");
    }

    // ==================================================================
    // HEADER HELPERS
    // ==================================================================

    private ImageButton createTextIconButton(String symbol, String description) {
        ImageButton btn = new ImageButton(getActivity());
        btn.setContentDescription(description);
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                null, new ShapeDrawable(new OvalShape())));
        Bitmap bmp = Bitmap.createBitmap(dp(24), dp(24), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(foreground());
        paint.setTextSize(dp(18));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
        float y = (dp(24) - fm.bottom - fm.top) / 2f;
        canvas.drawText(symbol, dp(12), y, paint);
        btn.setImageBitmap(bmp);
        return btn;
    }

    private void updateViewToggleIcon() {
        Bitmap bmp = Bitmap.createBitmap(dp(24), dp(24), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(foreground());
        paint.setTextSize(dp(18));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
        float y = (dp(24) - fm.bottom - fm.top) / 2f;
        canvas.drawText(isGridView ? "☰" : "⊞", dp(12), y, paint);
        viewToggleBtn.setImageBitmap(bmp);
    }

    // ==================================================================
    // RENDER
    // ==================================================================

    private void renderContent() {
        contentContainer.removeAllViews();
        rowViews.clear();
        displayedPaths = new ArrayList<>();

        long totalBytes = 0;
        for (OfflineTrack track : tracks) {
            totalBytes += track.audioFile().length();
        }
        summary.setText(str("morphe_music_downloads_summary",
                tracks.size(), formatSize(totalBytes)));

        if (activePlaylist != null) {
            renderActivePlaylistView(activePlaylist);
            markPlayingRow();
            return;
        }

        // 1. Playlists section
        List<OfflinePlaylist> matchingPlaylists = new ArrayList<>();
        for (OfflinePlaylist p : playlists) {
            if (matchesPlaylistQuery(p)) matchingPlaylists.add(p);
        }

        if (!matchingPlaylists.isEmpty()) {
            contentContainer.addView(sectionHeader("Playlists (" + matchingPlaylists.size() + ")"));
            if (isGridView) {
                renderPlaylistsGrid(matchingPlaylists);
            } else {
                for (OfflinePlaylist p : matchingPlaylists) {
                    contentContainer.addView(playlistLinearRow(p));
                }
            }
        }

        // 2. All songs section
        List<OfflineTrack> matchingTracks = new ArrayList<>();
        for (OfflineTrack track : tracks) {
            if (matchesQuery(track)) {
                matchingTracks.add(track);
                displayedPaths.add(track.audioFile().getAbsolutePath());
            }
        }

        contentContainer.addView(sectionHeader("All songs (" + matchingTracks.size() + ")"));

        if (matchingTracks.isEmpty()) {
            TextView empty = text(str(tracks.isEmpty()
                    ? "morphe_music_downloads_empty"
                    : "morphe_music_downloads_nothing_found"), 16, secondary());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(60), 0, 0);
            contentContainer.addView(empty);
            return;
        }

        if (isGridView) {
            renderTracksGrid(matchingTracks, null);
        } else {
            for (OfflineTrack track : matchingTracks) {
                contentContainer.addView(songLinearRow(track, null));
            }
        }

        markPlayingRow();
    }

    private void renderActivePlaylistView(OfflinePlaylist playlist) {
        LinearLayout topBar = new LinearLayout(getActivity());
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(4), dp(4), dp(4), dp(12));

        TextView backBtn = text("←  Back to library", 14, foreground());
        backBtn.setTypeface(Typeface.DEFAULT_BOLD);
        backBtn.setPadding(dp(10), dp(8), dp(14), dp(8));
        backBtn.setBackground(rowBackground(false));
        backBtn.setOnClickListener(v -> {
            activePlaylist = null;
            renderContent();
        });
        topBar.addView(backBtn);
        contentContainer.addView(topBar);

        // Hero card
        LinearLayout hero = new LinearLayout(getActivity());
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(8), dp(8), dp(8), dp(16));

        ImageView cover = artworkView(getPlaylistArtwork(playlist));
        cover.setOnClickListener(v -> pickPlaylistCover(playlist));
        hero.addView(cover, new LinearLayout.LayoutParams(dp(96), dp(96)));

        LinearLayout info = new LinearLayout(getActivity());
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(16), 0, dp(8), 0);

        TextView titleView = text(playlist.name, 20, foreground());
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);

        List<OfflineTrack> playlistTracks = resolvePlaylistTracks(playlist);
        TextView countView = text(
                playlistTracks.size() + " songs • Tap cover to change",
                13, secondary());

        LinearLayout actions = new LinearLayout(getActivity());
        actions.setPadding(0, dp(10), 0, 0);

        TextView addSongsBtn = createChipButton("+ Add songs",
                () -> showAddRemoveTracksDialog(playlist));
        TextView changeCoverBtn = createChipButton("Change cover",
                () -> showCoverOptionsMenu(cover, playlist));

        actions.addView(addSongsBtn);
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(-2, -2);
        coverLp.setMarginStart(dp(8));
        actions.addView(changeCoverBtn, coverLp);

        info.addView(titleView);
        info.addView(countView);
        info.addView(actions);
        hero.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton moreBtn = icon("yt_outline_experimental_overflow_vertical_vd_theme_24", playlist.name);
        moreBtn.setOnClickListener(v -> showPlaylistMenu(moreBtn, playlist));
        hero.addView(moreBtn, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        contentContainer.addView(hero);

        List<OfflineTrack> filtered = new ArrayList<>();
        for (OfflineTrack t : playlistTracks) {
            if (matchesQuery(t)) {
                filtered.add(t);
                displayedPaths.add(t.audioFile().getAbsolutePath());
            }
        }

        if (filtered.isEmpty()) {
            TextView empty = text("No songs in this playlist yet.", 15, secondary());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(48), 0, 0);
            contentContainer.addView(empty);
            return;
        }

        if (isGridView) {
            renderTracksGrid(filtered, playlist);
        } else {
            for (OfflineTrack track : filtered) {
                contentContainer.addView(songLinearRow(track, playlist));
            }
        }
    }

    private TextView createChipButton(String label, Runnable onClick) {
        TextView chip = text(label, 12, foreground());
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(artworkPlaceholder());
        chip.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()), bg, bg));
        chip.setOnClickListener(v -> onClick.run());
        return chip;
    }

    private TextView sectionHeader(String title) {
        TextView header = text(title, 15, foreground());
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(dp(8), dp(14), dp(8), dp(6));
        return header;
    }

    private void renderPlaylistsGrid(List<OfflinePlaylist> list) {
        for (int i = 0; i < list.size(); i += 2) {
            LinearLayout row = new LinearLayout(getActivity());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setWeightSum(2f);

            LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, -2, 1f);
            leftLp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(playlistGridCard(list.get(i)), leftLp);

            LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, -2, 1f);
            rightLp.setMargins(dp(4), dp(4), dp(4), dp(4));
            if (i + 1 < list.size()) {
                row.addView(playlistGridCard(list.get(i + 1)), rightLp);
            } else {
                row.addView(new View(getActivity()), rightLp);
            }
            contentContainer.addView(row);
        }
    }

    private void renderTracksGrid(List<OfflineTrack> list, @Nullable OfflinePlaylist fromPlaylist) {
        for (int i = 0; i < list.size(); i += 2) {
            LinearLayout row = new LinearLayout(getActivity());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setWeightSum(2f);

            LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, -2, 1f);
            leftLp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(songGridCard(list.get(i), fromPlaylist), leftLp);

            LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, -2, 1f);
            rightLp.setMargins(dp(4), dp(4), dp(4), dp(4));
            if (i + 1 < list.size()) {
                row.addView(songGridCard(list.get(i + 1), fromPlaylist), rightLp);
            } else {
                row.addView(new View(getActivity()), rightLp);
            }
            contentContainer.addView(row);
        }
    }

    private View playlistGridCard(OfflinePlaylist playlist) {
        LinearLayout card = new LinearLayout(getActivity());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(cardBackground(false));

        ImageView cover = artworkView(getPlaylistArtwork(playlist));
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(-1, dp(140));
        card.addView(cover, coverLp);

        LinearLayout bottomRow = new LinearLayout(getActivity());
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        bottomRow.setPadding(0, dp(8), 0, 0);

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);

        TextView name = text(playlist.name, 15, foreground());
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);

        int count = resolvePlaylistTracks(playlist).size();
        TextView sub = text(count + " songs", 12, secondary());
        sub.setSingleLine(true);

        labels.addView(name);
        labels.addView(sub);
        bottomRow.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageButton menu = icon("yt_outline_experimental_overflow_vertical_vd_theme_24", playlist.name);
        menu.setOnClickListener(v -> showPlaylistMenu(menu, playlist));
        bottomRow.addView(menu, new LinearLayout.LayoutParams(dp(36), dp(36)));

        card.addView(bottomRow);
        card.setOnClickListener(v -> {
            activePlaylist = playlist;
            renderContent();
        });
        return card;
    }

    private View playlistLinearRow(OfflinePlaylist playlist) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        row.setBackground(rowBackground(false));

        row.addView(artworkView(getPlaylistArtwork(playlist)),
                new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, dp(8), 0);

        TextView name = text(playlist.name, 16, foreground());
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);

        int count = resolvePlaylistTracks(playlist).size();
        TextView detail = text("Playlist • " + count + " songs", 13, secondary());
        detail.setSingleLine(true);

        labels.addView(name);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton menu = icon("yt_outline_experimental_overflow_vertical_vd_theme_24", playlist.name);
        menu.setOnClickListener(v -> showPlaylistMenu(menu, playlist));
        row.addView(menu, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        row.setOnClickListener(v -> {
            activePlaylist = playlist;
            renderContent();
        });
        return row;
    }

    private View songGridCard(OfflineTrack track, @Nullable OfflinePlaylist fromPlaylist) {
        LinearLayout card = new LinearLayout(getActivity());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        rowViews.add(card);

        ImageView cover = artworkView(getCachedTrackArtwork(track));
        card.addView(cover, new LinearLayout.LayoutParams(-1, dp(140)));

        LinearLayout bottomRow = new LinearLayout(getActivity());
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        bottomRow.setPadding(0, dp(8), 0, 0);

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);

        TextView name = text(track.displayTitle(), 14, foreground());
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);

        TextView sub = text(track.displayArtist(), 12, secondary());
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);

        labels.addView(name);
        labels.addView(sub);
        bottomRow.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageButton menu = icon("yt_outline_experimental_overflow_vertical_vd_theme_24",
                str("morphe_music_downloads_actions_for", track.displayTitle()));
        menu.setOnClickListener(v -> showTrackMenu(menu, track, fromPlaylist));
        bottomRow.addView(menu, new LinearLayout.LayoutParams(dp(36), dp(36)));

        card.addView(bottomRow);
        card.setOnClickListener(v -> play(track));
        return card;
    }

    private View songLinearRow(OfflineTrack track, @Nullable OfflinePlaylist fromPlaylist) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        rowViews.add(row);
        row.addView(artworkView(getCachedTrackArtwork(track)),
                new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, dp(8), 0);

        TextView name = text(track.displayTitle(), 16, foreground());
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);

        TextView detail = text(str("morphe_music_downloads_track_subtitle",
                track.displayArtist(), formatDuration(track.durationSeconds()),
                formatSize(track.audioFile().length())), 13, secondary());
        detail.setSingleLine(true);

        labels.addView(name);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton menu = icon("yt_outline_experimental_overflow_vertical_vd_theme_24",
                str("morphe_music_downloads_actions_for", track.displayTitle()));
        menu.setOnClickListener(v -> showTrackMenu(menu, track, fromPlaylist));
        row.addView(menu, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        row.setOnClickListener(v -> play(track));
        return row;
    }

    @Nullable
    private Bitmap getCachedTrackArtwork(OfflineTrack track) {
        String key = "track_" + track.videoId();
        Bitmap cached = artworkCache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        Bitmap decoded = track.artwork(ROW_ARTWORK_PIXELS);
        if (decoded != null) artworkCache.put(key, decoded);
        return decoded;
    }

    @Nullable
    private Bitmap getPlaylistArtwork(OfflinePlaylist playlist) {
        String key = "pl_" + playlist.id;
        Bitmap cached = artworkCache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        if (playlist.coverFile.isFile()) {
            Bitmap custom = OfflineStorage.decodeArtwork(playlist.coverFile, ROW_ARTWORK_PIXELS);
            if (custom != null) {
                artworkCache.put(key, custom);
                return custom;
            }
        }

        List<OfflineTrack> listTracks = resolvePlaylistTracks(playlist);
        List<Bitmap> bitmaps = new ArrayList<>();
        for (OfflineTrack t : listTracks) {
            Bitmap bmp = getCachedTrackArtwork(t);
            if (bmp != null) {
                bitmaps.add(bmp);
                if (bitmaps.size() == 4) break;
            }
        }

        Bitmap collage = OfflineStorage.createCollage(bitmaps, ROW_ARTWORK_PIXELS);
        if (collage != null) artworkCache.put(key, collage);
        return collage;
    }

    private void invalidatePlaylistArtwork(OfflinePlaylist playlist) {
        artworkCache.remove("pl_" + playlist.id);
    }

    private List<OfflineTrack> resolvePlaylistTracks(OfflinePlaylist playlist) {
        List<OfflineTrack> resolved = new ArrayList<>();
        for (String vId : playlist.videoIds) {
            for (OfflineTrack t : tracks) {
                if (t.videoId().equals(vId)) {
                    resolved.add(t);
                    break;
                }
            }
        }
        return resolved;
    }

    // ==================================================================
    // DIALOGS
    // ==================================================================

    private void showCreatePlaylistDialog() {
        EditText input = new EditText(getActivity());
        input.setHint("Playlist name");
        input.setSingleLine(true);
        input.setTextColor(foreground());
        input.setHintTextColor(secondary());

        CustomDialog.create(getActivity(), "New playlist", null, input,
                "Create", () -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    String id = UUID.randomUUID().toString().substring(0, 8);
                    OfflinePlaylist pl = new OfflinePlaylist(id, name, new ArrayList<>(), playlistsDir);
                    pl.save();
                    refreshDataAndRender();
                    showAddRemoveTracksDialog(pl);
                }, () -> {}, null, null, false).first.show();
    }

    private void showRenamePlaylistDialog(OfflinePlaylist playlist) {
        EditText input = new EditText(getActivity());
        input.setText(playlist.name);
        input.setSingleLine(true);
        input.setTextColor(foreground());

        CustomDialog.create(getActivity(), "Rename playlist", null, input,
                "Save", () -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) return;
                    playlist.name = newName;
                    playlist.save();
                    refreshDataAndRender();
                }, () -> {}, null, null, false).first.show();
    }

    private void showAddRemoveTracksDialog(OfflinePlaylist playlist) {
        if (tracks.isEmpty()) {
            Utils.showToastShort(str("morphe_music_downloads_empty"));
            return;
        }

        ScrollView scroll = new ScrollView(getActivity());
        LinearLayout listLayout = new LinearLayout(getActivity());
        listLayout.setOrientation(LinearLayout.VERTICAL);
        listLayout.setPadding(dp(16), dp(8), dp(16), dp(8));

        List<CheckBox> checkBoxes = new ArrayList<>();
        for (OfflineTrack track : tracks) {
            CheckBox cb = new CheckBox(getActivity());
            cb.setText(track.displayTitle() + " - " + track.displayArtist());
            cb.setTextColor(foreground());
            cb.setChecked(playlist.videoIds.contains(track.videoId()));
            cb.setTag(track.videoId());
            cb.setPadding(dp(8), dp(10), dp(8), dp(10));
            checkBoxes.add(cb);
            listLayout.addView(cb);
        }
        scroll.addView(listLayout);

        Pair<android.app.Dialog, LinearLayout> dialogPair = CustomDialog.create(
                getActivity(), playlist.name + " - Select songs", null, null,
                "Save", () -> {
                    playlist.videoIds.clear();
                    for (CheckBox cb : checkBoxes) {
                        if (cb.isChecked()) {
                            playlist.videoIds.add((String) cb.getTag());
                        }
                    }
                    playlist.save();
                    invalidatePlaylistArtwork(playlist);
                    refreshDataAndRender();
                }, () -> {}, null, null, false);

        dialogPair.second.addView(scroll, 1, new LinearLayout.LayoutParams(-1, dp(320)));
        dialogPair.first.show();
    }

    private void showAddTrackToPlaylistDialog(OfflineTrack track) {
        if (playlists.isEmpty()) {
            showCreatePlaylistDialog();
            return;
        }

        String[] names = new String[playlists.size()];
        for (int i = 0; i < playlists.size(); i++) {
            names[i] = playlists.get(i).name;
        }

        new AlertDialog.Builder(getActivity())
                .setTitle("Add to playlist")
                .setItems(names, (dialog, which) -> {
                    OfflinePlaylist target = playlists.get(which);
                    if (!target.videoIds.contains(track.videoId())) {
                        target.videoIds.add(track.videoId());
                        target.save();
                        invalidatePlaylistArtwork(target);
                        renderContent();
                        Utils.showToastShort("Added to " + target.name);
                    } else {
                        Utils.showToastShort("Song already in playlist");
                    }
                })
                .show();
    }

    private void pickPlaylistCover(OfflinePlaylist playlist) {
        pendingCoverPlaylist = playlist;
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, "Choose playlist cover"),
                    REQUEST_PICK_COVER);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not launch image picker", ex);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_PICK_COVER || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        OfflinePlaylist target = pendingCoverPlaylist;
        pendingCoverPlaylist = null;
        if (uri == null || target == null) return;

        try (InputStream stream = getActivity().getContentResolver().openInputStream(uri)) {
            if (stream == null) return;
            Bitmap cropped = OfflineStorage.decodeStreamAndCropSquare(stream, CUSTOM_COVER_PIXELS);
            if (cropped != null) {
                OfflineStorage.writeJpeg(target.coverFile, cropped);
                invalidatePlaylistArtwork(target);
                renderContent();
                Utils.showToastShort("Playlist cover updated");
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to set custom playlist cover", ex);
        }
    }

    private void showCoverOptionsMenu(View anchor, OfflinePlaylist playlist) {
        String[] items = playlist.coverFile.isFile()
                ? new String[]{"Choose cover from gallery", "Reset to default cover"}
                : new String[]{"Choose cover from gallery"};

        showMenu(anchor, items, -1, pos -> {
            if (pos == 0) {
                pickPlaylistCover(playlist);
            } else if (pos == 1) {
                OfflineStorage.delete(playlist.coverFile);
                invalidatePlaylistArtwork(playlist);
                renderContent();
            }
        });
    }

    private void showPlaylistMenu(View anchor, OfflinePlaylist playlist) {
        String[] items = {
                "Play playlist",
                "Add / remove songs",
                "Change cover",
                "Rename",
                "Delete playlist"
        };
        showMenu(anchor, items, -1, position -> {
            switch (position) {
                case 0 -> {
                    List<OfflineTrack> listTracks = resolvePlaylistTracks(playlist);
                    if (!listTracks.isEmpty()) {
                        displayedPaths.clear();
                        for (OfflineTrack t : listTracks) {
                            displayedPaths.add(t.audioFile().getAbsolutePath());
                        }
                        play(listTracks.get(0));
                    }
                }
                case 1 -> showAddRemoveTracksDialog(playlist);
                case 2 -> showCoverOptionsMenu(anchor, playlist);
                case 3 -> showRenamePlaylistDialog(playlist);
                case 4 -> confirm("Delete playlist",
                        playlist.name + " will be deleted. (Songs stay on the device)",
                        () -> {
                            OfflineStorage.delete(playlist.jsonFile);
                            OfflineStorage.delete(playlist.coverFile);
                            invalidatePlaylistArtwork(playlist);
                            if (activePlaylist != null && activePlaylist.id.equals(playlist.id)) {
                                activePlaylist = null;
                            }
                            refreshDataAndRender();
                        });
            }
        });
    }

    private void toggleSearch() {
        final boolean opening = searchBar.getVisibility() != View.VISIBLE;
        InputMethodManager keyboard = (InputMethodManager)
                getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);

        if (opening) {
            searchBar.setVisibility(View.VISIBLE);
            slideSearchBar(dp(SEARCH_BAR_HEIGHT_DP));
            searchBar.animate().alpha(1f).setDuration(SEARCH_ANIMATION_MILLISECONDS).start();

            searchBar.requestFocus();
            if (keyboard != null) keyboard.showSoftInput(searchBar, InputMethodManager.SHOW_IMPLICIT);
            return;
        }

        if (keyboard != null) keyboard.hideSoftInputFromWindow(searchBar.getWindowToken(), 0);
        searchBar.setText("");
        searchBar.clearFocus();

        slideSearchBar(0);
        searchBar.animate().alpha(0f).setDuration(SEARCH_ANIMATION_MILLISECONDS)
                .withEndAction(() -> searchBar.setVisibility(View.GONE)).start();
    }

    private void slideSearchBar(int target) {
        ValueAnimator animator = ValueAnimator.ofInt(searchBar.getHeight(), target);
        animator.setDuration(SEARCH_ANIMATION_MILLISECONDS);
        animator.addUpdateListener(value -> {
            searchBar.getLayoutParams().height = (int) value.getAnimatedValue();
            searchBar.requestLayout();
        });
        animator.start();
    }

    private void showSortMenu(View anchor) {
        String[] items = {
                str("morphe_music_downloads_sort_artist"),
                str("morphe_music_downloads_sort_title"),
                str("morphe_music_downloads_sort_size"),
        };
        showMenu(anchor, items, SortOrder.saved().ordinal(), position -> {
            Settings.DOWNLOADS_SORT.save(SortOrder.values()[position].name());
            refreshDataAndRender();
        });
    }

    @Nullable
    private OfflineTrack findByPath(String path) {
        if (path.isEmpty()) return null;
        for (OfflineTrack track : tracks) {
            if (track.audioFile().getAbsolutePath().equals(path)) return track;
        }
        return null;
    }

    private void markPlayingRow() {
        int playingIndex = displayedPaths.indexOf(playingPath);
        for (int i = 0; i < rowViews.size(); i++) {
            boolean isPlaying = (i == playingIndex);
            rowViews.get(i).setBackground(isGridView ? cardBackground(isPlaying) : rowBackground(isPlaying));
        }
    }

    private Drawable rowBackground(boolean playing) {
        Drawable card = playing
                ? CustomDialog.createRoundedBackground(ROW_CORNER_DP, Utils.adjustColorBrightness(
                        ThemeUtils.getAppBackgroundColor(), PLAYING_ROW_BRIGHTNESS))
                : null;
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()), card,
                CustomDialog.createRoundedBackground(ROW_CORNER_DP, Color.WHITE));
    }

    private Drawable cardBackground(boolean playing) {
        int baseColor = Utils.adjustColorBrightness(
                ThemeUtils.getAppBackgroundColor(), playing ? PLAYING_ROW_BRIGHTNESS : 1.12f);
        Drawable card = CustomDialog.createRoundedBackground(CARD_CORNER_DP, baseColor);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()), card,
                CustomDialog.createRoundedBackground(CARD_CORNER_DP, Color.WHITE));
    }

    private static int rippleColor() {
        final int foreground = foreground();
        return Color.argb(60, Color.red(foreground),
                Color.green(foreground), Color.blue(foreground));
    }

    private LinearLayout createStockMiniPlayer() {
        LinearLayout outer = new LinearLayout(getActivity());
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(ThemeUtils.getDialogBackgroundColor());

        View divider = new View(getActivity());
        divider.setBackgroundColor(rippleColor());
        outer.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        outer.setPadding(0, 0, 0, dp(12));

        LinearLayout line = new LinearLayout(getActivity());
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(16), 0, 0, 0);

        miniArtwork = artworkView(null);
        line.addView(miniArtwork, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(16), 0, dp(4), 0);

        miniTitle = text("", 14, foreground());
        miniTitle.setSingleLine(true);
        miniTitle.setTypeface(Typeface.DEFAULT_BOLD);

        miniArtist = text("", 14, secondary());
        miniArtist.setSingleLine(true);

        labels.addView(miniTitle);
        labels.addView(miniArtist);
        line.addView(labels, new LinearLayout.LayoutParams(0, dp(56), 1));

        miniPrevious = icon("yt_fill_experimental_skip_previous_vd_theme_24",
                str("morphe_music_downloads_previous"));
        miniPrevious.setOnClickListener(v -> OfflinePlaybackService.skipPrevious(getActivity()));
        line.addView(miniPrevious, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        miniPlay = icon("yt_fill_experimental_play_vd_theme_24",
                str("morphe_music_downloads_play_pause"));
        miniPlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        miniPlay.setPadding(dp(12), dp(12), dp(12), dp(12));
        miniPlay.setOnClickListener(v -> OfflinePlaybackService.toggle(getActivity()));
        line.addView(miniPlay, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        miniNext = icon("yt_fill_experimental_skip_next_vd_theme_24",
                str("morphe_music_downloads_next"));
        miniNext.setOnClickListener(v -> OfflinePlaybackService.skipNext(getActivity()));
        line.addView(miniNext, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        outer.addView(line, new LinearLayout.LayoutParams(-1, dp(60)));
        outer.addView(createSeekBar(), new LinearLayout.LayoutParams(-1, dp(SEEK_BAR_TOUCH_DP)));
        return outer;
    }

    private SeekBar createSeekBar() {
        miniSeek = new SeekBar(getActivity());
        miniSeek.setPadding(dp(16), 0, dp(16), 0);
        miniSeek.setProgressDrawable(createSeekBarDrawable());
        miniSeek.setThumb(null);
        miniSeek.setThumbOffset(0);
        miniSeek.setBackground(null);
        miniSeek.setSplitTrack(false);
        miniSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
                bar.setThumb(createThumb());
                bar.setThumbOffset(dp(SEEK_THUMB_DP) / 2);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                bar.setThumb(null);
                OfflinePlaybackService.seekTo(bar.getProgress());
            }

            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
            }
        });
        return miniSeek;
    }

    private Drawable createThumb() {
        ShapeDrawable thumb = new ShapeDrawable(new OvalShape());
        thumb.setIntrinsicWidth(dp(SEEK_THUMB_DP));
        thumb.setIntrinsicHeight(dp(SEEK_THUMB_DP));
        thumb.getPaint().setColor(ThemeUtils.getAppForegroundColor());
        return thumb;
    }

    private Drawable createSeekBarDrawable() {
        final int foreground = ThemeUtils.getAppForegroundColor();
        Drawable track = roundedBar(Utils.adjustColorBrightness(foreground, 0.3f));
        Drawable played = roundedBar(foreground);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                track, new ClipDrawable(played, Gravity.START, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);

        final int inset = (dp(SEEK_BAR_TOUCH_DP) - dp(SEEK_BAR_HEIGHT_DP)) / 2;
        layers.setLayerInset(0, 0, inset, 0, inset);
        layers.setLayerInset(1, 0, inset, 0, inset);
        return layers;
    }

    private Drawable roundedBar(int color) {
        return CustomDialog.createRoundedBackground(SEEK_BAR_HEIGHT_DP, color);
    }

    private void showTrackMenu(View anchor, OfflineTrack track, @Nullable OfflinePlaylist fromPlaylist) {
        String[] items = fromPlaylist != null
                ? new String[]{
                        str("morphe_music_downloads_play"),
                        "Add to playlist",
                        "Remove from playlist",
                        str("morphe_music_downloads_delete_track")
                }
                : new String[]{
                        str("morphe_music_downloads_play"),
                        "Add to playlist",
                        str("morphe_music_downloads_delete_track")
                };

        showMenu(anchor, items, -1, position -> {
            if (position == 0) {
                play(track);
            } else if (position == 1) {
                showAddTrackToPlaylistDialog(track);
            } else if (fromPlaylist != null && position == 2) {
                fromPlaylist.videoIds.remove(track.videoId());
                fromPlaylist.save();
                invalidatePlaylistArtwork(fromPlaylist);
                renderContent();
            } else {
                confirmDelete(track);
            }
        });
    }

    private void showMenu(View anchor, String[] items, int selected, IntConsumer onPick) {
        ListPopupWindow popup = new ListPopupWindow(getActivity());
        popup.setAnchorView(anchor);
        popup.setDropDownGravity(Gravity.END);
        popup.setWidth(dp(MENU_WIDTH_DP));
        popup.setModal(true);
        popup.setBackgroundDrawable(menuBackground());
        popup.setListSelector(new RippleDrawable(ColorStateList.valueOf(rippleColor()), null,
                CustomDialog.createRoundedBackground(MENU_CORNER_DP, Color.WHITE)));
        popup.setAdapter(new ArrayAdapter<>(getActivity(), 0, items) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = text(getItem(position), 15,
                        selected < 0 || position == selected ? foreground() : secondary());
                view.setPadding(dp(20), dp(14), dp(20), dp(14));
                return view;
            }
        });
        popup.setOnItemClickListener((parent, view, position, id) -> {
            popup.dismiss();
            onPick.accept(position);
        });
        popup.show();
    }

    private Drawable menuBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(ThemeUtils.getDialogBackgroundColor());
        background.setCornerRadius(dp(MENU_CORNER_DP));
        background.setStroke(dp(1), rippleColor());
        return background;
    }

    private void confirmDelete(OfflineTrack track) {
        confirm(str("morphe_music_downloads_delete_track_title"),
                str("morphe_music_downloads_delete_track_message", track.displayTitle()),
                () -> deleteTrack(track));
    }

    private void confirmDeleteAll() {
        if (tracks.isEmpty()) return;
        confirm(str("morphe_music_downloads_delete_all_title"),
                str("morphe_music_downloads_delete_all_message"), this::deleteAll);
    }

    private void confirm(String title, String message, Runnable onConfirm) {
        CustomDialog.create(getActivity(), title, message, null,
                str("morphe_music_downloads_delete"), onConfirm, () -> {
                }, null, null, false).first.show();
    }

    private void deleteAll() {
        for (OfflineTrack track : new ArrayList<>(tracks)) {
            OfflineStorage.delete(track.audioFile());
            deleteSidecars(track.videoId());
        }
        for (OfflinePlaylist p : playlists) {
            p.videoIds.clear();
            p.save();
        }
        artworkCache.evictAll();
        refreshDataAndRender();
        Utils.showToastShort(str("morphe_music_downloads_all_deleted"));
    }

    private void deleteSidecars(String videoId) {
        OfflineStorage.delete(new File(musicRoot, videoId + ".json"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".jpg"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".webm.part"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".m4a.part"));
    }

    private void deleteTrack(OfflineTrack track) {
        final boolean deleted = track.audioFile().delete();
        deleteSidecars(track.videoId());

        if (!deleted) {
            Utils.showToastShort(str("morphe_music_downloads_track_delete_failed"));
            return;
        }
        artworkCache.remove("track_" + track.videoId());
        for (OfflinePlaylist p : playlists) {
            if (p.videoIds.remove(track.videoId())) {
                p.save();
                invalidatePlaylistArtwork(p);
            }
        }
        refreshDataAndRender();
        Utils.showToastShort(str("morphe_music_downloads_track_deleted"));
    }

    private void play(OfflineTrack track) {
        try {
            queuePaths = new ArrayList<>(displayedPaths);
            if (queuePaths.isEmpty()) {
                queuePaths.add(track.audioFile().getAbsolutePath());
            }
            final int queueIndex = Math.max(0, queuePaths.indexOf(track.audioFile().getAbsolutePath()));

            Intent intent = new Intent(getActivity(), OfflinePlaybackService.class)
                    .setAction(OfflinePlaybackService.ACTION_PLAY_FILE)
                    .putExtra(OfflinePlaybackService.EXTRA_PATH, track.audioFile().getAbsolutePath())
                    .putExtra(OfflinePlaybackService.EXTRA_TITLE, track.displayTitle())
                    .putExtra(OfflinePlaybackService.EXTRA_ARTIST, track.displayArtist())
                    .putExtra(OfflinePlaybackService.EXTRA_ARTWORK_PATH,
                            track.artworkFile().getAbsolutePath())
                    .putStringArrayListExtra(OfflinePlaybackService.EXTRA_QUEUE, new ArrayList<>(queuePaths))
                    .putExtra(OfflinePlaybackService.EXTRA_QUEUE_INDEX, queueIndex);
            getActivity().startForegroundService(intent);

            applyTrackVisuals(track);
        } catch (Exception ex) {
            Logger.printException(() -> "Offline playback failed: " + track.audioFile(), ex);
            Utils.showToastShort(str("morphe_music_downloads_play_failed"));
        }
    }

    @Override
    public void onPlaybackChanged(String path, String title, boolean playing,
                                  int position, int duration) {
        if (getActivity() == null) return;

        getActivity().runOnUiThread(() -> {
            if (miniPlayer == null) return;
            final boolean wasVisible = miniPlayer.getVisibility() == View.VISIBLE;
            miniPlayer.setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);

            if (!path.equals(playingPath)) {
                playingPath = path;
                OfflineTrack track = findByPath(path);
                if (track != null) applyTrackVisuals(track);
                else miniTitle.setText(title);

                int index = queuePaths.indexOf(path);
                setButtonEnabled(miniPrevious, index > 0);
                setButtonEnabled(miniNext, index >= 0 && index + 1 < queuePaths.size());
                markPlayingRow();
            }

            updatePlayIcon(playing, wasVisible && playing != showingPause);
            miniSeek.setMax(Math.max(1, duration));
            if (!userSeeking) miniSeek.setProgress(position, true);
        });
    }

    private void updatePlayIcon(boolean playing, boolean morph) {
        showingPause = playing;
        if (morph && startIconMorph(playing)) return;

        miniPlay.setImageDrawable(ResourceUtils.getDrawable(playing
                ? "yt_fill_experimental_pause_vd_theme_24"
                : "yt_fill_experimental_play_vd_theme_24"));
    }

    private boolean startIconMorph(boolean playing) {
        String name = playing
                ? "player_play_pause_vector_transition"
                : "player_pause_play_vector_transition";

        int identifier = ResourceUtils.getDrawableIdentifier(name + "_delhi");
        if (identifier == 0) identifier = ResourceUtils.getDrawableIdentifier(name);

        Activity activity = getActivity();
        if (identifier == 0 || activity == null) return false;

        Drawable drawable = activity.getDrawable(identifier);
        if (!(drawable instanceof AnimatedVectorDrawable morph)) return false;

        miniPlay.setImageDrawable(morph);
        morph.start();
        return true;
    }

    private void applyTrackVisuals(OfflineTrack track) {
        miniTitle.setText(track.displayTitle());
        miniArtist.setText(track.displayArtist());

        Bitmap bitmap = getCachedTrackArtwork(track);
        if (bitmap == null) return;

        miniArtwork.clearColorFilter();
        miniArtwork.setPadding(0, 0, 0, 0);
        miniArtwork.setImageBitmap(bitmap);
        miniArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
    }

    private void setButtonEnabled(ImageButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .35f);
    }

    private ImageView artworkView(@Nullable Bitmap bitmap) {
        ImageView view = new ImageView(getActivity());
        view.setBackgroundColor(artworkPlaceholder());
        roundCorners(view);

        if (bitmap != null) {
            view.setImageBitmap(bitmap);
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            view.setImageDrawable(
                    ResourceUtils.getDrawable("yt_fill_experimental_play_circle_vd_theme_24"));
            view.setColorFilter(foreground());
            view.setPadding(dp(16), dp(16), dp(16), dp(16));
        }
        return view;
    }

    private void roundCorners(View view) {
        final float radius = dp(ARTWORK_CORNER_DP);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View outlined, Outline outline) {
                outline.setRoundRect(0, 0, outlined.getWidth(), outlined.getHeight(), radius);
            }
        });
        view.setClipToOutline(true);
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(getActivity());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private ImageButton icon(String drawable, String description) {
        ImageButton button = icon(ResourceUtils.getDrawable(drawable), description);
        button.setColorFilter(foreground());
        return button;
    }

    private ImageButton icon(Drawable drawable, String description) {
        ImageButton button = new ImageButton(getActivity());
        button.setImageDrawable(drawable);
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));

        button.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                null, new ShapeDrawable(new OvalShape())));
        return button;
    }

    private static int foreground() {
        return ThemeUtils.getAppForegroundColor();
    }

    private static int secondary() {
        return Utils.adjustColorBrightness(foreground(), SECONDARY_DIM);
    }

    private static int background() {
        return ThemeUtils.getAppBackgroundColor();
    }

    private static int artworkPlaceholder() {
        return Utils.adjustColorBrightness(foreground(), 0.2f);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private static String formatDuration(int seconds) {
        if (seconds <= 0) return "";
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private static String formatSize(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
    }
}