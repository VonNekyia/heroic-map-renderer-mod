---
title: Pflanzen gekippt, Zeit je Chunk
description: Was die gekippten Pflanzen (0049) im Worker je Chunk kosten, vorher gegen nachher im Wechsel, bei 1 bis 16 px; Reihe 1 mit n × n Proben, Reihe 2 mit dem gemittelten Raster.
date: 2026-10-24
commits: [a834226, 4995213, d356b25, b6c0ae3]
code:
  - src/main/java/com/nekyia/heroicmap/ChunkMaler.java
  - src/gametest/java/com/nekyia/heroicmap/Messung.java
---

# Pflanzen gekippt, Zeit je Chunk

Die gekippten Pflanzen kosten im Worker je Chunk rund 0,5 ms mehr, auf jeder
Auflösung fast gleich viel: bei 4 px +18 % (2,76 → 3,26 ms), bei 1 px +36 %,
bei 16 px +1 %. Der Grund: Die flache Fläche nahm das vorgemittelte Raster,
die gekippten Flächen tasten je Pixel n × n Proben ab, je Fläche und Block
256. Das liegt über dem Ziel von +5 %. Mit dem gemittelten Raster
(Reihe 2) sind es noch +7,2 % bei 4 px, +6,0 % bei 8 px und +3,3 % bei
16 px, rund 0,2 bis 0,5 ms je Chunk.

## Aufbau

Wie [Minimap, 8 und 16 px](2026-10-07-minimap-8-16px.md), „Aufbau“, nur das
Zeichnen je Chunk:

- **Welt, Client und Chunks** wie dort: Voreinstellung „normal“, Mittag,
  Sichtweite 12, 854 × 480, je Massstab drei Runden über die geladenen
  Chunks.
- **Schalter:** `-PmessungChunks=true` misst nur das Zeichnen je Chunk und
  hört dann auf; so dauert ein Lauf rund 2 min.
- **Stände:** A ist die Basis `a834226` mit diesem Schalter, B der Stand von
  mod#217 auf derselben Basis (`4995213`). Je Stand ein Worktree, in dem nur
  der Gametest `Messung` eingetragen ist.
- **Reihenfolge:** A B B A A B, je Lauf der Median der drei Runden, je Stand
  der Median der drei Läufe.

## Ablauf

Reihe 1, 24.10., 22:55 bis 23:08, unter der Sperrdatei, ohne die
Lastprüfung des Skills vor jedem Lauf; der Auftrag lautete zuerst, sofort zu
messen. Kein Spiel lief. Last vor jedem Lauf, drei Proben im Abstand von 5 s:

| Lauf | Stand | Zeit | Last vorher |
|---|---|---|---|
| 1 | A | 22:56:43 bis 22:58:42 | 51 / 59 / 51 % |
| 2 | B | 22:58:57 bis 23:00:44 | 10 / 18 / 13 % |
| 3 | B | 23:00:58 bis 23:02:36 | 3 / 9 / 4 % |
| 4 | A | 23:02:51 bis 23:04:27 | 11 / 8 / 1 % |
| 5 | A | 23:04:41 bis 23:06:14 | 10 / 2 / 2 % |
| 6 | B | 23:06:29 bis 23:08:07 | 16 / 22 / 5 % |

- **Lauf 1 ist ein Ausreisser:** Bis 22:56:32 räumte eine andere Sitzung
  Messbäume mit `rmdir` weg, ein Kern und viel Plattenlast. Die erste
  Lastprobe fällt hinein; gemessen wird je Chunk erst nach dem Anlegen der
  Welt, rund eine Minute später, doch Lauf 1 liegt bei 4 px 15 % über den
  beiden anderen Läufen von A.
- **Der erste Anlauf zu Reihe 2** unter der Lastprüfung des Skills wurde
  nach dem Bauen abgebrochen, ehe ein Lauf begann: Der Unterschied war
  schon klar.
- Die Zahlen stammen aus den Dateien der Läufe (`-Pmessung`), ausgewertet je
  Lauf als Median der drei Runden.

## Ergebnis

Zeichnen je Chunk im Worker, Median der Läufe, in Klammern die Spanne:

| Auflösung | A | B | B gegen A |
|---|---|---|---|
| 1 px | 1,97 ms (1,97–2,29) | 2,69 ms (2,60–3,12) | +36 % |
| 2 px | 2,13 ms (2,13–2,51) | 2,67 ms (2,57–2,76) | +25 % |
| 4 px | 2,76 ms (2,73–3,16) | 3,26 ms (3,17–3,55) | +18 % |
| 8 px | 5,30 ms (5,10–5,92) | 5,66 ms (5,65–6,13) | +7 % |
| 16 px | 14,9 ms (14,2–15,3) | 15,0 ms (14,9–15,5) | +1 % |

- Ohne Lauf 1 liegt A bei 4 px bei 2,73 bis 2,76 ms, B bei 3,17 bis 3,55 ms:
  Der Unterschied ist grösser als die Streuung.
- Absolut kostet B rund 0,7 ms mehr bei 1 px, 0,5 ms bei 2 und 4 px, 0,4 ms
  bei 8 px und 0,1 ms bei 16 px.

## Reihe 2: gemitteltes Raster

Gekippte Flächen nehmen hier das gemittelte Raster des Sprites, eine Probe
je Pixel.

- **Stände:** A ist main `d356b25` mit dem Schalter `-PmessungChunks`, B
  mod#217 mit dem Raster (`b6c0ae3`); main enthält dazu #218, das den
  Maler nicht berührt.
- **Lauf:** 25.10., 02:15 bis 02:34, unter der Sperrdatei, vor jedem Lauf
  die Lastprüfung des Skills: 30 s unter 10 %, gemessen 1 bis 6 %. Kein
  Spiel lief.
- **Lauf 1 A ist gestört:** Bis 02:20:39 räumte eine andere Sitzung
  Messbäume mit `rmdir` weg; Lauf 1 lief 02:17:38 bis 02:20:22 ganz darin.
  Ein Kern mit Plattenlast fiel unter 10 % CPU nicht auf. Lauf 7 A ersetzt
  ihn; seine Zahlen liegen gleichauf mit Lauf 1.

| Lauf | Stand | Zeit | 1 px | 2 px | 4 px | 8 px | 16 px |
|---|---|---|---|---|---|---|---|
| 1 | A, gestört | 02:17:38 bis 02:20:22 | 1,958 | 2,111 | 2,697 | 5,164 | 14,51 |
| 2 | B | 02:21:28 bis 02:23:01 | 2,053 | 2,197 | 2,819 | 5,192 | 14,51 |
| 3 | B | 02:23:35 bis 02:25:08 | 2,130 | 2,296 | 2,952 | 5,393 | 14,83 |
| 4 | A | 02:25:55 bis 02:27:26 | 1,924 | 2,094 | 2,670 | 5,055 | 14,26 |
| 5 | A | 02:28:00 bis 02:29:29 | 1,959 | 2,134 | 2,703 | 5,046 | 14,03 |
| 6 | B | 02:30:03 bis 02:31:37 | 2,124 | 2,286 | 2,898 | 5,360 | 14,73 |
| 7 | A | 02:32:20 bis 02:33:50 | 1,934 | 2,105 | 2,716 | 5,108 | 14,38 |

Zeichnen je Chunk in ms, je Lauf der Median der drei Runden. Ohne Lauf 1,
Median der drei Läufe je Stand, in Klammern die Spanne:

| Auflösung | A | B | B gegen A |
|---|---|---|---|
| 1 px | 1,93 ms (1,92–1,96) | 2,12 ms (2,05–2,13) | +0,19 ms, +9,8 % |
| 2 px | 2,11 ms (2,09–2,13) | 2,29 ms (2,20–2,30) | +0,18 ms, +8,6 % |
| 4 px | 2,70 ms (2,67–2,72) | 2,90 ms (2,82–2,95) | +0,20 ms, +7,2 % |
| 8 px | 5,06 ms (5,05–5,11) | 5,36 ms (5,19–5,39) | +0,31 ms, +6,0 % |
| 16 px | 14,26 ms (14,03–14,38) | 14,73 ms (14,51–14,83) | +0,47 ms, +3,3 % |

- Der Unterschied ist grösser als die Streuung; das Ziel von höchstens +5 %
  erreicht B bei 16 px, bei 4 und 8 px nicht.
- Gegen Reihe 1 sinkt der Aufpreis bei 1 px von 0,72 auf 0,19 ms, bei 4 px
  von 0,50 auf 0,20 ms; bei 16 px steigt er von 0,12 auf 0,47 ms, wo das
  Raster texelgenau ist und nichts spart. Ob das an den Ständen liegt (A
  in Reihe 1 ohne, hier mit #218) oder an der Streuung zwischen den
  Reihen, sagt diese Messung nicht; verglichen wird nur innerhalb der
  Reihe.
- **Was bleibt:** Je Pflanze rechnet der Maler das Licht für jede Fläche,
  die nicht liegt, beim `crop` acht Flächen mit je vier Ecken, auch für die
  Hälfte, die nach dem Kippen nach unten zeigt und nicht gezeichnet wird.
  Dazu zeichnet er statt einer Fläche zwei.

## Schluss

- **Woher es kommt:** Je gekippte Fläche und Block tastet der Maler
  scale² Pixel mal n² Proben ab, n = 16 / scale, also 256 Proben auf jeder
  Auflösung. Die flache Fläche deckte den Block ganz und las je Pixel eine
  Zelle des vorgemittelten Rasters (`ChunkMaler.raster`).
- **Entschieden** (24.10.): Der Reviewer wählte, auch gekippte Flächen das
  Raster nehmen zu lassen, eine Probe je Pixel; Ziel: Median höchstens +5 %
  bei 4, 8 und 16 px gegen main, 1 und 2 px mit Zahlen. Höchstens 4 × 4
  Proben hülfe nur bei 1 und 2 px. Der User nimmt die Pflanzen erst mit
  dem Raster.
- Diese Reihe ist keine Vergleichsbasis für spätere: ohne Lastprüfung, mit
  einem Ausreisser.
