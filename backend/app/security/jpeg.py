from __future__ import annotations

import io

from PIL import Image

from app.errors import ImageInvalidError, ImageMetadataNotAllowedError

MAX_PIXELS = 8192 * 8192
MAX_SIDE = 8192

ALLOW_LISTED_MARKERS = {
    0xD8,  # SOI
    0xD9,  # EOI
    0xDA,  # SOS
    0xC0,  # SOF0 (Baseline)
    0xC2,  # SOF2 (Progressive)
    0xC4,  # DHT
    0xDB,  # DQT
    0xDD,  # DRI
    0xE0,  # APP0 (must be JFIF without thumbnail)
    0xE1,  # APP1 (must be Exif with only orientation tag)
}

RESTART_MARKERS = {0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7}


def _validate_exif_tags(exif_bytes: bytes) -> None:
    if len(exif_bytes) < 8:
        raise ImageInvalidError("Truncated Exif header")
    endian_marker = exif_bytes[0:2]
    if endian_marker == b"II":
        byteorder = "little"
    elif endian_marker == b"MM":
        byteorder = "big"
    else:
        raise ImageInvalidError("Invalid TIFF byte order in Exif")

    tag_42 = int.from_bytes(exif_bytes[2:4], byteorder)
    if tag_42 != 42:
        raise ImageInvalidError("Invalid TIFF magic 42 in Exif")

    ifd0_offset = int.from_bytes(exif_bytes[4:8], byteorder)
    if ifd0_offset + 2 > len(exif_bytes):
        raise ImageInvalidError("Truncated IFD0 in Exif")

    num_entries = int.from_bytes(exif_bytes[ifd0_offset : ifd0_offset + 2], byteorder)
    entry_offset = ifd0_offset + 2

    # Allow-list: ONLY orientation tag (0x0112 / 274)
    ALLOWED_TAGS = {0x0112}

    for _ in range(num_entries):
        if entry_offset + 12 > len(exif_bytes):
            raise ImageInvalidError("Truncated IFD0 entry in Exif")
        tag_id = int.from_bytes(exif_bytes[entry_offset : entry_offset + 2], byteorder)
        if tag_id not in ALLOWED_TAGS:
            if tag_id == 0x8825:
                raise ImageMetadataNotAllowedError("GPS metadata is not allowed")
            elif tag_id in (0x0132, 0x9003, 0x9004):
                raise ImageMetadataNotAllowedError("Date-time metadata is not allowed")
            elif tag_id == 0x927C:
                raise ImageMetadataNotAllowedError("Maker notes are not allowed")
            elif tag_id == 0x8769:
                raise ImageMetadataNotAllowedError("ExifIFD sub-IFD metadata is not allowed")
            else:
                raise ImageMetadataNotAllowedError(f"Exif tag 0x{tag_id:04X} is not allowed")
        entry_offset += 12

    # Check for IFD1 (thumbnail IFD)
    if entry_offset + 4 <= len(exif_bytes):
        ifd1_offset = int.from_bytes(exif_bytes[entry_offset : entry_offset + 4], byteorder)
        if ifd1_offset != 0:
            raise ImageMetadataNotAllowedError("Embedded Exif thumbnail (IFD1) is not allowed")


def validate_jpeg(data: bytes) -> tuple[int, int]:
    """Validate JPEG according to doc 7 Section 7 and doc 8 Section 8.3:

    - Starts with JPEG SOI marker (0xFFD8)
    - Decodable by Pillow within max pixel limit (8192x8192)
    - Side dimensions at most 8192 px
    - Allow-listed segments only: SOI, APP0 JFIF (no thumbnail), APP1 Exif (orientation only),
      DQT, SOF0, SOF2, DHT, DRI, SOS, EOI.
    - Rejects XMP, MPF, thumbnails, GPS, date-time, and comments.

    Returns (width, height) on success.
    Raises ImageInvalidError or ImageMetadataNotAllowedError.
    """
    if len(data) < 4:
        raise ImageInvalidError("Image data too short to be a JPEG")
    if data[0:2] != b"\xff\xd8":
        raise ImageInvalidError("Image does not start with SOI marker (0xFFD8)")

    # 1. Pillow check: decompression bomb protection and dimensions
    Image.MAX_IMAGE_PIXELS = MAX_PIXELS
    try:
        with Image.open(io.BytesIO(data)) as img:
            if img.format != "JPEG":
                raise ImageInvalidError(f"Image format is {img.format}, expected JPEG")
            w, h = img.size
            if w > MAX_SIDE or h > MAX_SIDE:
                raise ImageInvalidError(
                    f"Image dimensions {w}x{h} exceed maximum allowed side of {MAX_SIDE}px"
                )
            img.verify()
    except Image.DecompressionBombError as e:
        raise ImageInvalidError(f"Decompression bomb detected: {e}") from e
    except (ImageInvalidError, ImageMetadataNotAllowedError):
        raise
    except Exception as e:
        raise ImageInvalidError(f"JPEG decode failed: {e}") from e

    # 2. Segment allow-list inspection
    offset = 2
    length = len(data)
    has_sos = False

    while offset < length:
        if data[offset] != 0xFF:
            raise ImageInvalidError(f"Expected marker prefix 0xFF at offset {offset}")

        while offset < length and data[offset] == 0xFF:
            offset += 1

        if offset >= length:
            break

        marker = data[offset]
        offset += 1

        if marker == 0xD8:  # SOI
            continue
        if marker == 0xD9:  # EOI
            break
        if marker in RESTART_MARKERS or marker == 0x00:
            continue

        if marker == 0xDA:  # SOS
            has_sos = True
            if offset + 2 > length:
                raise ImageInvalidError("Truncated SOS segment")
            sos_len = int.from_bytes(data[offset : offset + 2], "big")
            offset += sos_len
            # In scan data until next marker
            while offset < length - 1:
                if data[offset] == 0xFF:
                    next_byte = data[offset + 1]
                    if next_byte == 0x00 or (0xD0 <= next_byte <= 0xD7):
                        offset += 2
                    elif next_byte == 0xFF:
                        offset += 1
                    else:
                        break
                else:
                    offset += 1
            continue

        if offset + 2 > length:
            raise ImageInvalidError(f"Truncated segment for marker 0x{marker:02X}")
        seg_len = int.from_bytes(data[offset : offset + 2], "big")
        if seg_len < 2 or offset + seg_len > length:
            raise ImageInvalidError(f"Invalid segment length {seg_len} for marker 0x{marker:02X}")

        seg_payload = data[offset + 2 : offset + seg_len]
        offset += seg_len

        if marker not in ALLOW_LISTED_MARKERS:
            if 0xE2 <= marker <= 0xEF:
                if marker == 0xE2 and seg_payload.startswith(b"MPF\x00"):
                    raise ImageMetadataNotAllowedError("MPF secondary images are not allowed")
                raise ImageMetadataNotAllowedError(f"APP{marker - 0xE0} metadata is not allowed")
            elif marker == 0xFE:
                raise ImageMetadataNotAllowedError("COM (comment) metadata is not allowed")
            else:
                raise ImageMetadataNotAllowedError(f"Prohibited JPEG marker 0x{marker:02X}")

        if marker == 0xE0:  # APP0
            if not seg_payload.startswith(b"JFIF\x00"):
                raise ImageMetadataNotAllowedError("APP0 segment is not standard JFIF")
            if len(seg_payload) < 14:
                raise ImageInvalidError("Malformed JFIF segment")
            x_thumb = seg_payload[12]
            y_thumb = seg_payload[13]
            if x_thumb != 0 or y_thumb != 0:
                raise ImageMetadataNotAllowedError("Embedded JFIF thumbnail is not allowed")

        elif marker == 0xE1:  # APP1
            if seg_payload.startswith(b"http://ns.adobe.com/xap/1.0/\x00"):
                raise ImageMetadataNotAllowedError("XMP metadata is not allowed")
            if not seg_payload.startswith(b"Exif\x00\x00"):
                raise ImageMetadataNotAllowedError("APP1 segment is not Exif")
            _validate_exif_tags(seg_payload[6:])

    if not has_sos:
        raise ImageInvalidError("Missing SOS segment in JPEG")

    return w, h
