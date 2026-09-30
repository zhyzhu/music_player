package com.example.musicplayer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;

/**
 * Reads album art ("cover") for a track.
 *
 * Two sources, because neither is sufficient on API level 8:
 *
 * 1. The MediaStore album art table. Cheap, but only populated for files the
 *    media scanner has processed, and not on every device.
 * 2. An ID3v2 APIC frame parsed straight out of the file. Works regardless of
 *    the scanner, but only for MP3.
 *
 * MediaMetadataRetriever would do both in one call, but it does not exist in
 * API level 8 - it arrived in API 10 - and this project compiles against the
 * API 8 android.jar.
 *
 * Decoded bitmaps are cached in a small LRU map. Decoding is slow, so callers
 * must stay off the main thread.
 */
public final class Artwork {

    /** Largest edge of a decoded cover; keeps memory sane on a low-end device. */
    private static final int MAX_EDGE = 320;
    private static final int CACHE_ENTRIES = 12;
    /** Refuse absurd tags rather than allocating for them. */
    private static final int MAX_ART_BYTES = 4 * 1024 * 1024;

    /** The column exists in the album art table; named for clarity. */
    private static final String COLUMN_ALBUM_ART = "album_art";
    /** Content URI for an album's cover, by album id. */
    private static final Uri ALBUM_ART_URI =
            Uri.parse("content://media/external/audio/albumart");

    private static final Map<String, Bitmap> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > CACHE_ENTRIES;
                }
            });

    private Artwork() {
    }

    /**
     * Cover for a track, or null when none can be found.
     *
     * Cached: a repeated call for the same track does no work.
     */
    public static Bitmap load(Context context, Track track, long albumId) {
        if (track == null) {
            return null;
        }
        String key = keyOf(track);
        synchronized (CACHE) {
            if (CACHE.containsKey(key)) {
                return CACHE.get(key);
            }
        }

        byte[] raw = null;
        if (albumId > 0) {
            raw = fromMediaStore(context, albumId);
        }
        if (raw == null && track.path != null) {
            // The tag reader is a plain Java class, so it is unit tested rather
            // than trusted by eye.
            raw = Id3.readAlbumArt(new File(track.path));
        }

        Bitmap decoded = raw != null ? decodeSampled(raw) : null;
        if (decoded != null) {
            CACHE.put(key, decoded);
        }
        return decoded;
    }

    public static boolean isCached(Track track) {
        if (track == null) {
            return false;
        }
        synchronized (CACHE) {
            return CACHE.containsKey(keyOf(track));
        }
    }

    private static String keyOf(Track track) {
        if (track.uri != null) {
            return track.uri;
        }
        return track.path != null ? track.path : track.title + "|" + track.artist;
    }

    // ------------------------------------------------------------- MediaStore

    /**
     * Ask the media provider for an album's cached cover.
     *
     * The provider's album art URI and its "album_art" column both predate the
     * public constants (which only appear in API 10), so the URI is written out
     * literally and the column is discovered by name.
     *
     * Two shapes are handled, because implementations differ: the URI may serve
     * the image bytes itself, or the column may hold a path to a file the scanner
     * wrote.
     */
    private static byte[] fromMediaStore(Context context, long albumId) {
        ContentResolver resolver = context.getContentResolver();
        Uri uri = ContentUris.withAppendedId(ALBUM_ART_URI, albumId);

        byte[] direct = readUri(resolver, uri);
        if (direct != null && direct.length > 0) {
            return direct;
        }

        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, null, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            int index = cursor.getColumnIndex(COLUMN_ALBUM_ART);
            if (index < 0 || cursor.isNull(index)) {
                return null;
            }
            // A blob column means the bytes are right here.
            try {
                byte[] blob = cursor.getBlob(index);
                if (blob != null && blob.length > 0) {
                    return blob;
                }
            } catch (Exception notABlob) {
                // Fall through and treat the value as a path.
            }
            String path = cursor.getString(index);
            if (path != null && path.length() > 0) {
                return readFile(new File(path));
            }
            return null;
        } catch (Exception e) {
            // The provider may be missing, or hold no row for this album.
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /** Read a whole stream, refusing anything implausibly large. */
    private static byte[] readUri(ContentResolver resolver, Uri uri) {
        InputStream in = null;
        try {
            in = resolver.openInputStream(uri);
            return in != null ? readAll(in) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // Nothing useful to do.
                }
            }
        }
    }

    private static byte[] readFile(File file) {
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_ART_BYTES) {
            return null;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            return readAll(in);
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // Nothing useful to do.
                }
            }
        }
    }

    private static byte[] readAll(InputStream in) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_ART_BYTES) {
                    return null; // Not a cover image.
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * Decode with a power-of-two sample, so a large cover never allocates a
     * full-size bitmap on the way to a small one.
     */
    private static Bitmap decodeSampled(byte[] data) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }

            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / (sample * 2) >= MAX_EDGE) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** Placeholder tile drawn in code, so no extra asset is needed. */
    public static Bitmap placeholder(int size, int background, int glyph) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(background);

        Paint paint = new Paint();
        paint.setColor(glyph);
        paint.setAntiAlias(true);

        // A simple eighth note, proportioned like the launcher icon.
        float u = size / 24.0f;
        float stemX = 14.0f * u;
        float stemW = 1.8f * u;
        canvas.drawRect(stemX, 4.0f * u, stemX + stemW, 16.5f * u, paint);
        canvas.drawRect(stemX + stemW, 4.0f * u, stemX + stemW + 4.5f * u, 8.0f * u, paint);
        canvas.drawCircle(11.2f * u, 17.2f * u, 4.4f * u, paint);
        return bitmap;
    }
}
