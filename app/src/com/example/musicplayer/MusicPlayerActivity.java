package com.example.musicplayer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import android.app.ListActivity;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
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
 * Basic music player for Android 2.2 (API level 8).
 *
 * Features: scan a folder for audio files, show them in a list, and play them
 * with play/pause, previous/next, a seek bar and a progress readout.
 *
 * Deliberately written in Java 6 syntax and limited to API 8 APIs so it can be
 * compiled with -source/-target 1.6 and run on Froyo.
 */
public class MusicPlayerActivity extends ListActivity
        implements View.OnClickListener,
                   MediaPlayer.OnCompletionListener,
                   MediaPlayer.OnErrorListener,
                   SeekBar.OnSeekBarChangeListener,
                   AudioManager.OnAudioFocusChangeListener {

    /** File extensions treated as audio during a folder scan. */
    public static final String[] AUDIO_EXT = {
        ".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac", ".mid", ".midi", ".amr", ".3gp", ".mp4"
    };

    private static final int MSG_PROGRESS = 1;
    private static final long PROGRESS_INTERVAL_MS = 1000L;

    private MediaPlayer player;
    private AudioManager audioManager;

    private final List<Track> tracks = new ArrayList<Track>();
    private TrackAdapter adapter;

    private EditText pathEdit;
    private Button scanButton;
    private Button playButton;
    private TextView nowText;
    private TextView timeText;
    private TextView emptyText;
    private SeekBar seekBar;

    private int currentIndex = -1;
    private boolean prepared;
    private boolean userSeeking;
    /** Set when audio focus was lost, so playback can resume afterwards. */
    private boolean resumeOnFocusGain;
    private int volumeBeforeDuck = -1;
    private int scanGeneration;
    private boolean progressPosted;

    private final Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what != MSG_PROGRESS) {
                return;
            }
            progressPosted = false;
            if (player != null && prepared && !userSeeking) {
                int position = player.getCurrentPosition();
                int duration = player.getDuration();
                if (duration > 0) {
                    seekBar.setMax(duration);
                    seekBar.setProgress(position);
                }
                timeText.setText(formatTime(position) + " / " + formatTime(duration));
            }
            if (player != null && prepared && player.isPlaying()) {
                postProgress();
            }
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        pathEdit = (EditText) findViewById(R.id.path);
        scanButton = (Button) findViewById(R.id.btn_scan);
        playButton = (Button) findViewById(R.id.btn_play);
        Button prevButton = (Button) findViewById(R.id.btn_prev);
        Button nextButton = (Button) findViewById(R.id.btn_next);
        nowText = (TextView) findViewById(R.id.now);
        timeText = (TextView) findViewById(R.id.time);
        emptyText = (TextView) findViewById(R.id.empty);
        seekBar = (SeekBar) findViewById(R.id.seek);

        scanButton.setOnClickListener(this);
        playButton.setOnClickListener(this);
        prevButton.setOnClickListener(this);
        nextButton.setOnClickListener(this);
        seekBar.setOnSeekBarChangeListener(this);

        adapter = new TrackAdapter(this);
        setListAdapter(adapter);

        File defaultDir = new File(Environment.getExternalStorageDirectory(), "Music");
        pathEdit.setText(defaultDir.getAbsolutePath());

        scanDirectory();
    }

    @Override
    protected void onDestroy() {
        stopProgress();
        releasePlayer();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Basic behaviour: do not keep playing once the UI is in the background.
        if (player != null && player.isPlaying()) {
            player.pause();
            updatePlayButton();
            stopProgress();
        }
    }

    // ------------------------------------------------------------------ input

    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_scan) {
            scanDirectory();
        } else if (id == R.id.btn_play) {
            togglePlayPause();
        } else if (id == R.id.btn_prev) {
            step(-1);
        } else if (id == R.id.btn_next) {
            step(1);
        }
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long itemId) {
        if (position < 0 || position >= tracks.size()) {
            return;
        }
        if (position == currentIndex) {
            togglePlayPause();
            return;
        }
        currentIndex = position;
        startCurrent(false);
    }

    // ---------------------------------------------------------------- playback

    private void togglePlayPause() {
        if (player == null || !prepared || currentIndex < 0) {
            // Nothing loaded yet: start from the top of the list.
            if (tracks.isEmpty()) {
                toast(getString(R.string.no_song));
                return;
            }
            if (currentIndex < 0) {
                currentIndex = 0;
            }
            startCurrent(false);
            return;
        }
        if (player.isPlaying()) {
            player.pause();
        } else {
            player.start();
            postProgress();
        }
        updatePlayButton();
    }

    private void step(int delta) {
        if (tracks.isEmpty()) {
            toast(getString(R.string.no_song));
            return;
        }
        int count = tracks.size();
        int next = currentIndex < 0 ? 0 : (currentIndex + delta + count) % count;
        currentIndex = next;
        startCurrent(false);
    }

    private void startCurrent(boolean autoPlay) {
        if (currentIndex < 0 || currentIndex >= tracks.size()) {
            return;
        }
        Track track = tracks.get(currentIndex);

        releasePlayer();
        resetProgressUi();
        nowText.setText(track.displayName());

        player = new MediaPlayer();
        player.setOnCompletionListener(this);
        player.setOnErrorListener(this);
        try {
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(new File(track.path).getAbsolutePath());
            player.prepare();
            prepared = true;
        } catch (Exception e) {
            releasePlayer();
            toast(getString(R.string.error_play));
            return;
        }

        requestFocus();

        int duration = player.getDuration();
        if (duration > 0) {
            seekBar.setMax(duration);
            timeText.setText(formatTime(0) + " / " + formatTime(duration));
        }

        player.start();
        postProgress();
        updatePlayButton();
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.reset();
            } catch (Exception ignored) {
                // Nothing useful to do; the instance is being discarded.
            }
            player.release();
            player = null;
        }
        prepared = false;
        abandonFocus();
        updatePlayButton();
    }

    private void updatePlayButton() {
        if (playButton == null) {
            return;
        }
        boolean playing = player != null && prepared && player.isPlaying();
        playButton.setText(playing ? R.string.btn_pause : R.string.btn_play);
    }

    public void onCompletion(MediaPlayer mp) {
        stopProgress();
        // Advance to the next track, wrapping around at the end.
        if (tracks.size() > 1 && currentIndex >= 0) {
            currentIndex = (currentIndex + 1) % tracks.size();
            startCurrent(true);
        } else {
            updatePlayButton();
        }
    }

    public boolean onError(MediaPlayer mp, int what, int extra) {
        stopProgress();
        toast(getString(R.string.error_play));
        releasePlayer();
        return true;
    }

    // ------------------------------------------------------------ audio focus

    private void requestFocus() {
        if (audioManager == null) {
            return;
        }
        try {
            audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        } catch (Exception ignored) {
            // Focus is best-effort on old devices.
        }
    }

    private void abandonFocus() {
        if (audioManager == null) {
            return;
        }
        try {
            audioManager.abandonAudioFocus(this);
        } catch (Exception ignored) {
            // Ignore.
        }
    }

    public void onAudioFocusChange(int focusChange) {
        if (player == null || !prepared) {
            return;
        }
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnFocusGain = false;
            if (player.isPlaying()) {
                player.pause();
            }
            updatePlayButton();
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            resumeOnFocusGain = player.isPlaying();
            if (resumeOnFocusGain) {
                player.pause();
            }
            updatePlayButton();
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (audioManager != null && volumeBeforeDuck < 0) {
                volumeBeforeDuck = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                int ducked = volumeBeforeDuck / 3;
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, ducked, 0);
            }
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            if (volumeBeforeDuck >= 0 && audioManager != null) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBeforeDuck, 0);
                volumeBeforeDuck = -1;
            }
            if (resumeOnFocusGain) {
                resumeOnFocusGain = false;
                player.start();
                postProgress();
            }
            updatePlayButton();
        }
    }

    // --------------------------------------------------------------- seek bar

    public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
        if (fromUser) {
            timeText.setText(formatTime(progress) + " / "
                    + formatTime(player != null && prepared ? player.getDuration() : 0));
        }
    }

    public void onStartTrackingTouch(SeekBar bar) {
        userSeeking = true;
    }

    public void onStopTrackingTouch(SeekBar bar) {
        userSeeking = false;
        if (player != null && prepared) {
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

    private void resetProgressUi() {
        stopProgress();
        seekBar.setProgress(0);
        seekBar.setMax(0);
        timeText.setText(R.string.time_zero);
    }

    private static String formatTime(int millis) {
        if (millis < 0) {
            millis = 0;
        }
        int totalSeconds = millis / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return pad(minutes) + ":" + pad(seconds);
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

        final File dir = new File(dirPath);
        if (!dir.isDirectory()) {
            toast("目录不存在: " + dirPath);
            return;
        }

        nowText.setText(R.string.scanning);
        final int generation = ++scanGeneration;

        // Small folders are the norm here; scan on a worker thread anyway so the
        // UI never blocks on slow SD cards.
        Thread worker = new Thread(new Runnable() {
            public void run() {
                final List<Track> found = listAudioFiles(dir);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (generation != scanGeneration) {
                            return; // A newer scan superseded this one.
                        }
                        applyScanResult(found);
                    }
                });
            }
        }, "audio-scan");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private List<Track> listAudioFiles(File dir) {
        List<Track> result = new ArrayList<Track>();
        collect(dir, result, 0);
        return result;
    }

    /** Recursively gather audio files, guarding against pathological nesting. */
    private void collect(File dir, List<Track> out, int depth) {
        if (dir == null || depth > 12 || out.size() > 2000) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return; // Unreadable directory (permissions or removed media).
        }
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            String name = f.getName();
            if (name.startsWith(".")) {
                continue;
            }
            if (f.isDirectory()) {
                collect(f, out, depth + 1);
            } else if (hasAudioExtension(name)) {
                out.add(new Track(stripExtension(name), "未知艺术家", "未知专辑",
                        f.getAbsolutePath(), 0L));
            }
        }
    }

    private void applyScanResult(List<Track> found) {
        tracks.clear();
        tracks.addAll(found);
        adapter.notifyDataSetChanged();
        currentIndex = -1;
        resetProgressUi();
        releasePlayer();

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

    public static boolean hasAudioExtension(String name) {
        String lower = name.toLowerCase();
        for (int i = 0; i < AUDIO_EXT.length; i++) {
            if (lower.endsWith(AUDIO_EXT[i])) {
                return true;
            }
        }
        return false;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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
            boolean active = position == currentIndex;
            title.setTextColor(active ? 0xFF3DA9FC : 0xFFF2F5F8);
            return view;
        }
    }
}
