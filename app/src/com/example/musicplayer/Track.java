package com.example.musicplayer;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * One playable audio entry.
 *
 * A track can come from two sources: the MediaStore index (which carries real
 * ID3 tags, a duration and a content:// URI) or a plain folder scan (which only
 * knows the file name). Both are represented here, so playback must accept
 * either a file path or a URI.
 *
 * Parcelable so the queue can be handed to the playback service across the
 * binder, and Serializable so a single track survives a process restart via the
 * service's START_REDELIVER_INTENT retry.
 *
 * Fallback labels are passed in by the caller rather than taken from resources,
 * so this class stays dependency-free and compiles against API level 8.
 */
public class Track implements Parcelable {

    public final String title;
    public final String artist;
    public final String album;
    /** Absolute file path, or null for MediaStore entries. */
    public final String path;
    /** "content://media/..." URI from MediaStore, or null for folder scans. */
    public final String uri;
    /** Duration in milliseconds, or 0 when unknown (file-scanned entries). */
    public final long duration;

    public Track(String title, String artist, String album,
                 String path, String uri, long duration,
                 String fallbackTitle, String fallbackArtist, String fallbackAlbum) {
        this.title = clean(title, fallbackTitle);
        this.artist = clean(artist, fallbackArtist);
        this.album = clean(album, fallbackAlbum);
        this.path = path;
        this.uri = uri;
        this.duration = duration;
    }

    private Track(Parcel in) {
        title = in.readString();
        artist = in.readString();
        album = in.readString();
        path = in.readString();
        uri = in.readString();
        duration = in.readLong();
    }

    /** Folder scan: only the file name is known. */
    public static Track fromFile(String name, String path,
                                 String fallbackTitle, String fallbackArtist,
                                 String fallbackAlbum) {
        return new Track(name, null, null, path, null, 0L,
                fallbackTitle, fallbackArtist, fallbackAlbum);
    }

    /** MediaStore entry, with real tags and a content:// URI. */
    public static Track fromMediaStore(String title, String artist, String album,
                                       String path, String uri, long duration,
                                       boolean variousArtists, String fallbackTitle,
                                       String fallbackArtist, String fallbackAlbum,
                                       String variousLabel) {
        String shownArtist = variousArtists ? variousLabel : artist;
        return new Track(title, shownArtist, album, path, uri, duration,
                fallbackTitle, fallbackArtist, fallbackAlbum);
    }

    private static String clean(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        // MediaStore stores "<unknown>" when a tag is missing.
        if (trimmed.length() == 0 || "<unknown>".equalsIgnoreCase(trimmed)) {
            return fallback;
        }
        return trimmed;
    }

    /** Label used in the now-playing bar. */
    public String displayName() {
        return title + " - " + artist;
    }

    // -------------------------------------------------------------- Parcelable

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(title);
        dest.writeString(artist);
        dest.writeString(album);
        dest.writeString(path);
        dest.writeString(uri);
        dest.writeLong(duration);
    }

    public static final Parcelable.Creator<Track> CREATOR =
            new Parcelable.Creator<Track>() {
                public Track createFromParcel(Parcel in) {
                    return new Track(in);
                }

                public Track[] newArray(int size) {
                    return new Track[size];
                }
            };
}
