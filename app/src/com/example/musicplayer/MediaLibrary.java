package com.example.musicplayer;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

/**
 * Reads the device music library through MediaStore.
 *
 * This is what gives the list real ID3 tags (title, artist, album) and a known
 * duration. It only sees files the system media scanner has indexed, so a folder
 * walk is still available as a fallback for anything not yet indexed.
 */
public final class MediaLibrary {

    /** Columns that exist on API level 8's MediaStore.Audio.Media. */
    private static final String[] COLUMNS = {
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.IS_MUSIC,
        MediaStore.Audio.Media.ALBUM_ID
    };

    private MediaLibrary() {
    }

    /**
     * Query every indexed audio track.
     *
     * @return the tracks, sorted by title; never null. An empty list means the
     *         query failed or the library holds nothing.
     */
    public static List<Track> queryAll(Context context, boolean musicOnly) {
        List<Track> result = new ArrayList<Track>();
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) {
            return result;
        }

        // Localised fallback labels, resolved once per query.
        String fbTitle = context.getString(R.string.unknown_song);
        String fbArtist = context.getString(R.string.unknown_artist);
        String fbAlbum = context.getString(R.string.unknown_album);
        String various = context.getString(R.string.various_artists);

        String selection = musicOnly
                ? MediaStore.Audio.Media.IS_MUSIC + " != 0"
                : MediaStore.Audio.Media.DURATION + " > 0";

        Cursor cursor = null;
        try {
            cursor = resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    COLUMNS, selection, null,
                    MediaStore.Audio.Media.TITLE + " ASC");
            if (cursor == null) {
                return result;
            }

            // Resolve column positions defensively: a provider may omit columns,
            // and getString on a missing index would throw.
            int idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
            int titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
            int artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
            int albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
            int durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
            int dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
            int albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID);

            while (cursor.moveToNext()) {
                String path = dataCol >= 0 && !cursor.isNull(dataCol)
                        ? cursor.getString(dataCol) : null;

                String uri = null;
                if (idCol >= 0 && !cursor.isNull(idCol)) {
                    Uri contentUri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idCol));
                    uri = contentUri.toString();
                }
                if (uri == null && path == null) {
                    continue; // Nothing to play.
                }

                String title = titleCol >= 0 ? cursor.getString(titleCol) : null;
                String artist = artistCol >= 0 ? cursor.getString(artistCol) : null;
                String album = albumCol >= 0 ? cursor.getString(albumCol) : null;
                long duration = durationCol >= 0 && !cursor.isNull(durationCol)
                        ? cursor.getLong(durationCol) : 0L;
                long albumId = albumIdCol >= 0 && !cursor.isNull(albumIdCol)
                        ? cursor.getLong(albumIdCol) : -1L;

                // Compilation albums list every performer in ARTIST; a summary
                // beats a wall of names.
                boolean variousArtists = artist != null && artist.indexOf(';') >= 0;

                result.add(Track.fromMediaStore(title, artist, album, path, uri,
                        duration, albumId, variousArtists, fbTitle, fbArtist, fbAlbum,
                        various));
            }
        } catch (Exception e) {
            // A missing or broken media provider must not crash the player; the
            // caller falls back to a folder scan, so an empty list is the right
            // answer here.
            return new ArrayList<Track>();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return result;
    }

    /** Recursively gather audio files, for content the scanner has not indexed. */
    public static List<Track> scanFolder(File dir, String fbTitle, String fbArtist,
                                         String fbAlbum) {
        List<Track> out = new ArrayList<Track>();
        collect(dir, out, 0, fbTitle, fbArtist, fbAlbum);
        Collections.sort(out, new Comparator<Track>() {
            public int compare(Track a, Track b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        return out;
    }

    private static void collect(File dir, List<Track> out, int depth,
                                String fbTitle, String fbArtist, String fbAlbum) {
        if (dir == null || depth > 12 || out.size() > 3000) {
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
                collect(f, out, depth + 1, fbTitle, fbArtist, fbAlbum);
            } else if (MusicPlayerActivity.hasAudioExtension(name)) {
                out.add(Track.fromFile(stripExtension(name), f.getAbsolutePath(),
                        fbTitle, fbArtist, fbAlbum));
            }
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
