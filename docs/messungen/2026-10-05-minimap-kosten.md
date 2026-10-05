---
title: Minimap, Kosten
description: Was die Minimap je Chunk und je Frame kostet, bei Sichtweite 12, bei freier Bildrate, 144 und 60 fps, mit 2 und 4 Pixeln je Block, auf dem Render-Thread und im Worker.
date: 2026-10-05
commits: [3c74339, f05408b]
code:
  - src/main/java/com/nekyia/heroicmap/ChunkMaler.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/gametest/java/com/nekyia/heroicmap/Messung.java
---

# Minimap, Kosten

Die Minimap kostet den Render-Thread je Chunk im Median 0,08 ms für den
Abzug; das Zeichnen, 1,9 bis 2,6 ms je Chunk, läuft im Worker. Die
Frametime im p95 steigt bei 144 und 60 fps um höchstens 0,06 ms, bei freier
Bildrate um höchstens 0,18 ms, das p99 bei 144 und 60 fps um höchstens
0,45 ms. Gemessen bei Sichtweite 12 mit 4 Pixeln je Block, bei freier
Bildrate auch mit 2. Ohne Worker stieg das p95 im Flug um 1,9 ms, über die
Grenze von 1 ms aus
[0001](../entscheidungen/0001-minimap-mit-dem-tesselator.md).

## Aufbau

- **Welt:** eine Welt, die der Gametest `Messung` neu erzeugt, Voreinstellung
  „normal“, Mittag, klares Wetter, Spieler im Zuschauermodus auf Höhe 160.
- **Client:** Sichtweite 12, Fenster 854 × 480, ohne VSync,
  `inactivityFpsLimit` auf `minimized`. Bildrate ohne Grenze, 144 und 60 fps
  (`framerateLimit`).
- **Je Chunk:** jeder geladene Chunk in Sichtweite, 585 Stück, je Massstab
  drei Runden, auf dem Client-Thread des Gametests nacheinander gemessen,
  Abzug und Zeichnen getrennt.
- **Je Frame:** die Zeit zwischen zwei Frames (`LevelRenderEvents.START_MAIN`),
  Minimap aus und an im Wechsel, je drei Läufe:
  - **Stand:** 5 s, 100 Ticks, ohne Bewegung;
  - **Flug:** 10 s, 200 Ticks, 20 Blöcke/s über dieselbe Strecke von
    200 Blöcken, hin und zurück im Wechsel. Ein ungemessener Durchgang
    vorher lädt die Strecke; der Server erzeugt im Flug nichts mehr.
- **Je Lauf** zählt der Gametest Mittel, p50, p95, p99 und den längsten
  Frame, die Garbage Collections und die übernommenen Chunks.
- **Befehl:** `./gradlew runClientGameTest -Pmessung=<datei>`, unter der
  Sperrdatei, siehe Skill `messung-protokollieren` des Hauptrepositorys.
  Vor jedem Lauf: keine Sperre, kein Spiel, Last unter 10 % für 30 s.

## Ablauf

| Lauf | Zeit | Stand | gültig |
|---|---|---|---|
| A | 05.10., 17:47 bis 18:06 | drei Zwischenstände bis `3c74339` | Zeit je Chunk; die Frametimes sind abgelöst |
| B | 05.10., 23:48 bis 23:59 | `f05408b` | 144 fps (Stand ab Runde 3, Flug) und 60 fps |
| C | 06.10., 00:01 bis 00:11 | `f05408b`, der Gametest wartet auf freie Frames | freie Bildrate; bei 144 und 60 fps nur der Vergleich |

- **Lauf A** verglich drei Stände: ohne schnellen Weg, mit schnellem Weg auf
  dem Render-Thread und mit Worker (`3c74339`). Seine Frametimes flogen in
  neues Gelände und enthielten den Fehler aus Punkt 15 des Reviews: Nach
  einem Lauf „aus“ zeichnete der erste Frame von „an“ den ganzen Bereich neu.
- **Im Takt der Ticks:** Zeitweise zeichnete das Spiel im Gametest einen
  Frame je Tick, 20 fps. In Lauf B betraf das alles bis 23:55; diese Läufe
  sind verworfen, darin auch 3 bis 5 s Last einer anderen Sitzung um 23:53.
  Lauf C wartet deshalb vor jeder Bildrate, bis mehr als 2 Frames je Tick
  kommen.
- **In Lauf C** hatten bei 144 und 60 fps beide Seiten regelmässig lange
  Frames von 28 bis 35 ms, mit und ohne Minimap gleich. Dort gilt der
  Vergleich, nicht die absoluten Werte. Beim Start von Lauf C lief bis
  00:02:00 der Build einer anderen Sitzung, noch während Gradle startete.

## Ergebnis

Je Chunk, Median der drei Runden, in ms:

| Stand | 1 px | 2 px | 4 px |
|---|---|---|---|
| A, ohne schnellen Weg | 3,58 | 3,78 | 4,40 |
| A, schneller Weg | 2,53 | 2,45 | 2,95 |
| C, Abzug auf dem Render-Thread | 0,09 | 0,08 | 0,08 |
| C, Zeichnen im Worker | 1,94 | 2,05 | 2,61 |

Einmalig auf dem Render-Thread: die Kopie der Texel des Block-Atlas, 1310
Sprites, je Lauf 3,5 bis 15 ms; eine neue Region bei 4 px, im Median 0,14
bis 0,17 ms.

Frametime, Minimap an minus aus, je Lauf, in ms:

| Lauf | Bildrate, scale | Art | Mittel | p95 | p99 |
|---|---|---|---|---|---|
| B | 144, 4 px | Stand | +0,00 | +0,00 | −0,06 |
| B | 144, 4 px | Flug | −0,00 bis +0,00 | −0,06 bis +0,00 | −0,21 bis −0,10 |
| B | 60, 4 px | Stand | +0,00 bis +0,03 | −0,01 bis +0,06 | −0,13 bis +0,45 |
| B | 60, 4 px | Flug | −0,00 bis +0,01 | −0,06 bis +0,06 | −0,31 bis +0,08 |
| C | frei, 2 px | Stand | +0,05 bis +0,06 | +0,02 bis +0,03 | – |
| C | frei, 2 px | Flug | −0,18 bis +0,09 | −0,06 bis +0,18 | – |
| C | frei, 4 px | Stand | +0,06 bis +0,08 | +0,03 | – |
| C | frei, 4 px | Flug | −0,20 bis +0,03 | −0,08 bis +0,01 | – |

Bei freier Bildrate liegen p50 bei 0,20 bis 0,29 ms, also 3500 bis
5000 fps; das p99 trägt dort die langen Frames der Umgebung und fehlt. Im
Flug übernahm die Minimap je Lauf 76 bis 117 Chunks. Garbage Collections
fielen mit und ohne Minimap ähnlich viele, bis auf den ersten Flug bei
2 px in Lauf C, 13 gegen 7.

Pause zwischen zwei Abzügen eines Chunks, 60 fps, 4 px, Flug, je drei Läufe
im Wechsel:

| Lauf | Pause | Chunks je Lauf | p99 |
|---|---|---|---|
| B | 0,5 s | 94 bis 107 | 18,36 bis 18,51 ms |
| B | keine | 81 bis 84 | 17,83 bis 17,93 ms |
| C | 0,5 s | 94 bis 109 | 33,03 bis 33,98 ms |
| C | keine | 81 bis 94 | 33,16 bis 33,48 ms |

## Schluss

- **Der Worker** hält den Render-Thread frei: Der Abzug kostet 0,08 ms je
  Chunk, das p95 bleibt bei 144 und 60 fps innerhalb von 0,06 ms, bei
  freier Bildrate innerhalb von 0,18 ms. Die Grenze von 1 ms aus 0001 hält.
- **Ausreisser im p99** aus früheren Läufen kamen vom Flug in neues
  Gelände: Der eingebaute Server erzeugte es im selben Prozess, mit vielen
  Garbage Collections. Über geladenem Gelände sind sie weg.
- **Die Pause** von 0,5 s bringt über geladenem Gelände nichts: In Lauf B
  lag das p99 mit Pause um 0,5 ms höher, und es kamen mehr Chunks, in
  Lauf C nicht. In einem Lauf vom 05.10. um 21:38 mit Flug in neues Gelände,
  noch mit dem Fehler aus Punkt 15, zeichnete die Minimap ohne Pause 1004
  Chunks in 10 s, mit Pause 300 bis 400; das ist nur ein Hinweis. Der Mod
  behält die Pause vorerst, weil das Licht neuer Chunks auf einem Server
  ihre Nachbarn immer wieder markiert.
- **Der schnelle Weg** spart je Chunk rund ein Drittel.
