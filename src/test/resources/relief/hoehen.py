"""Schreibt hoehen.bin: je Spalte das y aus der Heightmap WORLD_SURFACE der Testwelt, wie der Renderer es liest
(Chunk::surface), für die Szene der Kacheln in diesem Ordner, mit 32 Blöcken Rand nach Norden und Westen für den
Schatten und 1 nach Osten und Süden. Fehlt ein Chunk, steht dort -32768.

Aufruf aus der Wurzel des Mods: python -I src/test/resources/relief/hoehen.py <ordner der Regionen der Testwelt>
Format: Kopf x0, z0, breite, tiefe als int32 Little Endian, dann je Zeile z die Höhen als int16 Little Endian."""
import os
import struct
import sys
import zlib

X0, Z0 = -2880 - 32, -3392 - 32
BREITE, TIEFE = 128 + 33, 128 + 33
FEHLT = -32768
MIN_Y, HOEHE = -64, 384


def nbt(daten, i, typ):
    """Liest einen Wert vom Typ typ ab i; gibt (wert, neues i)."""
    if typ == 1:
        return daten[i], i + 1
    if typ == 2:
        return struct.unpack_from(">h", daten, i)[0], i + 2
    if typ == 3:
        return struct.unpack_from(">i", daten, i)[0], i + 4
    if typ == 4:
        return struct.unpack_from(">q", daten, i)[0], i + 8
    if typ == 5:
        return None, i + 4
    if typ == 6:
        return None, i + 8
    if typ == 7:
        n = struct.unpack_from(">i", daten, i)[0]
        return None, i + 4 + n
    if typ == 8:
        n = struct.unpack_from(">H", daten, i)[0]
        return daten[i + 2:i + 2 + n].decode("utf-8", "replace"), i + 2 + n
    if typ == 9:
        art, n = daten[i], struct.unpack_from(">i", daten, i + 1)[0]
        i += 5
        for _ in range(n):
            _, i = nbt(daten, i, art)
        return None, i
    if typ == 10:
        aus = {}
        while daten[i] != 0:
            art = daten[i]
            n = struct.unpack_from(">H", daten, i + 1)[0]
            name = daten[i + 3:i + 3 + n].decode("utf-8", "replace")
            wert, i = nbt(daten, i + 3 + n, art)
            aus[name] = wert
        return aus, i + 1
    if typ == 11:
        n = struct.unpack_from(">i", daten, i)[0]
        return None, i + 4 + 4 * n
    if typ == 12:
        n = struct.unpack_from(">i", daten, i)[0]
        return list(struct.unpack_from(">%dq" % n, daten, i + 4)), i + 4 + 8 * n
    raise ValueError("NBT-Typ %d" % typ)


def heightmap(werte):
    """WORLD_SURFACE: je Wert so viele Bits, wie die Höhe der Welt braucht, ohne über ein long zu laufen; Wert 0 heisst leer."""
    bits = (HOEHE).bit_length()
    je = 64 // bits
    aus = []
    for i in range(256):
        lang = werte[i // je] & ((1 << 64) - 1)
        aus.append((lang >> ((i % je) * bits)) & ((1 << bits) - 1))
    return aus


def chunk(regionen, cx, cz):
    datei = os.path.join(regionen, "r.%d.%d.mca" % (cx >> 5, cz >> 5))
    if not os.path.exists(datei):
        return None
    with open(datei, "rb") as f:
        kopf = f.read(4096)
        lage = struct.unpack_from(">I", kopf, 4 * ((cx & 31) + 32 * (cz & 31)))[0]
        if lage == 0:
            return None
        f.seek((lage >> 8) * 4096)
        laenge, art = struct.unpack(">IB", f.read(5))
        if art != 2:
            raise ValueError("Kompression %d" % art)
        daten = zlib.decompress(f.read(laenge - 1))
    wurzel, _ = nbt(daten, 3 + struct.unpack_from(">H", daten, 1)[0], 10)
    karte = (wurzel.get("Heightmaps") or {}).get("WORLD_SURFACE")
    if karte is None:
        return None
    # Wert 0 heisst keine Spalte; sonst y des obersten Blocks + 1 − minY.
    return [None if v == 0 else v - 1 + MIN_Y for v in heightmap(karte)]


def main():
    regionen = sys.argv[1]
    hoehen = [FEHLT] * (BREITE * TIEFE)
    cache = {}
    for z in range(TIEFE):
        for x in range(BREITE):
            wx, wz = X0 + x, Z0 + z
            schluessel = (wx >> 4, wz >> 4)
            if schluessel not in cache:
                cache[schluessel] = chunk(regionen, *schluessel)
            c = cache[schluessel]
            v = None if c is None else c[(wx & 15) + 16 * (wz & 15)]
            hoehen[z * BREITE + x] = FEHLT if v is None else v
    aus = os.path.join(os.path.dirname(os.path.abspath(__file__)), "hoehen.bin")
    with open(aus, "wb") as f:
        f.write(struct.pack("<4i", X0, Z0, BREITE, TIEFE))
        f.write(struct.pack("<%dh" % len(hoehen), *hoehen))
    fehlt = sum(1 for h in hoehen if h == FEHLT)
    print("hoehen.bin:", BREITE, "x", TIEFE, "ab", X0, Z0, "fehlend", fehlt, "Höhen", min(h for h in hoehen if h != FEHLT), "bis", max(hoehen))


if __name__ == "__main__":
    main()
