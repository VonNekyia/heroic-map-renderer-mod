---
title: Selbst gezeichnete Karte, Schreiben je Massstab
description: Was Kachelwerk.schreibe je Durchlauf kostet, bei 1, 2 und 4 Pixeln je Block, für eine Farm und einen Flug; drei Läufe in Ruhe unter der Sperre, ohne Minecraft. Ergänzt die Zeiten zur Messung des Platzes vom 10.10.
date: 2026-10-11
commits: [0d6fbba]
code:
  - src/test/java/com/nekyia/heroicmap/KachelwerkMessung.java
  - src/main/java/com/nekyia/heroicmap/Kachelwerk.java
---

# Selbst gezeichnete Karte, Schreiben je Massstab

Im Flug kostet ein Durchlauf der feinen Stufen im Median rund 40 ms bei
1 px, 100 ms bei 2 px und 276 ms bei 4 px; einer über alle Stufen rund
104, 210 und 517 ms. Bei der Farm sind es je Massstab gut 8 ms und 30 bis
36 ms. Die drei Läufe liegen im Flug je Massstab innerhalb von 7 %.

## Aufbau

- **Test:** `KachelwerkMessung` wie in
  [Selbst gezeichnete Karte, Platz je Massstab](2026-10-10-selbst-platz-je-massstab.md),
  ohne Minecraft, ein Kern:

  ```bash
  ./gradlew test --tests '*KachelwerkMessung*' -Pkachelwerk=<datei> --rerun-tasks
  ```

- **Fälle:** Farm, ein Chunk ändert sich je Runde zu 5 s; Flug, je Runde
  6 Reihen zu 25 Chunks dazu. 720 Runden, eine Stunde; jede zwölfte
  schreibt auch die groben Stufen.
- **Chunk:** je Block eine von 16 Zufallsfarben, je Pixel um bis zu ±8 je
  Kanal verrauscht. Das Rauschen kam mit mod#78 dazu; die Messung vom
  09.10., [Schreiben der Kacheln](2026-10-09-selbst-schreiben.md), hatte
  je Block 4 × 4 gleiche Pixel. Ihre Zeiten sind darum nicht mit diesen zu
  vergleichen: PNG packt das Rauschen schwerer, bei 4 px im Flug 105 ms
  damals gegen 276 ms hier. Echtes Gelände liegt dazwischen.
- **Lauf:** 11.10., 02:34 bis 02:54, dreimal mit 15 s Pause, unter der
  Sperrdatei, nach der Messung zu Strahl und Schleier in derselben Sperre;
  kein Spiel des Users, kein Renderer. Kopf `0d6fbba`.

## Ergebnis

Zeit je Durchlauf in ms, Median, je Lauf; „fein“ sind die zwei feinsten
Stufen alle 5 s, „alle“ alle Stufen alle 60 s:

| Fall | Massstab | fein, Lauf 1 | fein, Lauf 2 | fein, Lauf 3 | alle, Lauf 1 | alle, Lauf 2 | alle, Lauf 3 | PNG je Stunde |
|---|---|---|---|---|---|---|---|---|
| Farm | 1 px | 8,62 | 10,08 | 8,33 | 30,14 | 35,12 | 28,62 | 1 740 |
| Farm | 2 px | 8,52 | 8,24 | 8,17 | 33,27 | 32,00 | 31,65 | 1 800 |
| Farm | 4 px | 8,91 | 8,65 | 8,46 | 36,72 | 36,00 | 35,20 | 1 860 |
| Flug | 1 px | 40,94 | 41,46 | 39,34 | 103,41 | 106,62 | 101,84 | 4 276 |
| Flug | 2 px | 104,01 | 100,23 | 97,36 | 217,55 | 207,60 | 204,67 | 7 366 |
| Flug | 4 px | 280,58 | 273,28 | 275,21 | 519,76 | 513,90 | 516,73 | 16 306 |

- **Streuung:** im Flug je Massstab und Stufe innerhalb von 7 %; bei der
  Farm 1 px hängt Lauf 2 um rund 20 % nach oben, die anderen liegen
  innerhalb von 4 %.
- **Je Massstab:** Im Flug kosten 2 px rund das 2,5-Fache von 1 px und 4 px
  rund das 2,7-Fache von 2 px; mit der Fläche in Pixeln wäre es je das
  Vierfache. Bei der Farm kostet der Massstab fast nichts.
- **Last:** Ein Durchlauf alle 5 s von 40 ms ist knapp 1 % eines Kerns im
  Worker mit niedriger Priorität, einer von 276 ms gut 5 %.

## Ablauf

Die Zahlen stammen aus `nacht-kachelwerk-1.txt` bis
`nacht-kachelwerk-3.txt`, die `KachelwerkMessung` geschrieben hat; die PNG je
Stunde zählt der Test aus den Kacheln, die `schreibe` zurückgibt. Sie
gleichen der Messung vom 10.10.
