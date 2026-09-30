package com.example.musicplayer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;

/**
 * Playback engine, owned by a foreground service so audio keeps running when the
 * activity is not in the foreground.
 *
 * The activity binds for control and observation. Because API level 8
 * notifications cannot carry action buttons (that arrived with the expandable
 * notification in API 11), the notification offers a single tap target that
 * brings the activity back, and its text mirrors the current track.
 *
 * Written in Java 6 syntax against API level 8.
 */
public class TrackPlayer extends Service
        implements MediaPlayer.OnCompletionListener,
                   MediaPlayer.OnErrorListener,
                   AudioManager.OnAudioFocusChangeListener {

    /** Sent by the notification; also usable from adb. */
    public static final String ACTION_TOGGLE = "com.example.musicplayer.TOGGLE";
    public static final String ACTION_PAUSE = "com.example.musicplayer.PAUSE";
    public static final String ACTION_NEXT = "com.example.musicplayer.NEXT";

    private static final int NOTIFICATION_ID = 1;
    private static final int REQ_CONTENT = 10;

    /** Implemented by the activity to mirror playback state into its UI. */
    public interface Listener {
        void onPlayerStateChanged();
    }

    private final IBinder binder = new LocalBinder();
    private Listener listener;

    private final List<Track> queue = new ArrayList<Track>();
    private MediaPlayer player;
    private AudioManager audioManager;
    private NotificationManager notificationManager;

    private int index = -1;
    private boolean prepared;
    private boolean foreground;
    private int volumeBeforeDuck = -1;

    public class LocalBinder extends Binder {
        public TrackPlayer getService() {
            return TrackPlayer.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_TOGGLE.equals(action)) {
                toggle();
            } else if (ACTION_PAUSE.equals(action)) {
                pause();
            } else if (ACTION_NEXT.equals(action)) {
                next();
            }
        }
        // The queue is supplied by the activity, so restarting with a null
        // intent would leave the player with nothing to play.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        stopForeground(true);
        super.onDestroy();
    }

    // ------------------------------------------------------------- public API

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Replace the queue. Does not start playback. */
    public void setQueue(List<Track> tracks) {
        queue.clear();
        if (tracks != null) {
            queue.addAll(tracks);
        }
        if (index >= queue.size()) {
            index = -1;
        }
        if (queue.isEmpty()) {
            releasePlayer();
            leaveForeground();
            notifyListener();
            stopSelf();
        } else {
            notifyListener();
        }
    }

    public int getIndex() {
        return index;
    }

    public Track getCurrentTrack() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    public boolean isPlaying() {
        try {
            return player != null && prepared && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public boolean isPrepared() {
        return prepared;
    }

    public int getPosition() {
        try {
            return player != null && prepared ? player.getCurrentPosition() : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public int getDuration() {
        try {
            return player != null && prepared ? player.getDuration() : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public void play(List<Track> tracks, int position) {
        queue.clear();
        if (tracks != null) {
            queue.addAll(tracks);
        }
        playAt(position);
    }

    public void playAt(int position) {
        if (position < 0 || position >= queue.size()) {
            return;
        }
        index = position;
        Track track = queue.get(position);

        releasePlayer();
        player = new MediaPlayer();
        player.setOnCompletionListener(this);
        player.setOnErrorListener(this);
        try {
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            // MediaStore entries play through their content:// URI; folder scans
            // use the file path.
            if (track.uri != null) {
                player.setDataSource(this, Uri.parse(track.uri));
            } else {
                player.setDataSource(new File(track.path).getAbsolutePath());
            }
            player.prepare();
            prepared = true;
        } catch (Exception e) {
            releasePlayer();
            leaveForeground();
            notifyListener();
            return;
        }

        requestFocus();
        player.start();
        enterForeground(track, true);
        notifyListener();
    }

    public void toggle() {
        if (player != null && prepared) {
            if (isPlaying()) {
                pause();
            } else {
                requestFocus();
                player.start();
                enterForeground(getCurrentTrack(), true);
                notifyListener();
            }
        } else if (!queue.isEmpty()) {
            playAt(index < 0 ? 0 : index);
        }
    }

    public void pause() {
        if (player != null && prepared && isPlaying()) {
            player.pause();
            // Stay foreground while paused so the notification survives; it
            // shows the play action instead.
            enterForeground(getCurrentTrack(), false);
            notifyListener();
        }
    }

    public void next() {
        if (queue.isEmpty()) {
            return;
        }
        playAt(index < 0 ? 0 : (index + 1) % queue.size());
    }

    public void previous() {
        if (queue.isEmpty()) {
            return;
        }
        playAt(index < 0 ? 0 : (index - 1 + queue.size()) % queue.size());
    }

    public void seekTo(int position) {
        if (player != null && prepared) {
            player.seekTo(position);
            notifyListener();
        }
    }

    /** Stop playback and tear the service down. */
    public void stop() {
        releasePlayer();
        index = -1;
        leaveForeground();
        notifyListener();
        stopSelf();
    }

    // -------------------------------------------------------------- internals

    private void releasePlayer() {
        if (player != null) {
            try {
                player.reset();
            } catch (Exception ignored) {
                // The instance is being discarded.
            }
            player.release();
            player = null;
        }
        prepared = false;
        abandonFocus();
    }

    public void onCompletion(MediaPlayer mp) {
        // Advance within the queue the service holds, so playback continues even
        // with no activity attached.
        if (!queue.isEmpty() && index >= 0) {
            playAt((index + 1) % queue.size());
        }
    }

    public boolean onError(MediaPlayer mp, int what, int extra) {
        releasePlayer();
        leaveForeground();
        notifyListener();
        return true;
    }

    private void notifyListener() {
        Listener l = listener;
        if (l != null) {
            l.onPlayerStateChanged();
        }
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
        if (volumeBeforeDuck >= 0 && audioManager != null) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBeforeDuck, 0);
            volumeBeforeDuck = -1;
        }
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
        if (audioManager == null) {
            return;
        }
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pause();
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (volumeBeforeDuck < 0) {
                volumeBeforeDuck = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
                        volumeBeforeDuck / 3, 0);
            }
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            if (volumeBeforeDuck >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
                        volumeBeforeDuck, 0);
                volumeBeforeDuck = -1;
            }
        }
    }

    // ----------------------------------------------------------- notification

    private void enterForeground(Track track, boolean playing) {
        Notification n = buildNotification(track, playing);
        startForeground(NOTIFICATION_ID, n);
        foreground = true;
    }

    private void leaveForeground() {
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
        if (foreground) {
            stopForeground(true);
            foreground = false;
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(Track track, boolean playing) {
        String title = track != null ? track.title : getString(R.string.no_song);
        String text;
        if (track == null) {
            text = getString(R.string.app_name);
        } else if (playing) {
            text = track.artist;
        } else {
            // Make it obvious this is a paused player, not a stale notification.
            text = track.artist + "  -  " + getString(R.string.btn_play);
        }

        Notification n = new Notification();
        // A dedicated white-on-transparent glyph: the system tints notification
        // icons, so the launcher icon would render as a solid block.
        n.icon = R.drawable.ic_stat_music;
        n.tickerText = title;
        n.when = System.currentTimeMillis();
        n.flags |= Notification.FLAG_ONGOING_EVENT;
        n.flags |= Notification.FLAG_NO_CLEAR;

        Intent content = new Intent(this, MusicPlayerActivity.class);
        content.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, REQ_CONTENT,
                content, PendingIntent.FLAG_UPDATE_CURRENT);

        // API 8 has no action buttons, so the whole notification is the tap
        // target; playback control stays in the activity.
        n.setLatestEventInfo(this, title, text, contentIntent);
        return n;
    }
}
