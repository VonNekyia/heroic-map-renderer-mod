---
title: Selbst gezeichnete Karte, Schreiben der Kacheln
description: Was Kachelwerk.schreibe je Durchlauf kostet und wie viele PNG es je Stunde schreibt, für eine Farm und für einen Flug, mit den feinen Stufen alle 5 s und den groben alle 60 s.
date: 2026-10-09
commits: [7b2eda4]
code:
  - src/main/java/com/nekyia/heroicmap/Kachelwerk.java
  - src/test/java/com/nekyia/heroicmap/KachelwerkMessung.java
---

# Selbst gezeichnete Karte, Schreiben der Kacheln

Ändert sich ein Chunk dauernd, wie an einer Farm, kostet ein Durchlauf der
zwei feinsten Stufen im Median 8,2 ms, einer über alle Stufen 36 ms, und es
entstehen 1 860 PNG je Stunde. Bei einem Flug mit 20 Blöcken/s sind es
105 ms und 324 ms je Durchlauf und 16 306 PNG je Stunde. Beides läuft im
Worker der eigenen Karte, nicht auf dem Render-Thread.

## Aufbau

- **Test:** `KachelwerkMessung`, ohne Minecraft, nur mit
  `-Pkachelwerk=<datei>`:

  ```bash
  ./gradlew test --tests '*KachelwerkMessung*' -Pkachelwerk=messung.txt --rerun-tasks
  ```

- **Werk:** Kacheln 256², 64 Pixel je Chunk, Stufen 0 bis 8, wie
  `Selbst`. Eine Stunde sind 720 Runden zu 5 s; jede Runde legt Chunks und
  schreibt die zwei feinsten Stufen, jede zwölfte alle Stufen, wie
  `Selbst.SCHREIBEN_MS` und `Selbst.GROB_MS`.
- **Chunks:** je Block eine von 16 Farben, 4 × 4 Pixel gleich, aus einem
  festen Zufall; so muss PNG ähnlich viel packen wie bei Gelände.
- **Farm:** je Runde derselbe Chunk.
- **Flug:** je Runde 6 Reihen zu 25 Chunks, die bei 20 Blöcken/s und
  Sichtweite 12 in 5 s neu dazukommen, weiter in x.
- **Platte:** ein `@TempDir` auf der Systemplatte, nach dem Lauf weg.
- **Lauf:** 09.10., 01:46 bis 01:52, Kopf `7b2eda4`, dreimal mit je 15 s
  Pause, unter der Sperrdatei, kein Spiel des Users, Grundlast vorher 10 %.

## Ergebnis

Zeit je Durchlauf in ms, Median und p95, je Lauf:

| Fall | Lauf | fein, Median | fein, p95 | grob, Median | grob, p95 | PNG je Stunde |
|---|---|---|---|---|---|---|
| Farm | 1 | 8,18 | 9,10 | 35,66 | 38,13 | 1 860 |
| Farm | 2 | 8,00 | 9,26 | 35,14 | 37,96 | 1 860 |
| Farm | 3 | 8,34 | 9,51 | 37,12 | 39,38 | 1 860 |
| Flug | 1 | 105,40 | 122,01 | 325,69 | 378,71 | 16 306 |
| Flug | 2 | 103,40 | 117,86 | 319,58 | 380,78 | 16 306 |
| Flug | 3 | 105,55 | 119,65 | 324,02 | 363,60 | 16 306 |

- **Streuung:** Die Mediane liegen je Fall und Stufe innerhalb von 6 %.
- **Farm:** 1 860 PNG je Stunde sind 2 je 5 s, Stufe 8 und 7, und 7 je
  60 s, die Stufen 6 bis 0; so hatte `selbst.md` es gerechnet.
- **Flug:** Ein feiner Durchlauf schreibt rund 20 PNG, je rund 5 ms. Ein
  grober schreibt die gröberen Stufen der letzten Minute mit.
- **Last:** Ein Durchlauf alle 5 s von 105 ms ist gut 2 % eines Kerns im
  Worker mit niedriger Priorität.

## Ablauf

Die Zahlen stammen aus den Dateien `messung-1.txt` bis `messung-3.txt`, die
`KachelwerkMessung` am 09.10. geschrieben hat; die PNG je Stunde zählt der
Test aus den Kacheln, die `schreibe` zurückgibt.
