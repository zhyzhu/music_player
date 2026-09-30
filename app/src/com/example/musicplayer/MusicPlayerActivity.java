package com.example.musicplayer;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TabHost;
import android.widget.TextView;

/**
 * Main screen for Android 2.2 (API level 8): two tabs over the same library.
 *
 * - 列表: every song, in the order the library returned them
 * - 专辑: the same songs grouped by album, with headers
 *
 * The now-playing bar along the bottom is deliberately thin; tapping it opens
 * {@link PlayerActivity}, where the transport controls and cover live.
 *
 * Playback lives in {@link TrackPlayer}, a foreground service, so audio keeps
 * playing when this activity is not in the foreground.
 *
 * Deliberately written in Java 6 syntax and limited to API 8 APIs.
 */
public class MusicPlayerActivity extends Activity
        implements View.OnClickListener,
                   TrackPlayer.Listener {

    /** File extensions treated as audio during a folder scan. */
    public static final String[] AUDIO_EXT = {
        ".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac", ".mid", ".midi", ".amr", ".3gp", ".mp4"
    };

    /** Folder scanned when nothing is indexed yet; no need to ask the user. */
    private static final String DEFAULT_FOLDER = "Music";

    private static final int MSG_FILTER = 2;
    /** Keystrokes arrive faster than a filter needs to run. */
    private static final long FILTER_DELAY_MS = 250L;

    /** Every track found by the last scan, in library order. */
    private final List<Track> allTracks = new ArrayList<Track>();

    /** One entry of the segmented album list. */
    private static final class Row {
        static final int TYPE_GROUP = 0;
        static final int TYPE_TRACK = 1;

        final int type;
        final Track track;
        final String label;
        final int count;

        private Row(int type, Track track, String label, int count) {
            this.type = type;
            this.track = track;
            this.label = label;
            this.count = count;
        }

        static Row group(String label, int count) {
            return new Row(TYPE_GROUP, null, label, count);
        }

        static Row track(Track track) {
            return new Row(TYPE_TRACK, track, null, 0);
        }

        boolean isGroup() {
            return type == TYPE_GROUP;
        }
    }

    /** Bound playback service, or null until the connection completes. */
    private TrackPlayer player;
    /** True once playback has been asked for; from then on the queue is fixed. */
    private boolean playbackStarted;

    private EditText searchEdit;
    private Button clearButton;
    private ImageView playButton;
    private ImageView artView;
    private TextView listInfo;
    private TextView nowText;
    private TextView nowArtist;
    private TextView emptyText;
    private View nowBar;
    private View playHitArea;
    private TextView tabSongs;
    private TextView tabAlbums;
    private ListView songsList;
    private ListView albumsList;

    /** Which list is showing; drives both visibility and the tab highlight. */
    private boolean showingAlbums;

    /** Flat view: matching songs in library order. */
    private final List<Track> songsResults = new ArrayList<Track>();
    /** Album view: matching songs in grouped order. */
    private final List<Track> albumResults = new ArrayList<Track>();
    private final List<Row> songRows = new ArrayList<Row>();
    private final List<Row> albumRows = new ArrayList<Row>();

    private TrackAdapter songsAdapter;
    private TrackAdapter albumsAdapter;

    private int scanGeneration;
    /** Guards against a slow cover decode overwriting a newer one. */
    private int artToken;

    private final Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == MSG_FILTER) {
                updateFilter();
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            player = ((TrackPlayer.LocalBinder) service).getService();
            player.setListener(MusicPlayerActivity.this);
            playbackStarted = player.isPrepared();

            // Never clobber the service's queue here. When this activity is
            // recreated the bind callback runs before the scan finishes, so
            // pushing the local list would hand the service an empty queue,
            // which releases the player and stops playback.
            List<Track> serviceQueue = player.getQueueSnapshot();
            if (!serviceQueue.isEmpty()) {
                allTracks.clear();
                allTracks.addAll(serviceQueue);
            } else if (!songsResults.isEmpty()) {
                player.setQueue(new ArrayList<Track>(songsResults));
            }
            updateFilter();
            onPlayerStateChanged();
        }

        public void onServiceDisconnected(ComponentName name) {
            player = null;
            onPlayerStateChanged();
        }
    };

    private final TextWatcher searchWatcher = new TextWatcher() {
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        public void afterTextChanged(Editable s) {
            if (clearButton != null) {
                clearButton.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
            // Debounce: filtering on every keystroke is wasted work on a long list.
            handler.removeMessages(MSG_FILTER);
            handler.sendEmptyMessageDelayed(MSG_FILTER, FILTER_DELAY_MS);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        searchEdit = (EditText) findViewById(R.id.search);
        clearButton = (Button) findViewById(R.id.btn_clear);
        Button scanButton = (Button) findViewById(R.id.btn_scan);
        listInfo = (TextView) findViewById(R.id.list_info);
        emptyText = (TextView) findViewById(R.id.empty);
        artView = (ImageView) findViewById(R.id.art);
        nowText = (TextView) findViewById(R.id.now);
        nowArtist = (TextView) findViewById(R.id.now_artist);
        nowBar = findViewById(R.id.now_bar);
        playButton = (ImageView) findViewById(R.id.btn_play);

        scanButton.setOnClickListener(this);
        clearButton.setOnClickListener(this);
        searchEdit.addTextChangedListener(searchWatcher);

        setupTabs();
        setupNowBar();

        // Automatic scan on start: results are shown, but the service keeps
        // whatever it is already playing.
        scan(false);
    }

    /**
     * Wire the segmented control.
     *
     * Both lists occupy the same slot and only one is visible, so the adapters
     * stay attached and switching is just a visibility flip - no data is rebuilt
     * and no scroll position is lost.
     */
    private void setupTabs() {
        tabSongs = (TextView) findViewById(R.id.tab_songs);
        tabAlbums = (TextView) findViewById(R.id.tab_albums);
        songsList = (ListView) findViewById(R.id.list_songs);
        albumsList = (ListView) findViewById(R.id.list_albums);

        songsAdapter = new TrackAdapter(this, songRows, songsResults);
        albumsAdapter = new TrackAdapter(this, albumRows, albumResults);
        songsList.setAdapter(songsAdapter);
        albumsList.setAdapter(albumsAdapter);

        tabSongs.setOnClickListener(this);
        tabAlbums.setOnClickListener(this);

        songsList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onRowClick(songRows, songsResults, position);
            }
        });
        albumsList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onRowClick(albumRows, albumResults, position);
            }
        });

        showTab(false);
    }

    /** Switch the visible list and move the highlight. */
    private void showTab(boolean albums) {
        showingAlbums = albums;
        songsList.setVisibility(albums ? View.GONE : View.VISIBLE);
        albumsList.setVisibility(albums ? View.VISIBLE : View.GONE);
        tabSongs.setBackgroundDrawable(albums ? null
                : getResources().getDrawable(R.color.tab_active));
        tabAlbums.setBackgroundDrawable(albums
                ? getResources().getDrawable(R.color.tab_active) : null);
        tabSongs.setTextColor(getResources().getColor(
                albums ? R.color.text_secondary : R.color.text_primary));
        tabAlbums.setTextColor(getResources().getColor(
                albums ? R.color.text_primary : R.color.text_secondary));
        updateTabCounts();
    }

    /** Each tab shows the number of songs it holds. */
    private void updateTabCounts() {
        if (tabSongs == null || songsResults == null) {
            return;
        }
        tabSongs.setText(getString(R.string.tab_songs) + " " + songsResults.size());
        tabAlbums.setText(getString(R.string.tab_albums) + " " + albumResults.size());
    }

    /**
     * The bar is a single touch target: a tap on the play button toggles
     * playback, a tap anywhere else opens the player page. Two overlapping click
     * listeners would be ambiguous, so the coordinates decide.
     */
    private void setupNowBar() {
        playHitArea = playButton;
        nowBar.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() != MotionEvent.ACTION_UP) {
                    // Still consume the event so the bar shows feedback.
                    return true;
                }
                if (isInside(playHitArea, event)) {
                    if (player != null) {
                        player.toggle();
                        if (player.isPlaying()) {
                            startPlaybackForeground();
                        }
                    }
                } else {
                    startActivity(new Intent(MusicPlayerActivity.this, PlayerActivity.class));
                }
                return true;
            }
        });
    }

    private static boolean isInside(View view, MotionEvent event) {
        if (view == null) {
            return false;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= location[0] && x <= location[0] + view.getWidth()
                && y >= location[1] && y <= location[1] + view.getHeight();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Bind here (not in onCreate) so bind/unbind stay balanced with onStop.
        bindService(new Intent(this, TrackPlayer.class), connection, Context.BIND_AUTO_CREATE);
        onPlayerStateChanged();
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            // Not bound.
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeMessages(MSG_FILTER);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ input

    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_scan) {
            scan(true);
        } else if (id == R.id.btn_clear) {
            searchEdit.setText("");
        } else if (id == R.id.tab_songs) {
            showTab(false);
        } else if (id == R.id.tab_albums) {
            showTab(true);
        }
    }

    /** Shared click handling for both tabs. */
    private void onRowClick(List<Row> rows, List<Track> playbackOrder, int position) {
        if (player == null || position < 0 || position >= rows.size()) {
            return;
        }
        Row row = rows.get(position);
        if (row.isGroup()) {
            return; // Group headers are labels, not playable entries.
        }
        Track clicked = row.track;
        int trackIndex = playbackOrder.indexOf(clicked);
        if (trackIndex < 0) {
            return;
        }

        int serviceIndex = indexInServiceQueue(clicked);
        if (serviceIndex >= 0 && serviceIndex == player.getIndex() && player.isPrepared()) {
            player.toggle();
        } else {
            // Play the list as displayed in this tab, so next/previous follow
            // what the user is looking at.
            player.play(new ArrayList<Track>(playbackOrder), trackIndex);
            playbackStarted = true;
        }
        startPlaybackForeground();
    }

    /** Position of a track in the service queue, matched by identity then name. */
    private int indexInServiceQueue(Track track) {
        if (player == null) {
            return -1;
        }
        List<Track> queue = player.getQueueSnapshot();
        for (int i = 0; i < queue.size(); i++) {
            Track other = queue.get(i);
            if (other == track) {
                return i;
            }
            if (other.title.equals(track.title) && other.artist.equals(track.artist)) {
                return i;
            }
        }
        return -1;
    }

    /** Promote the service from bound to started so playback survives this UI. */
    private void startPlaybackForeground() {
        startService(new Intent(this, TrackPlayer.class));
    }

    // ----------------------------------------------------------------- state

    public void onPlayerStateChanged() {
        boolean playing = player != null && player.isPlaying();
        playButton.setImageResource(playing ? R.drawable.ic_bar_pause : R.drawable.ic_bar_play);
        playButton.setContentDescription(getString(
                playing ? R.string.btn_pause : R.string.btn_play));

        Track current = player != null ? player.getCurrentTrack() : null;
        if (current == null) {
            nowText.setText(R.string.no_song);
            nowArtist.setText("");
            showArtwork(null);
        } else {
            nowText.setText(current.title);
            nowArtist.setText(current.artist);
            loadArtworkAsync(current);
        }
        if (songsAdapter != null) {
            songsAdapter.notifyDataSetChanged();
        }
        if (albumsAdapter != null) {
            albumsAdapter.notifyDataSetChanged();
        }
    }

    // --------------------------------------------------------------- progress

    private static String formatTime(int millis) {
        if (millis < 0) {
            millis = 0;
        }
        int totalSeconds = millis / 1000;
        return pad(totalSeconds / 60) + ":" + pad(totalSeconds % 60);
    }

    private static String pad(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    // ------------------------------------------------------------------ scan

    /**
     * Discover songs.
     *
     * @param replaceQueue true for a user-initiated scan, which replaces what the
     *        service is playing; false for the automatic scan on activity start,
     *        which must leave in-progress playback untouched.
     */
    private void scan(final boolean replaceQueue) {
        final File fallbackDir = new File(Environment.getExternalStorageDirectory(),
                DEFAULT_FOLDER);

        listInfo.setText(R.string.scanning);
        final int generation = ++scanGeneration;

        Thread worker = new Thread(new Runnable() {
            public void run() {
                // The indexed library first: real tags and durations.
                List<Track> found = MediaLibrary.queryAll(MusicPlayerActivity.this, true);

                if (found.isEmpty() && fallbackDir.isDirectory()) {
                    // Nothing indexed yet: walk the conventional Music folder so
                    // a freshly copied file is still playable.
                    found = MediaLibrary.scanFolder(fallbackDir,
                            getString(R.string.unknown_song),
                            getString(R.string.unknown_artist),
                            getString(R.string.unknown_album));
                }

                final List<Track> result = found;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (generation != scanGeneration) {
                            return; // A newer scan superseded this one.
                        }
                        applyScanResult(result, replaceQueue);
                    }
                });
            }
        }, "audio-scan");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private void applyScanResult(List<Track> found, boolean replaceQueue) {
        allTracks.clear();
        allTracks.addAll(found);

        if (replaceQueue && player != null) {
            // The user asked for a fresh list; playback starts from scratch.
            playbackStarted = false;
            player.setQueue(new ArrayList<Track>(allTracks));
        }

        updateFilter();
    }

    /** Rebuild both lists from the current query, then resync the service. */
    private void updateFilter() {
        String query = searchEdit.getText().toString().trim().toLowerCase();

        // Flat tab: library order. The rows must be rebuilt here as well - the
        // adapter reads songRows, not songsResults.
        songsResults.clear();
        songRows.clear();
        if (query.length() == 0) {
            songsResults.addAll(allTracks);
        } else {
            for (int i = 0; i < allTracks.size(); i++) {
                Track track = allTracks.get(i);
                if (matches(track, query)) {
                    songsResults.add(track);
                }
            }
        }
        for (int i = 0; i < songsResults.size(); i++) {
            songRows.add(Row.track(songsResults.get(i)));
        }

        // Album tab: the same matches, grouped.
        buildAlbumRows(query);

        if (songsAdapter != null) {
            songsAdapter.notifyDataSetChanged();
        }
        if (albumsAdapter != null) {
            albumsAdapter.notifyDataSetChanged();
        }

        boolean playing = player != null && player.isPrepared();
        if (!playing) {
            syncQueueWithSongs();
        }
        if (songsResults.isEmpty() && allTracks.size() > 0) {
            listInfo.setText(R.string.no_match);
        } else if (playing) {
            listInfo.setText(getString(R.string.found_playing, songsResults.size()));
        } else {
            listInfo.setText(getString(R.string.found, songsResults.size()));
        }

        boolean empty = songsResults.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        emptyText.setText(allTracks.size() > 0 ? R.string.no_match : R.string.empty);
        // Keep the tab labels in step with the filtered counts.
        updateTabCounts();
    }

    private static boolean matches(Track track, String query) {
        return track.title.toLowerCase().indexOf(query) >= 0
                || track.artist.toLowerCase().indexOf(query) >= 0
                || track.album.toLowerCase().indexOf(query) >= 0;
    }

    /**
     * Fill the album tab's rows and its playback order.
     *
     * Album is the only grouping left: the folder grouping experiment is gone,
     * since the album tab answers the same question with better labels.
     */
    private void buildAlbumRows(String query) {
        String unknownAlbum = getString(R.string.group_unknown_album);

        // Insertion order preserved, so albums appear in library order.
        LinkedHashMap<String, List<Track>> groups =
                new LinkedHashMap<String, List<Track>>();
        for (int i = 0; i < allTracks.size(); i++) {
            Track track = allTracks.get(i);
            if (query.length() > 0 && !matches(track, query)) {
                continue;
            }
            String key = track.album != null && track.album.length() > 0
                    ? track.album : unknownAlbum;
            List<Track> bucket = groups.get(key);
            if (bucket == null) {
                bucket = new ArrayList<Track>();
                groups.put(key, bucket);
            }
            bucket.add(track);
        }

        albumRows.clear();
        albumResults.clear();
        for (Map.Entry<String, List<Track>> entry : groups.entrySet()) {
            List<Track> bucket = entry.getValue();
            albumRows.add(Row.group(entry.getKey(), bucket.size()));
            for (int i = 0; i < bucket.size(); i++) {
                Track track = bucket.get(i);
                albumRows.add(Row.track(track));
                albumResults.add(track);
            }
        }
    }

    /**
     * Before playback starts, keep the service queue equal to the flat list so
     * pressing play plays what the user is looking at. Once playback has begun
     * the queue is left alone, otherwise a search would restart the track.
     */
    private void syncQueueWithSongs() {
        if (player == null || playbackStarted) {
            return;
        }
        List<Track> queue = player.getQueueSnapshot();
        if (queue.size() == songsResults.size()) {
            boolean same = true;
            for (int i = 0; i < queue.size() && same; i++) {
                if (queue.get(i) != songsResults.get(i)) {
                    same = false;
                }
            }
            if (same) {
                return;
            }
        }
        player.setQueue(new ArrayList<Track>(songsResults));
    }

    /** Extensions treated as audio; also used by MediaLibrary's folder scan. */
    public static boolean hasAudioExtension(String name) {
        String lower = name.toLowerCase();
        for (int i = 0; i < AUDIO_EXT.length; i++) {
            if (lower.endsWith(AUDIO_EXT[i])) {
                return true;
            }
        }
        return false;
    }

    // --------------------------------------------------------------- artwork

    private void showArtwork(Bitmap art) {
        if (art != null) {
            artView.setImageBitmap(art);
            return;
        }
        int edge = artView.getWidth();
        if (edge <= 0) {
            edge = 40;
        }
        artView.setImageBitmap(Artwork.placeholder(edge, 0xFF1B2027, 0xFF8B97A6));
    }

    private void loadArtworkAsync(final Track track) {
        if (Artwork.isCached(track)) {
            showArtwork(Artwork.load(this, track, track.albumId));
            return;
        }
        showArtwork(null); // placeholder while decoding
        final int token = ++artToken;
        Thread worker = new Thread(new Runnable() {
            public void run() {
                final Bitmap art = Artwork.load(MusicPlayerActivity.this, track, track.albumId);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (token != artToken) {
                            return; // A newer track superseded this request.
                        }
                        showArtwork(art);
                    }
                });
            }
        }, "cover-load");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    // ------------------------------------------------------------------ adapters

    /** Renders one tab: group headers interleaved with track rows. */
    private class TrackAdapter extends BaseAdapter {

        private final LayoutInflater inflater;
        private final List<Row> rows;
        private final List<Track> tracks;

        TrackAdapter(Context context, List<Row> rows, List<Track> tracks) {
            this.inflater = LayoutInflater.from(context);
            this.rows = rows;
            this.tracks = tracks;
        }

        public int getCount() {
            return rows.size();
        }

        public Object getItem(int position) {
            return rows.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        // Two view types: group headers must not be inflated as track rows.

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            // A group header is a label; ListView must not treat it as clickable.
            return !rows.get(position).isGroup();
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = rows.get(position);
            if (row.isGroup()) {
                return getGroupView(row, convertView, parent);
            }
            return getTrackView(row.track, convertView, parent);
        }

        private View getGroupView(Row row, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null || view.findViewById(R.id.group_name) == null) {
                view = inflater.inflate(R.layout.group_header, parent, false);
            }
            TextView name = (TextView) view.findViewById(R.id.group_name);
            TextView count = (TextView) view.findViewById(R.id.group_count);
            name.setText(row.label);
            count.setText(getString(R.string.group_count, row.count));
            return view;
        }

        private View getTrackView(Track track, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null || view.findViewById(R.id.row_title) == null) {
                view = inflater.inflate(R.layout.row, parent, false);
            }
            TextView title = (TextView) view.findViewById(R.id.row_title);
            TextView artist = (TextView) view.findViewById(R.id.row_artist);
            TextView time = (TextView) view.findViewById(R.id.row_time);

            title.setText(track.title);
            artist.setText(track.artist);
            if (track.duration > 0) {
                time.setText(formatTime((int) track.duration));
            } else {
                time.setText("--:--");
            }

            // Highlight the row that is currently loaded. Resolved once per bind
            // rather than per element, to keep binding linear.
            Track current = player != null ? player.getCurrentTrack() : null;
            title.setTextColor(track == current ? 0xFF3DA9FC : 0xFFF2F5F8);
            return view;
        }
    }
}
