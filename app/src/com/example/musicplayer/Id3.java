package com.example.musicplayer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Minimal ID3v2 reader, used to pull album art out of an MP3.
 *
 * Deliberately free of Android types so it can be exercised by a plain JVM test
 * (see tools/test_id3.ps1) - byte offset arithmetic is exactly the sort of code
 * that looks right and is not.
 *
 * Handles ID3v2.2 ("PIC"), ID3v2.3 and ID3v2.4 ("APIC"), unsynchronised tags and
 * optional extended headers.
 */
public final class Id3 {

    /** Refuse absurd tags rather than allocating for them. */
    private static final int MAX_TAG_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ART_BYTES = 4 * 1024 * 1024;

    private Id3() {
    }

    /**
     * Attached picture bytes from an MP3, or null when the file has no ID3v2 tag,
     * no picture frame, or a shape this parser does not trust.
     */
    public static byte[] readAlbumArt(File file) {
        if (file == null || !file.isFile() || file.length() < 10) {
            return null;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            return readAlbumArt(in);
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        } finally {
            close(in);
        }
    }

    /** Visible for the JVM test: parse from an arbitrary stream. */
    public static byte[] readAlbumArt(InputStream in) throws IOException {
        byte[] header = new byte[10];
        if (readFully(in, header, 10) != 10) {
            return null;
        }
        if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') {
            return null;
        }

        int major = header[3] & 0xFF;
        if (major < 2 || major > 4) {
            return null;
        }
        int flags = header[5] & 0xFF;
        long tagSize = synchsafe(header, 6);
        if (tagSize <= 0 || tagSize > MAX_TAG_BYTES) {
            return null;
        }

        // Skip an extended header when the tag carries one.
        if ((flags & 0x40) != 0) {
            byte[] ext = new byte[4];
            if (readFully(in, ext, 4) != 4) {
                return null;
            }
            // v2.4 extended header sizes are synchsafe, v2.3 are plain.
            long extSize = (major == 4) ? synchsafe(ext, 0) : beInt(ext, 0);
            if (extSize < 0 || extSize > tagSize || !skipFully(in, extSize)) {
                return null;
            }
            tagSize -= extSize;
        }

        boolean unsynchronised = (flags & 0x80) != 0;
        int idLength = (major == 2) ? 3 : 4;
        int sizeLength = (major == 2) ? 3 : 4;

        byte[] id = new byte[idLength];
        byte[] sizeBytes = new byte[sizeLength];
        long consumed = 0;

        while (consumed + idLength + sizeLength <= tagSize) {
            if (readFully(in, id, idLength) != idLength) {
                return null;
            }
            if (id[0] == 0) {
                return null; // Padding; no picture in this tag.
            }
            if (readFully(in, sizeBytes, sizeLength) != sizeLength) {
                return null;
            }
            consumed += idLength + sizeLength;

            long frameSize = (major == 2) ? be24(sizeBytes, 0)
                    : (major == 4) ? synchsafe(sizeBytes, 0) : beInt(sizeBytes, 0);
            if (frameSize <= 0 || frameSize > tagSize - consumed) {
                return null;
            }

            String frameId = new String(id, "ISO-8859-1");
            boolean isPicture = (major == 2) ? "PIC".equals(frameId) : "APIC".equals(frameId);
            if (isPicture) {
                byte[] frame = new byte[(int) frameSize];
                if (readFully(in, frame, frame.length) != frame.length) {
                    return null;
                }
                if (unsynchronised) {
                    frame = deunsynchronise(frame);
                }
                return pictureFromFrame(frame, major);
            }

            if (!skipFully(in, frameSize)) {
                return null;
            }
            consumed += frameSize;
        }
        return null;
    }

    /** Pull the image bytes out of an APIC/PIC frame body. */
    static byte[] pictureFromFrame(byte[] frame, int major) {
        int pos = 0;
        if (pos >= frame.length) {
            return null;
        }
        int encoding = frame[pos] & 0xFF;
        pos++; // text encoding

        if (major == 2) {
            // 3-byte image format, e.g. "JPG" or "PNG".
            if (pos + 3 > frame.length) {
                return null;
            }
            pos += 3;
        } else {
            // NUL-terminated MIME type, always single-byte.
            while (pos < frame.length && frame[pos] != 0) {
                pos++;
            }
            if (pos >= frame.length) {
                return null;
            }
            pos++; // the NUL
        }

        pos++; // picture type

        // Find where the description ends. The scan stops on a NUL byte; for
        // UTF-16 (encodings 1 and 2) the terminator is a double NUL, so a lone
        // NUL byte is only a stop when the next byte is NUL as well. Note that
        // UTF-16LE text can end with a NUL byte of its own, which makes the run
        // of NULs three bytes long, so the run is skipped rather than a fixed
        // count: assuming a fixed width either ate the first image byte or left
        // a terminator byte on the front of the image.
        int terminators = (encoding == 1 || encoding == 2) ? 2 : 1;
        while (pos < frame.length) {
            if (frame[pos] != 0) {
                pos++;
                continue;
            }
            if (terminators == 1 || (pos + 1 < frame.length && frame[pos + 1] == 0)) {
                break;
            }
            pos++;
        }
        if (pos >= frame.length) {
            return null; // No terminator; do not guess.
        }
        while (pos < frame.length && frame[pos] == 0) {
            pos++; // Step over the terminator run.
        }

        if (pos >= frame.length) {
            return null;
        }
        int length = frame.length - pos;
        if (length <= 0 || length > MAX_ART_BYTES) {
            return null;
        }
        byte[] image = new byte[length];
        System.arraycopy(frame, pos, image, 0, length);
        return image;
    }

    // ---------------------------------------------------------------- helpers

    /** ID3 sizes are 7 bits per byte, most significant first. */
    static long synchsafe(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 4; i++) {
            value = (value << 7) | (data[offset + i] & 0x7F);
        }
        return value;
    }

    static long beInt(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    static long be24(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 16)
                | ((data[offset + 1] & 0xFF) << 8)
                | (data[offset + 2] & 0xFF);
    }

    /** ID3v2.3 unsynchronisation: 0xFF 0x00 encodes a literal 0xFF. */
    static byte[] deunsynchronise(byte[] data) {
        byte[] out = new byte[data.length];
        int written = 0;
        for (int i = 0; i < data.length; i++) {
            out[written++] = data[i];
            if ((data[i] & 0xFF) == 0xFF && i + 1 < data.length && data[i + 1] == 0) {
                i++; // Drop the inserted zero.
            }
        }
        if (written == out.length) {
            return out;
        }
        byte[] trimmed = new byte[written];
        System.arraycopy(out, 0, trimmed, 0, written);
        return trimmed;
    }

    private static int readFully(InputStream in, byte[] buffer, int length) {
        int read = 0;
        try {
            while (read < length) {
                int n = in.read(buffer, read, length - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
        } catch (Exception e) {
            return read;
        }
        return read;
    }

    private static boolean skipFully(InputStream in, long count) {
        long remaining = count;
        try {
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) {
                    if (in.read() < 0) {
                        return false;
                    }
                    remaining--;
                } else {
                    remaining -= skipped;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void close(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
                // Nothing useful to do.
            }
        }
    }
}
