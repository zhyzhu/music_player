package com.example.musicplayer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Full screen player page, reached by tapping the now-playing bar.
 *
 * Holds no playback state: it binds to the same {@link TrackPlayer} service and
 * mirrors it, which is also why leaving this page never interrupts playback.
 *
 * Written in Java 6 syntax against API level 8.
 */
public class PlayerActivity extends Activity
        implements View.OnClickListener,
                   SeekBar.OnSeekBarChangeListener,
                   TrackPlayer.Listener {

    private static final int MSG_PROGRESS = 1;
    private static final long PROGRESS_INTERVAL_MS = 1000L;

    private TrackPlayer player;

    private ImageView artView;
    private TextView trackText;
    private TextView artistText;
    private TextView albumText;
    private TextView timeText;
    private TextView modeText;
    private SeekBar seekBar;
    private ImageButton playButton;
    private ImageButton shuffleButton;
    private ImageButton repeatButton;

    private boolean userSeeking;
    private boolean progressPosted;
    /** Guards against a slow cover decode overwriting a newer one. */
    private int artToken;

    private final Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == MSG_PROGRESS) {
                progressPosted = false;
                updateProgress();
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            player = ((TrackPlayer.LocalBinder) service).getService();
            player.setListener(PlayerActivity.this);
            onPlayerStateChanged();
        }

        public void onServiceDisconnected(ComponentName name) {
            player = null;
            onPlayerStateChanged();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.player);

        artView = (ImageView) findViewById(R.id.player_art);
        trackText = (TextView) findViewById(R.id.player_track);
        artistText = (TextView) findViewById(R.id.player_artist);
        albumText = (TextView) findViewById(R.id.player_album);
        timeText = (TextView) findViewById(R.id.player_time);
        modeText = (TextView) findViewById(R.id.player_mode);
        seekBar = (SeekBar) findViewById(R.id.player_seek);
        playButton = (ImageButton) findViewById(R.id.player_play);
        shuffleButton = (ImageButton) findViewById(R.id.player_shuffle);
        repeatButton = (ImageButton) findViewById(R.id.player_repeat);

        playButton.setOnClickListener(this);
        shuffleButton.setOnClickListener(this);
        repeatButton.setOnClickListener(this);
        findViewById(R.id.player_prev).setOnClickListener(this);
        findViewById(R.id.player_next).setOnClickListener(this);
        seekBar.setOnSeekBarChangeListener(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Bind here so bind/unbind stay balanced with onStop, and so the page
        // picks up whatever the service is doing.
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
            // Not bound.
        }
    }

    @Override
    protected void onDestroy() {
        stopProgress();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ input

    public void onClick(View v) {
        if (player == null) {
            return;
        }
        int id = v.getId();
        if (id == R.id.player_play) {
            player.toggle();
            // Promote the service so playback outlives this page.
            startService(new Intent(this, TrackPlayer.class));
            postProgress();
        } else if (id == R.id.player_prev) {
            player.previous();
            startService(new Intent(this, TrackPlayer.class));
        } else if (id == R.id.player_next) {
            player.next();
            startService(new Intent(this, TrackPlayer.class));
        } else if (id == R.id.player_shuffle) {
            player.setShuffle(!player.isShuffle());
        } else if (id == R.id.player_repeat) {
            player.cycleRepeatMode();
        }
    }

    // ------------------------------------------------------------------ state

    public void onPlayerStateChanged() {
        boolean playing = player != null && player.isPlaying();
        playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playButton.setContentDescription(getString(
                playing ? R.string.btn_pause : R.string.btn_play));
        updateModeUi();

        Track current = player != null ? player.getCurrentTrack() : null;
        if (current == null) {
            trackText.setText(R.string.no_song);
            artistText.setText("");
            albumText.setText("");
            showArtwork(null);
        } else {
            trackText.setText(current.title);
            artistText.setText(current.artist);
            albumText.setText(current.album);
            loadArtworkAsync(current);
        }
        if (playing) {
            postProgress();
        } else {
            stopProgress();
        }
    }

    // ----------------------------------------------------------- play mode

    /**
     * Reflect shuffle and repeat state.
     *
     * The toggles swap between a grey and an accent-blue glyph rather than
     * fading one icon: on/off reads more clearly, and it avoids depending on
     * drawable alpha behaviour.
     */
    private void updateModeUi() {
        if (player == null) {
            return;
        }
        boolean shuffle = player.isShuffle();
        int repeat = player.getRepeatMode();

        shuffleButton.setImageResource(shuffle
                ? R.drawable.ic_shuffle_on : R.drawable.ic_shuffle_off);
        shuffleButton.setContentDescription(getString(R.string.cd_shuffle)
                + " " + getString(shuffle ? R.string.mode_shuffle : R.string.mode_order));

        if (repeat == TrackPlayer.REPEAT_ONE) {
            repeatButton.setImageResource(R.drawable.ic_repeat_one);
            modeText.setText(R.string.repeat_one);
        } else if (repeat == TrackPlayer.REPEAT_ALL) {
            repeatButton.setImageResource(R.drawable.ic_repeat_all);
            modeText.setText(R.string.repeat_all);
        } else {
            repeatButton.setImageResource(R.drawable.ic_repeat_off);
            modeText.setText(R.string.repeat_off);
        }
        repeatButton.setContentDescription(getString(R.string.cd_repeat)
                + " " + modeText.getText());
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

    // --------------------------------------------------------------- artwork

    private void showArtwork(Bitmap art) {
        if (art != null) {
            artView.setImageBitmap(art);
            return;
        }
        int edge = artView.getWidth();
        if (edge <= 0) {
            edge = 200;
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
                final Bitmap art = Artwork.load(PlayerActivity.this, track, track.albumId);
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
}
