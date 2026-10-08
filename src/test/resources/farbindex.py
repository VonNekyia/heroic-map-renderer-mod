"""Erzeugt farbindex.webp für KachelnTest, aus der Wurzel des Repositorys:

    python -I src/test/resources/farbindex.py

8 × 8 Pixel, verlustfrei mit libwebp wie der Renderer (method 0, quality 0,
exact). Die Hälfte ist durchsichtiges Schwarz, die andere 31 Grautöne.
libwebp lässt das Schwarz als letzten Eintrag der Palette weg; die Pixel
zeigen dann hinter die Palette. Siehe docs/vollbildkarte.md, „Farbindex hinter der Palette“.
"""
import io
import pathlib

from PIL import Image

SEITE = 8


def pixel(i):
    """Wie KachelnTest.farbindex: Schachbrett aus Schwarz, Alpha 0, und Grau 8 bis 248."""
    x, y = i % SEITE, i // SEITE
    if (x + y) % 2 == 0:
        return (0, 0, 0, 0)
    v = 8 * (1 + i // 2 % 31)
    return (v, v, v, 255)


bild = Image.new("RGBA", (SEITE, SEITE))
bild.putdata([pixel(i) for i in range(SEITE * SEITE)])
puffer = io.BytesIO()
bild.save(puffer, "WEBP", lossless=True, method=0, quality=0, exact=True)
webp = puffer.getvalue()

# Kopf (RFC 9649): RIFF, WEBP, VP8L, Signatur 0x2F, dann LSB zuerst 14 + 14 Bit
# Grösse, 1 Bit Alpha, 3 Bit Version, 1 Bit „Transformation folgt“, 2 Bit Art
# (3 = Farbindex) und 8 Bit Grösse der Farbtabelle − 1.
assert webp[0:4] == b"RIFF" and webp[8:16] == b"WEBPVP8L" and webp[20] == 0x2F
bits = int.from_bytes(webp[21:27], "little")
assert (bits & 0x3FFF) + 1 == SEITE and (bits >> 14 & 0x3FFF) + 1 == SEITE
assert bits >> 28 & 1 == 1, "Alpha fehlt im Kopf"
assert bits >> 32 & 1 == 1 and bits >> 33 & 3 == 3, "erste Transformation ist nicht der Farbindex"
tabelle = (bits >> 35 & 0xFF) + 1
farben = len(bild.getcolors())

# libwebp liest die Datei verlustfrei. Die Farbtabelle hat weniger Einträge, als
# das Bild Farben hat; die übrigen kommen aus Indizes hinter der Tabelle, und
# die ergeben nur 0x00000000.
assert Image.open(io.BytesIO(webp)).tobytes() == bild.tobytes()
assert tabelle < farben, f"Farbtabelle {tabelle} Einträge, Bild {farben} Farben"

pathlib.Path(__file__).with_name("farbindex.webp").write_bytes(webp)
print(f"farbindex.webp: {len(webp)} Bytes, Farbtabelle {tabelle} Einträge, Bild {farben} Farben")
