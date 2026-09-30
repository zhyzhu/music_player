package com.example.musicplayer;

/**
 * One playable audio entry.
 *
 * Kept deliberately simple: this class only holds data, so it compiles against
 * Android 2.2 (API level 8) with no dependencies beyond the JDK.
 */
public class Track {

    public final String title;
    public final String artist;
    public final String album;
    public final String path;
    /** Duration in milliseconds, or 0 when unknown (file-scanned entries). */
    public final long duration;

    public Track(String title, String artist, String album, String path, long duration) {
        this.title = emptyTo(title, "未知歌曲");
        this.artist = emptyTo(artist, "未知艺术家");
        this.album = emptyTo(album, "未知专辑");
        this.path = path;
        this.duration = duration;
    }

    private static String emptyTo(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        // "<unknown>" is what MediaStore stores when a tag is missing.
        if (trimmed.length() == 0 || "<unknown>".equalsIgnoreCase(trimmed)) {
            return fallback;
        }
        return trimmed;
    }

    /** Human readable label used both in the list and in the now-playing bar. */
    public String displayName() {
        return title + " - " + artist;
    }
}
