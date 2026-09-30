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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.widget.RemoteViews;
import android.widget.Toast;

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

    /** Repeat modes. Shuffle is independent of these. */
    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;

    private static final int NOTIFICATION_ID = 1;
    private static final int REQ_CONTENT = 10;
    /**
     * Edge of the cover bitmap put into the notification. Small on purpose: the
     * bitmap is copied over Binder into the system process, which caps the
     * transaction size.
     */
    private static final int NOTIFICATION_ART_EDGE = 144;

    /** Implemented by the activity to mirror playback state into its UI. */
    public interface Listener {
        void onPlayerStateChanged();
    }

    private final IBinder binder = new LocalBinder();
    private Listener listener;

    private final List<Track> queue = new ArrayList<Track>();
    /**
     * The order tracks are visited in. Without shuffle this is the identity
     * order; with shuffle it is a permutation. Playing next/previous walks this
     * list, so shuffling never has to disturb the queue itself.
     */
    private final List<Integer> order = new ArrayList<Integer>();
    /** Position within {@link #order}, not within {@link #queue}. */
    private int orderPos = -1;
    private boolean shuffle;
    private int repeatMode = REPEAT_ALL;

    private MediaPlayer player;
    private AudioManager audioManager;
    private NotificationManager notificationManager;

    private int index = -1;
    private boolean prepared;
    private boolean foreground;
    private int volumeBeforeDuck = -1;
    /** Guards against a slow cover decode overwriting a newer notification. */
    private int artToken;

    /** Notifications are posted from the main thread. */
    private final Handler handler = new Handler();

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

    /** Replace the queue. Stops any playback in progress: the new queue starts
     * with no current track. Callers that only want to display a list must not
     * call this (see MusicPlayerActivity's bind callback).
     */
    public void setQueue(List<Track> tracks) {
        queue.clear();
        if (tracks != null) {
            queue.addAll(tracks);
        }
        index = -1;
        orderPos = -1;
        order.clear();
        if (queue.isEmpty()) {
            releasePlayer();
            leaveForeground();
            notifyListener();
            stopSelf();
        } else {
            buildOrder(index);
            notifyListener();
        }
    }

    public int getIndex() {
        return index;
    }

    /**
     * A copy of the current queue, so callers cannot mutate the service state by
     * accident and the service keeps sole ownership of the list.
     */
    public List<Track> getQueueSnapshot() {
        return new ArrayList<Track>(queue);
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
        buildOrder(position);
        playAt(position);
    }

    public void playAt(int position) {
        if (position < 0 || position >= queue.size()) {
            return;
        }
        index = position;
        orderPos = order.indexOf(Integer.valueOf(position));
        if (orderPos < 0) {
            // Nothing sensible to walk; fall back to a linear order.
            buildOrder(position);
            orderPos = order.indexOf(Integer.valueOf(position));
        }
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
            playAt(index < 0 ? orderStart() : index);
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
        step(1);
    }

    public void previous() {
        step(-1);
    }

    /**
     * Move one position along {@link #order}.
     *
     * Manual skipping wraps in every repeat mode except "off", where it stops at
     * the ends; "repeat one" still skips, because the user asked to move.
     */
    private void step(int delta) {
        if (order.isEmpty()) {
            return;
        }
        int target = orderPos + delta;
        if (target < 0) {
            if (repeatMode == REPEAT_OFF) {
                return;
            }
            target = order.size() - 1;
        } else if (target >= order.size()) {
            if (repeatMode == REPEAT_OFF) {
                return;
            }
            target = 0;
        }
        playAt(order.get(target).intValue());
    }

    // ------------------------------------------------------ shuffle / repeat

    public boolean isShuffle() {
        return shuffle;
    }

    public int getRepeatMode() {
        return repeatMode;
    }

    /** Turning shuffle on reshuffles around the current track, which keeps playing. */
    public void setShuffle(boolean on) {
        if (shuffle == on) {
            return;
        }
        shuffle = on;
        buildOrder(index);
        notifyListener();
    }

    public void setRepeatMode(int mode) {
        if (mode != REPEAT_OFF && mode != REPEAT_ALL && mode != REPEAT_ONE) {
            return;
        }
        repeatMode = mode;
        notifyListener();
    }

    /** Cycle order -> repeat all -> repeat one. */
    public void cycleRepeatMode() {
        setRepeatMode((repeatMode + 1) % 3);
    }

    /**
     * Rebuild {@link #order}.
     *
     * With shuffle off this is the identity order. With shuffle on, the anchor
     * track (the one playing now) is placed first and the rest are shuffled, so
     * toggling shuffle never interrupts what is playing.
     */
    private void buildOrder(int anchor) {
        order.clear();
        int count = queue.size();
        if (count == 0) {
            orderPos = -1;
            return;
        }
        if (!shuffle) {
            for (int i = 0; i < count; i++) {
                order.add(Integer.valueOf(i));
            }
        } else {
            if (anchor >= 0 && anchor < count) {
                order.add(Integer.valueOf(anchor));
            }
            List<Integer> rest = new ArrayList<Integer>();
            for (int i = 0; i < count; i++) {
                if (i != anchor) {
                    rest.add(Integer.valueOf(i));
                }
            }
            java.util.Collections.shuffle(rest);
            order.addAll(rest);
        }
        orderPos = anchor >= 0 ? order.indexOf(Integer.valueOf(anchor)) : -1;
    }

    private int orderStart() {
        return order.isEmpty() ? 0 : order.get(0).intValue();
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
        // Reached the end of a track: this is where repeat mode decides what
        // happens. Playback continues even with no activity attached.
        if (queue.isEmpty() || index < 0) {
            return;
        }
        if (repeatMode == REPEAT_ONE) {
            playAt(index);
            return;
        }
        int target = orderPos + 1;
        if (target >= order.size()) {
            if (repeatMode == REPEAT_OFF) {
                // Stop at the end: stay prepared on the last track, paused.
                pause();
                return;
            }
            target = 0;
        }
        playAt(order.get(target).intValue());
    }

    public boolean onError(MediaPlayer mp, int what, int extra) {
        releasePlayer();
        leaveForeground();
        // Without this the failure would be silent: the row just does not start.
        Toast.makeText(this, R.string.error_play, Toast.LENGTH_SHORT).show();
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
        Notification n = buildNotification(track, playing,
                artFor(track, NOTIFICATION_ART_EDGE));
        startForeground(NOTIFICATION_ID, n);
        foreground = true;

        // A cover that is not cached yet would block this call on file IO, so it
        // is decoded in the background and the notification refreshed after.
        if (track != null && !Artwork.isCached(track)) {
            refreshArtworkAsync(track, playing);
        }
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

    /** Decode the cover off the main thread, then repost the notification. */
    private void refreshArtworkAsync(final Track track, final boolean playing) {
        final int token = ++artToken;
        Thread worker = new Thread(new Runnable() {
            public void run() {
                final Bitmap art = Artwork.load(TrackPlayer.this, track, track.albumId);
                if (art == null) {
                    return; // Keep the placeholder already displayed.
                }
                handler.post(new Runnable() {
                    public void run() {
                        if (token != artToken || !foreground) {
                            return; // A newer track superseded this request.
                        }
                        if (notificationManager != null) {
                            notificationManager.notify(NOTIFICATION_ID,
                                    buildNotification(track, playing, art));
                        }
                    }
                });
            }
        }, "notif-cover");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    /** Cached cover if there is one; never blocks, so it may return null. */
    private Bitmap artFor(Track track, int edge) {
        if (track != null && Artwork.isCached(track)) {
            Bitmap cached = Artwork.load(this, track, track.albumId);
            if (cached != null) {
                return cached;
            }
        }
        return Artwork.placeholder(edge, 0xFF1B2027, 0xFF8B97A6);
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(Track track, boolean playing, Bitmap art) {
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

        Intent content = new Intent(this, MusicPlayerActivity.class);
        content.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, REQ_CONTENT,
                content, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification();
        // A dedicated white-on-transparent glyph: the system tints notification
        // icons, so the launcher icon would render as a solid block.
        n.icon = R.drawable.ic_stat_music;
        n.tickerText = title;
        n.when = System.currentTimeMillis();
        n.flags |= Notification.FLAG_ONGOING_EVENT;
        n.flags |= Notification.FLAG_NO_CLEAR;
        n.contentIntent = contentIntent;

        // The custom view carries the cover. setLatestEventInfo is still called
        // so the notification stays valid on API levels that ignore contentView.
        n.setLatestEventInfo(this, title, text, contentIntent);
        n.contentView = buildContentView(title, text, art);
        return n;
    }

    private RemoteViews buildContentView(String title, String text, Bitmap art) {
        RemoteViews views = new RemoteViews(getPackageName(), R.layout.notification);
        views.setTextViewText(R.id.notif_title, title);
        views.setTextViewText(R.id.notif_text, text);
        Bitmap scaled = scaleTo(art, NOTIFICATION_ART_EDGE);
        if (scaled != null) {
            views.setImageViewBitmap(R.id.notif_art, scaled);
        }
        return views;
    }

    /**
     * Scale into a fresh mutable bitmap.
     *
     * Bitmap.createScaledBitmap cannot be used here: art decoded from a PNG is
     * immutable, and the framework call rejects it.
     */
    private static Bitmap scaleTo(Bitmap source, int edge) {
        if (source == null) {
            return null;
        }
        try {
            Bitmap out = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawBitmap(source, null,
                    new android.graphics.Rect(0, 0, edge, edge), null);
            return out;
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }
}
