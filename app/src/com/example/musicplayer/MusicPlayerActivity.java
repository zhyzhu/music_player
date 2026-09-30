package com.example.musicplayer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import android.app.ListActivity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Music player UI for Android 2.2 (API level 8).
 *
 * Songs are discovered automatically: the MediaStore index first, then the Music
 * folder on the SD card for anything the media scanner has not indexed. The user
 * never has to know or type a path - the search box filters by title or artist.
 *
 * Playback lives in {@link TrackPlayer}, a foreground service, so audio keeps
 * playing when this activity is not in the foreground.
 *
 * Deliberately written in Java 6 syntax and limited to API 8 APIs.
 */
public class MusicPlayerActivity extends ListActivity
        implements View.OnClickListener,
                   SeekBar.OnSeekBarChangeListener,
                   TrackPlayer.Listener {

    /** File extensions treated as audio during a folder scan. */
    public static final String[] AUDIO_EXT = {
        ".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac", ".mid", ".midi", ".amr", ".3gp", ".mp4"
    };

    /** Folder scanned when nothing is indexed yet; no need to ask the user. */
    private static final String DEFAULT_FOLDER = "Music";

    private static final int MSG_PROGRESS = 1;
    private static final int MSG_FILTER = 2;
    private static final long PROGRESS_INTERVAL_MS = 1000L;
    /** Keystrokes arrive faster than a filter needs to run. */
    private static final long FILTER_DELAY_MS = 250L;

    /** Every track found by the last scan, in display order. */
    private final List<Track> allTracks = new ArrayList<Track>();
    /** The subset currently shown, matching the search box. */
    private final List<Track> visibleTracks = new ArrayList<Track>();
    private TrackAdapter adapter;

    /** Bound playback service, or null until the connection completes. */
    private TrackPlayer player;
    /** True once playback has been asked for; from then on the queue is fixed. */
    private boolean playbackStarted;

    private EditText searchEdit;
    private Button clearButton;
    private Button playButton;
    private TextView listInfo;
    private TextView nowText;
    private TextView timeText;
    private TextView emptyText;
    private SeekBar seekBar;

    private int scanGeneration;
    private boolean userSeeking;
    private boolean progressPosted;

    private final Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == MSG_PROGRESS) {
                progressPosted = false;
                updateProgress();
            } else if (msg.what == MSG_FILTER) {
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
            // recreated (back button, then relaunch from the notification) the
            // bind callback runs before the scan finishes, so pushing the local
            // list would hand the service an empty queue, which releases the
            // player and stops playback. Adopt whatever the service already has
            // and only seed it when the service has nothing.
            List<Track> serviceQueue = player.getQueueSnapshot();
            if (!serviceQueue.isEmpty()) {
                allTracks.clear();
                allTracks.addAll(serviceQueue);
                adapter.notifyDataSetChanged();
            } else if (!visibleTracks.isEmpty()) {
                player.setQueue(new ArrayList<Track>(visibleTracks));
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
        playButton = (Button) findViewById(R.id.btn_play);
        Button prevButton = (Button) findViewById(R.id.btn_prev);
        Button nextButton = (Button) findViewById(R.id.btn_next);
        listInfo = (TextView) findViewById(R.id.list_info);
        nowText = (TextView) findViewById(R.id.now);
        timeText = (TextView) findViewById(R.id.time);
        emptyText = (TextView) findViewById(R.id.empty);
        seekBar = (SeekBar) findViewById(R.id.seek);

        scanButton.setOnClickListener(this);
        clearButton.setOnClickListener(this);
        playButton.setOnClickListener(this);
        prevButton.setOnClickListener(this);
        nextButton.setOnClickListener(this);
        seekBar.setOnSeekBarChangeListener(this);
        searchEdit.addTextChangedListener(searchWatcher);

        adapter = new TrackAdapter(this);
        setListAdapter(adapter);

        // Automatic scan on start: results are shown, but the service keeps
        // whatever it is already playing, so returning here cannot interrupt it.
        scan(false);
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
        stopProgress();
        try {
            unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            // Not bound (for example when onCreate's bind already failed).
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeMessages(MSG_FILTER);
        stopProgress();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ input

    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_scan) {
            scan(true);
        } else if (id == R.id.btn_clear) {
            searchEdit.setText("");
        } else if (id == R.id.btn_play) {
            if (player != null) {
                player.toggle();
                if (player.isPlaying()) {
                    startPlaybackForeground();
                }
                postProgress();
            }
        } else if (id == R.id.btn_prev) {
            if (player != null) {
                player.previous();
                startPlaybackForeground();
            }
        } else if (id == R.id.btn_next) {
            if (player != null) {
                player.next();
                startPlaybackForeground();
            }
        }
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long itemId) {
        if (player == null || position < 0 || position >= visibleTracks.size()) {
            return;
        }
        int serviceIndex = indexInServiceQueue(visibleTracks.get(position));
        if (serviceIndex >= 0 && serviceIndex == player.getIndex() && player.isPrepared()) {
            player.toggle();
        } else {
            // Play the visible list, so next/previous follow what the user sees.
            player.play(new ArrayList<Track>(visibleTracks), position);
            playbackStarted = true;
        }
        startPlaybackForeground();
        postProgress();
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
        playButton.setText(playing ? R.string.btn_pause : R.string.btn_play);

        Track current = player != null ? player.getCurrentTrack() : null;
        if (current == null) {
            nowText.setText(R.string.no_song);
        } else {
            nowText.setText(current.displayName());
        }
        adapter.notifyDataSetChanged();
        if (playing) {
            postProgress();
        } else {
            stopProgress();
        }
    }

    private void updateProgress() {
        if (player == null || !player.isPrepared()) {
            return;
        }
        int duration = player.getDuration();
        if (duration > 0 && seekBar.getMax() != duration) {
            seekBar.setMax(duration);
        }
        if (!userSeeking && duration > 0) {
            seekBar.setProgress(player.getPosition());
        }
        timeText.setText(formatTime(player.getPosition()) + " / " + formatTime(duration));
        if (player.isPlaying()) {
            postProgress();
        }
    }

    // --------------------------------------------------------------- seek bar

    public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
        if (fromUser) {
            int duration = player != null ? player.getDuration() : 0;
            timeText.setText(formatTime(progress) + " / " + formatTime(duration));
        }
    }

    public void onStartTrackingTouch(SeekBar bar) {
        userSeeking = true;
    }

    public void onStopTrackingTouch(SeekBar bar) {
        userSeeking = false;
        if (player != null) {
            player.seekTo(bar.getProgress());
        }
    }

    // --------------------------------------------------------------- progress

    private void postProgress() {
        if (!progressPosted) {
            progressPosted = true;
            handler.sendEmptyMessageDelayed(MSG_PROGRESS, PROGRESS_INTERVAL_MS);
        }
    }

    private void stopProgress() {
        handler.removeMessages(MSG_PROGRESS);
        progressPosted = false;
    }

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

        // Both the MediaStore query and the folder walk can be slow on a large
        // library or a slow SD card, so they run off the UI thread.
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

        boolean playing = player != null && player.isPrepared();
        if (replaceQueue && player != null) {
            // The user asked for a fresh list; hand the service the full set and
            // let updateFilter narrow it down. Passing the (possibly empty)
            // filtered list here would make the service shut itself down.
            playbackStarted = false;
            player.setQueue(new ArrayList<Track>(allTracks));
            playing = false;
        }

        updateFilter();
        if (!playing) {
            listInfo.setText(getString(R.string.found, allTracks.size()));
        }
    }

    /** Rebuild the visible list from the current query, then resync the service. */
    private void updateFilter() {
        String query = searchEdit.getText().toString().trim().toLowerCase();

        visibleTracks.clear();
        if (query.length() == 0) {
            visibleTracks.addAll(allTracks);
        } else {
            for (int i = 0; i < allTracks.size(); i++) {
                Track track = allTracks.get(i);
                if (track.title.toLowerCase().indexOf(query) >= 0
                        || track.artist.toLowerCase().indexOf(query) >= 0) {
                    visibleTracks.add(track);
                }
            }
        }
        adapter.notifyDataSetChanged();

        boolean playing = player != null && player.isPrepared();
        if (playing) {
            // The queue is fixed once playing, so searching only changes the
            // list and never interrupts the current track.
            listInfo.setText(getString(R.string.found_playing, visibleTracks.size()));
        } else {
            syncQueueWithVisible();
            if (visibleTracks.isEmpty() && allTracks.size() > 0) {
                listInfo.setText(R.string.no_match);
            } else {
                listInfo.setText(getString(R.string.found, visibleTracks.size()));
            }
        }

        boolean empty = visibleTracks.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        getListView().setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty && allTracks.size() > 0) {
            emptyText.setText(R.string.no_match);
        } else if (empty) {
            emptyText.setText(R.string.empty);
        }
    }

    /**
     * Before playback starts, keep the service queue equal to the visible list so
     * pressing play plays what the user is looking at. Once playback has begun
     * the queue is left alone, otherwise a search would restart the track.
     */
    private void syncQueueWithVisible() {
        if (player == null || playbackStarted) {
            return;
        }
        List<Track> queue = player.getQueueSnapshot();
        if (queue.size() == visibleTracks.size()) {
            boolean same = true;
            for (int i = 0; i < queue.size() && same; i++) {
                if (queue.get(i) != visibleTracks.get(i)) {
                    same = false;
                }
            }
            if (same) {
                return;
            }
        }
        player.setQueue(new ArrayList<Track>(visibleTracks));
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

    // ------------------------------------------------------------------ adapter

    /** Renders the track list; kept inline to avoid an extra source file. */
    private class TrackAdapter extends BaseAdapter {

        private final LayoutInflater inflater;

        TrackAdapter(Context context) {
            inflater = LayoutInflater.from(context);
        }

        public int getCount() {
            return visibleTracks.size();
        }

        public Object getItem(int position) {
            return visibleTracks.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = inflater.inflate(R.layout.row, parent, false);
            }
            Track track = visibleTracks.get(position);

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
