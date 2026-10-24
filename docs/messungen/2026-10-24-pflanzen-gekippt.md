---
title: Pflanzen gekippt, Zeit je Chunk
description: Was die gekippten Pflanzen (0049) im Worker je Chunk kosten, vorher gegen nachher im Wechsel, bei 1 bis 16 px; Reihe 1 ohne Lastprüfung, mit einem Ausreisser.
date: 2026-10-24
commits: [a834226, 4995213]
code:
  - src/main/java/com/nekyia/heroicmap/ChunkMaler.java
  - src/gametest/java/com/nekyia/heroicmap/Messung.java
---

# Pflanzen gekippt, Zeit je Chunk

Die gekippten Pflanzen kosten im Worker je Chunk rund 0,5 ms mehr, auf jeder
Auflösung fast gleich viel: bei 4 px +18 % (2,76 → 3,26 ms), bei 1 px +36 %,
bei 16 px +1 %. Der Grund: Die flache Fläche nahm das vorgemittelte Raster,
die gekippten Flächen tasten je Pixel n × n Proben ab, je Fläche und Block
256. Das liegt über dem Ziel von +5 %. Darum nehmen auch die gekippten
Flächen das Raster; Reihe 2 misst das gegen main.

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
- **Reihe 2** unter der Lastprüfung des Skills wurde nach dem Bauen
  abgebrochen, ehe ein Lauf begann: Der Unterschied war schon klar.
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
