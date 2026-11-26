"""Test images for MF-Explorer RC checks R6 (EXIF orientation 6/8), R7 (JPEG without EXIF
preview) and R12 (large WebP/HEIC). Every image shows "TOP" at its upper edge and an arrow
pointing up when it is displayed correctly."""
import random
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import pillow_heif

pillow_heif.register_heif_opener()
OUT = Path(__file__).parent


def picture(width, height, label):
    image = Image.new("RGB", (width, height))
    draw = ImageDraw.Draw(image)
    for y in range(0, height, 8):
        shade = int(255 * y / height)
        draw.rectangle([0, y, width, y + 8], fill=(shade, 90, 255 - shade))
    rng = random.Random(label)
    for _ in range(400):
        x, y = rng.randrange(width), rng.randrange(height)
        r = rng.randrange(width // 80 + 2, width // 20 + 3)
        draw.ellipse([x - r, y - r, x + r, y + r],
                     fill=(rng.randrange(256), rng.randrange(256), rng.randrange(256)))
    big = ImageFont.load_default(size=max(24, height // 12))
    small = ImageFont.load_default(size=max(16, height // 30))
    draw.rectangle([0, 0, width, height // 7], fill=(255, 255, 255))
    draw.text((width // 2, height // 14), "TOP", font=big, fill=(200, 0, 0), anchor="mm")
    cx, top, bottom, w = width // 2, height // 4, height * 3 // 4, width // 10
    draw.polygon([(cx, top), (cx - w, top + w), (cx + w, top + w)], fill=(255, 255, 0))
    draw.rectangle([cx - w // 3, top + w, cx + w // 3, bottom], fill=(255, 255, 0))
    draw.text((width // 2, height - height // 20), label, font=small, fill=(255, 255, 255),
              anchor="mm")
    return image


def jpeg_with_orientation(name, width, height, orientation):
    displayed = picture(width, height, f"{name} (EXIF {orientation})")
    # Orientation 6: the viewer rotates the stored pixels 90 degrees clockwise.
    # Orientation 8: 90 degrees counterclockwise. Store the inverse.
    stored = displayed.transpose(
        Image.Transpose.ROTATE_90 if orientation == 6 else Image.Transpose.ROTATE_270)
    exif = Image.Exif()
    exif[0x0112] = orientation
    stored.save(OUT / name, "JPEG", quality=90, exif=exif.tobytes())


# Portrait when displayed, so a missing rotation shows a landscape picture lying on its side.
jpeg_with_orientation("r6-orient6-small.jpg", 1500, 2000, 6)
jpeg_with_orientation("r6-orient8-small.jpg", 1500, 2000, 8)
# Large enough for the large image view (more than 100 MB as ARGB_8888).
jpeg_with_orientation("r6-orient6-large.jpg", 6000, 8000, 6)
jpeg_with_orientation("r6-orient8-large.jpg", 6000, 8000, 8)
# No EXIF at all, so no embedded preview: the list and viewer must fall back to decoding.
picture(8000, 6000, "r7-no-exif-preview.jpg").save(
    OUT / "r7-no-exif-preview.jpg", "JPEG", quality=92)
picture(8000, 6000, "r12-large.webp").save(OUT / "r12-large.webp", "WEBP", quality=90)
picture(8000, 6000, "r12-large.heic").save(OUT / "r12-large.heic", "HEIF", quality=90)
