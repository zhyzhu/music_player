"""Build synthetic MP3 files with known ID3v2 tags, to test Id3.readAlbumArt.

The parser does hand-written byte arithmetic, which is exactly the kind of code
that looks correct and is not. These fixtures let tools/test_id3.ps1 assert that
the exact image bytes come back out.

Negative cases are marked in a manifest, so "no picture" is never confused with
"an empty picture was extracted".
"""
import json
import os
import struct

OUT = r"C:\Users\zhuzh\Documents\music_player\build\id3-fixtures"

PNG = bytes.fromhex("89504E470D0A1A0A") + b"FAKE-PNG-PAYLOAD-" + bytes(range(64))
JPEG = bytes.fromhex("FFD8FFE0") + b"FAKE-JPEG-PAYLOAD-" + bytes(range(32))
AUDIO = b"\xFF\xFB\x90\x00" + b"\x00" * 400


def synchsafe(value):
    return bytes([
        (value >> 21) & 0x7F,
        (value >> 14) & 0x7F,
        (value >> 7) & 0x7F,
        value & 0x7F,
    ])


def apic_body(image, mime=b"image/png", encoding=0, desc=b"cover"):
    """Body of an APIC frame: encoding, MIME, NUL, type, desc, terminator, image.

    The description terminator is one NUL for the single-byte encodings and two
    for UTF-16, which is what a real writer emits and what the reader must handle.
    """
    terminator = b"\x00\x00" if encoding in (1, 2) else b"\x00"
    return bytes([encoding]) + mime + b"\x00" + b"\x03" + desc + terminator + image


def pic_body(image):
    """Body of an ID3v2.2 PIC frame: encoding, 3-byte format, type, desc, NUL, image."""
    return bytes([0]) + b"PNG" + b"\x03" + b"cover" + b"\x00" + image


def frame_v23(frame_id, body):
    return frame_id + struct.pack(">I", len(body)) + body


def frame_v24(frame_id, body):
    return frame_id + synchsafe(len(body)) + body


def frame_v22(frame_id, body):
    return frame_id + len(body).to_bytes(3, "big") + body


def tag(major, body, unsync=False, extended=False):
    flags = 0
    if unsync:
        flags |= 0x80
    if extended:
        flags |= 0x40
    header = bytes([0x49, 0x44, 0x33, major, 0x00, flags]) + synchsafe(len(body))
    return header + body + AUDIO


def unsynchronise(data):
    out = bytearray()
    for byte in data:
        out.append(byte)
        if byte == 0xFF:
            out.append(0x00)
    return bytes(out)


def build():
    """Return a list of (name, file_bytes, expected_bytes_or_None)."""
    cases = []

    cases.append(("v23_simple", tag(3, frame_v23(b"APIC", apic_body(PNG))), PNG))
    cases.append(("v23_jpeg",
                  tag(3, frame_v23(b"APIC", apic_body(JPEG, mime=b"image/jpeg"))),
                  JPEG))

    # A different frame before the picture: the parser must honour frame sizes.
    text = frame_v23(b"TIT2", b"\x00title")
    cases.append(("v23_text_frame_first",
                  tag(3, text + frame_v23(b"APIC", apic_body(PNG))), PNG))

    # Extended header present: 6 bytes of it, v2.3 layout.
    ext = struct.pack(">I", 6) + b"\x00\x00" + struct.pack(">I", 0)
    cases.append(("v23_extended_header",
                  tag(3, ext + frame_v23(b"APIC", apic_body(PNG)), extended=True),
                  PNG))

    # UTF-16LE description: the terminator is a double NUL, and the description is
    # kept at an even length so the UTF-16LE bytes stay well formed.
    cases.append(("v23_utf16_description",
                  tag(3, frame_v23(b"APIC",
                                   apic_body(PNG, encoding=1,
                                             desc="cov".encode("utf-16-le")))),
                  PNG))

    # UTF-16BE description "cove" (an even number of characters, so the encoded
    # bytes end on a character boundary) followed by the double-NUL terminator.
    # Every character starts with a 0x00, so a scan that stops at the first NUL
    # byte would break out of the description almost immediately.
    cases.append(("v23_utf16be_description",
                  tag(3, frame_v23(b"APIC",
                                   apic_body(PNG, encoding=2,
                                             desc="cove".encode("utf-16-be")))),
                  PNG))

    cases.append(("v24_simple", tag(4, frame_v24(b"APIC", apic_body(PNG))), PNG))
    cases.append(("v22_pic", tag(2, frame_v22(b"PIC", pic_body(PNG))), PNG))

    # Unsynchronised tag: the whole body has 0x00 inserted after every 0xFF and
    # the size in the header accounts for the encoded bytes.
    body = frame_v23(b"APIC", apic_body(PNG))
    encoded = unsynchronise(body)
    cases.append(("v23_unsynchronised", tag(3, encoded, unsync=True), PNG))

    # --- negative cases: must yield nothing, never a wrong image -------------
    cases.append(("no_tag", AUDIO, None))
    cases.append(("tag_without_picture",
                  tag(3, frame_v23(b"TIT2", b"\x00title")), None))
    cases.append(("truncated_header", b"ID3\x03\x00\x00\x00\x00\x00", None))
    cases.append(("bad_version",
                  b"ID3\x07\x00\x00" + synchsafe(16) + b"\x00" * 16, None))
    # Frame claims more bytes than the tag holds.
    cases.append(("overlong_frame",
                  tag(3, b"APIC" + struct.pack(">I", 9999) + b"\x00" * 8), None))

    return cases


def main():
    os.makedirs(OUT, exist_ok=True)
    cases = build()
    manifest = []
    for name, data, expected in cases:
        with open(os.path.join(OUT, name + ".mp3"), "wb") as fh:
            fh.write(data)
        entry = {"name": name, "expectArt": expected is not None}
        if expected is not None:
            with open(os.path.join(OUT, name + ".expected"), "wb") as fh:
                fh.write(expected)
            entry["expectedBytes"] = len(expected)
        manifest.append(entry)

    manifest_path = os.path.join(OUT, "manifest.json")
    with open(manifest_path, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2)

    print("wrote %d fixtures + manifest to %s" % (len(cases), OUT))
    for entry in manifest:
        detail = ("%d bytes" % entry["expectedBytes"]) if entry["expectArt"] else "expect NO art"
        print("  %-26s %s" % (entry["name"], detail))


if __name__ == "__main__":
    main()
