package com.example.musicplayer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import android.app.ListActivity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Music player UI for Android 2.2 (API level 8).
 *
 * The activity owns the library, the list and the progress display; actual
 * playback lives in {@link TrackPlayer}, a foreground service, so music keeps
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

    /** Scan the whole MediaStore index, or just one folder. */
    private static final int MODE_LIBRARY = 0;
    private static final int MODE_FOLDER = 1;

    private static final String PREFS = "player";
    private static final String PREF_MODE = "mode";
    private static final String PREF_DIR = "dir";

    private static final int MSG_PROGRESS = 1;
    private static final long PROGRESS_INTERVAL_MS = 1000L;

    private final List<Track> tracks = new ArrayList<Track>();
    private TrackAdapter adapter;

    /** Bound playback service, or null until the connection completes. */
    private TrackPlayer player;

    private EditText pathEdit;
    private Button scanButton;
    private Button modeButton;
    private Button playButton;
    private TextView modeInfo;
    private TextView nowText;
    private TextView timeText;
    private TextView emptyText;
    private SeekBar seekBar;

    private int scanMode = MODE_LIBRARY;
    private int scanGeneration;
    private boolean userSeeking;
    private boolean progressPosted;

    private final Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what != MSG_PROGRESS) {
                return;
            }
            progressPosted = false;
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
            timeText.setText(formatTime(player.getPosition()) + " / "
                    + formatTime(duration));
            if (player.isPlaying()) {
                postProgress();
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            player = ((TrackPlayer.LocalBinder) service).getService();
            player.setListener(MusicPlayerActivity.this);
            player.setQueue(tracks);
            onPlayerStateChanged();
        }

        public void onServiceDisconnected(ComponentName name) {
            player = null;
            onPlayerStateChanged();
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        pathEdit = (EditText) findViewById(R.id.path);
        scanButton = (Button) findViewById(R.id.btn_scan);
        modeButton = (Button) findViewById(R.id.btn_mode);
        playButton = (Button) findViewById(R.id.btn_play);
        Button prevButton = (Button) findViewById(R.id.btn_prev);
        Button nextButton = (Button) findViewById(R.id.btn_next);
        modeInfo = (TextView) findViewById(R.id.mode_info);
        nowText = (TextView) findViewById(R.id.now);
        timeText = (TextView) findViewById(R.id.time);
        emptyText = (TextView) findViewById(R.id.empty);
        seekBar = (SeekBar) findViewById(R.id.seek);

        scanButton.setOnClickListener(this);
        modeButton.setOnClickListener(this);
        playButton.setOnClickListener(this);
        prevButton.setOnClickListener(this);
        nextButton.setOnClickListener(this);
        seekBar.setOnSeekBarChangeListener(this);

        adapter = new TrackAdapter(this);
        setListAdapter(adapter);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        scanMode = prefs.getInt(PREF_MODE, MODE_LIBRARY);
        File defaultDir = new File(Environment.getExternalStorageDirectory(), "Music");
        pathEdit.setText(prefs.getString(PREF_DIR, defaultDir.getAbsolutePath()));

        // The service is created by the bind in onStart and only outlives this
        // activity once playback actually starts (see startPlaybackForeground),
        // so leaving without playing leaves nothing running.
        updateModeUi();
        scanDirectory();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Bind here (not in onCreate) so the bind/unbind calls stay balanced with
        // onStop, and so playback driven from the notification is picked up again
        // when this activity returns.
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
        stopProgress();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ input

    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_scan) {
            scanDirectory();
        } else if (id == R.id.btn_mode) {
            scanMode = (scanMode == MODE_LIBRARY) ? MODE_FOLDER : MODE_LIBRARY;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(PREF_MODE, scanMode).commit();
            updateModeUi();
            scanDirectory();
        } else if (id == R.id.btn_play) {
            if (player != null) {
                player.toggle();
                startPlaybackForeground();
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
        if (player == null || position < 0 || position >= tracks.size()) {
            return;
        }
        if (position == player.getIndex() && player.isPrepared()) {
            player.toggle();
        } else {
            player.play(tracks, position);
        }
        startPlaybackForeground();
        postProgress();
    }

    /**
     * Promote the service from "bound" to "started" so playback continues after
     * this activity goes away.
     */
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

    private void scanDirectory() {
        final String raw = pathEdit.getText().toString().trim();
        final String dirPath = raw.length() == 0
                ? new File(Environment.getExternalStorageDirectory(), "Music").getAbsolutePath()
                : raw;
        final int mode = scanMode;

        // In folder mode the directory must exist; in library mode the path is
        // only a fallback, so a missing one is not fatal.
        final File dir = new File(dirPath);
        if (mode == MODE_FOLDER && !dir.isDirectory()) {
            toast(getString(R.string.dir_missing, dirPath));
            return;
        }
        if (mode == MODE_FOLDER) {
            // Remember the folder so the next launch scans the same place.
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_DIR, dirPath).commit();
        }

        nowText.setText(R.string.scanning);
        final int generation = ++scanGeneration;

        // Query MediaStore (and possibly walk the folder) off the UI thread: both
        // can be slow on a large library or a slow SD card.
        Thread worker = new Thread(new Runnable() {
            public void run() {
                List<Track> found = null;

                if (mode == MODE_LIBRARY) {
                    // Real ID3 tags and durations for everything the system has
                    // indexed.
                    found = MediaLibrary.queryAll(MusicPlayerActivity.this, true);
                }

                if (found == null || found.isEmpty()) {
                    // Nothing indexed (or folder mode): fall back to a plain
                    // directory walk, which only knows file names.
                    if (dir.isDirectory()) {
                        found = MediaLibrary.scanFolder(dir,
                                getString(R.string.unknown_song),
                                getString(R.string.unknown_artist),
                                getString(R.string.unknown_album));
                    } else if (found == null) {
                        found = new ArrayList<Track>();
                    }
                }

                final List<Track> result = found;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (generation != scanGeneration) {
                            return; // A newer scan superseded this one.
                        }
                        applyScanResult(result);
                    }
                });
            }
        }, "audio-scan");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private void updateModeUi() {
        if (modeButton == null) {
            return;
        }
        boolean library = scanMode == MODE_LIBRARY;
        modeButton.setText(library
                ? R.string.mode_toggle_to_folder
                : R.string.mode_toggle_to_library);
        modeInfo.setText(library
                ? R.string.mode_library_info
                : R.string.mode_folder_info);
    }

    private void applyScanResult(List<Track> found) {
        tracks.clear();
        tracks.addAll(found);
        adapter.notifyDataSetChanged();

        // Hand the new queue to the service. A rescan while playing therefore
        // stops playback, which is the predictable behaviour.
        if (player != null) {
            player.setQueue(tracks);
        }

        if (tracks.isEmpty()) {
            nowText.setText(R.string.no_song);
            emptyText.setVisibility(View.VISIBLE);
            getListView().setVisibility(View.GONE);
        } else {
            nowText.setText(getString(R.string.found, tracks.size()));
            emptyText.setVisibility(View.GONE);
            getListView().setVisibility(View.VISIBLE);
        }
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

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ adapter

    /** Renders the track list; kept inline to avoid an extra source file. */
    private class TrackAdapter extends BaseAdapter {

        private final LayoutInflater inflater;

        TrackAdapter(Context context) {
            inflater = LayoutInflater.from(context);
        }

        public int getCount() {
            return tracks.size();
        }

        public Object getItem(int position) {
            return tracks.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = inflater.inflate(R.layout.row, parent, false);
            }
            Track track = tracks.get(position);

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

            // Highlight the track that is currently loaded.
            boolean active = player != null && position == player.getIndex();
            title.setTextColor(active ? 0xFF3DA9FC : 0xFFF2F5F8);
            return view;
        }
    }
}
