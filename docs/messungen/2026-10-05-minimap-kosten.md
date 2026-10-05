---
title: Minimap, Kosten
description: Was die Minimap je Chunk und je Frame kostet, bei Sichtweite 12 in einer erzeugten Welt, auf dem Render-Thread und mit Worker.
date: 2026-10-05
commits: []
code:
  - src/main/java/com/nekyia/heroicmap/ChunkMaler.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/gametest/java/com/nekyia/heroicmap/Messung.java
---

# Minimap, Kosten

Mit Worker kostet die Minimap den Render-Thread je Chunk im Median 0,09 bis
0,10 ms für den Abzug. Die Frametime im p95 steigt im Flug um 0,03 bis
0,15 ms, im Stand um 0,07 bis 0,36 ms. Das Zeichnen selbst, 2,0 bis 2,6 ms
je Chunk, läuft im Worker. Ohne Worker stieg das p95 im Flug um 1,9 ms,
über die Grenze von 1 ms aus
[0001](../entscheidungen/0001-minimap-mit-dem-tesselator.md).

## Aufbau

- **Welt:** eine Welt, die der Gametest `Messung` neu erzeugt, Voreinstellung
  „normal“, Mittag, klares Wetter, Spieler im Zuschauermodus auf Höhe 160.
- **Client:** Sichtweite 12, Fenster 854 × 480, Bildrate ohne Grenze,
  ohne VSync, `inactivityFpsLimit` auf `minimized`.
- **Je Chunk:** jeder geladene Chunk in Sichtweite, 585 Stück, je Massstab
  drei Runden, auf dem Client-Thread des Gametests nacheinander gemessen.
- **Je Frame:** die Zeit zwischen zwei Frames (`LevelRenderEvents.START_MAIN`),
  Minimap aus und an im Wechsel, je drei Läufe:
  - **Stand:** 5 s, 100 Ticks, ohne Bewegung;
  - **Flug:** 10 s, 200 Ticks, je Tick 1 Block nach Osten, also 20 Blöcke/s
    in neues Gelände; die Minimap hat vor jedem Lauf mit ihr nachgezeichnet.
- **Befehl:** `./gradlew runClientGameTest -Pmessung=<datei>`, unter der
  Sperrdatei, siehe Skill `messung-protokollieren` des Hauptrepositorys.

## Ablauf

Drei Stände nacheinander, je ein Lauf unter der Sperre:

1. **Ohne schnellen Weg:** jede Fläche mit (16 / scale)² Abtastpunkten je
   Pixel, ein Chunk am Stück auf dem Render-Thread. Seine Frametimes sind
   verworfen: Das Spiel drosselte nach 60 s ohne Eingabe auf 30 fps
   (`InactivityFpsLimit.AFK`). Ab Stand 2 ist das abgeschaltet.
2. **Schneller Weg, Spalte für Spalte:** volle Oberseiten aus einem
   gemittelten Raster je Sprite, auf dem Render-Thread, je Frame 2 ms,
   Spalte für Spalte.
3. **Worker:** Abzug auf dem Render-Thread, Zeichnen im Worker, siehe
   [Minimap](../minimap.md), „Neu zeichnen“.

Die Zahlen stammen aus der Ausgabe von `Messung`, 05.10. zwischen 17:47 und
18:06. Beim Start von Stand 2 lief für bis zu 2 s ein javap einer anderen
Sitzung, noch während Gradle startete; die Messphasen begannen eine Minute
später.

## Ergebnis

Je Chunk, Median der drei Runden, in ms:

| Stand | 1 px | 2 px | 4 px |
|---|---|---|---|
| 1, ohne schnellen Weg | 3,58 | 3,78 | 4,40 |
| 2, schneller Weg | 2,53 | 2,45 | 2,95 |
| 3, Abzug auf dem Render-Thread | 0,10 | 0,10 | 0,09 |
| 3, Zeichnen im Worker | 1,99 | 2,09 | 2,61 |

Frametime im p95, in ms, Minimap bei 2 px:

| Stand | Lauf | Stand aus | Stand an | Flug aus | Flug an |
|---|---|---|---|---|---|
| 2 | 1 | 0,467 | 0,567 | 0,395 | 2,325 |
| 2 | 2 | 0,443 | 0,506 | 0,319 | 2,245 |
| 2 | 3 | 0,445 | 0,467 | 0,330 | 2,250 |
| 3 | 1 | 0,556 | 0,624 | 0,584 | 0,676 |
| 3 | 2 | 0,599 | 0,727 | 0,401 | 0,427 |
| 3 | 3 | 0,637 | 1,000 | 0,366 | 0,513 |

## Schluss

- **Der schnelle Weg** spart je Chunk rund ein Drittel.
- **Ohne Worker** nimmt die Minimap im Flug in jedem Frame mit offenen
  Chunks fast ihr ganzes Budget von 2 ms; das p95 steigt um 1,9 ms.
- **Mit Worker** bleibt das p95 auch im Flug unter 0,2 ms über dem Wert ohne
  Minimap und im Stand unter 0,4 ms. Der Worker schafft einen Chunk in
  rund 2 ms, also rund 400 Chunks/s auf einem Kern, weit mehr, als im Flug
  dazukommen.
- **Streuung:** Im dritten Lauf von Stand 3 im Stand mit Minimap lag das
  p95 bei 1,000 ms, in den Läufen davor bei 0,624 und 0,727 ms; die Bildrate
  war dort rund ein Viertel niedriger als im ersten Lauf. Der Unterschied zu
  ohne Minimap bleibt auch dort unter 1 ms.
