"""Erzeugt banner.webp für SymboleTest, aus der Wurzel des Repositorys:

    python -I src/test/resources/banner.py

22 × 40 Pixel, nicht quadratisch, verlustfrei mit libwebp wie der Renderer
(method 0, quality 0, exact): ein Banner als WebP mit nur dem Chunk VP8L.
"""
import io
import pathlib

from PIL import Image

BREITE, HOEHE = 22, 40

bild = Image.new("RGBA", (BREITE, HOEHE), (46, 74, 140, 255))
for x in range(BREITE):
    for y in range(HOEHE // 3, HOEHE // 3 + 5):
        bild.putpixel((x, y), (232, 197, 71, 255))
puffer = io.BytesIO()
bild.save(puffer, "WEBP", lossless=True, method=0, quality=0, exact=True)
webp = puffer.getvalue()

# Kopf (RFC 9649): RIFF, WEBP, nur VP8L, Signatur 0x2F, dann LSB zuerst 14 + 14 Bit Grösse − 1.
assert webp[0:4] == b"RIFF" and webp[8:16] == b"WEBPVP8L" and webp[20] == 0x2F
bits = int.from_bytes(webp[21:25], "little")
assert (bits & 0x3FFF) + 1 == BREITE and (bits >> 14 & 0x3FFF) + 1 == HOEHE

pathlib.Path(__file__).with_name("banner.webp").write_bytes(webp)
