---
title: Selbst gezeichnete Karte, Platz je Massstab
description: Wie viele PNG die eigene Karte je Stunde schreibt und wie viel Platz sie danach braucht, bei 1, 2 und 4 Pixeln je Block, für eine Farm und einen Flug; ohne Minecraft, mit Kachelwerk. Nur Dateien und Platz, die Zeiten folgen in einer Messung in Ruhe.
date: 2026-10-10
commits: [e86457b]
code:
  - src/test/java/com/nekyia/heroicmap/KachelwerkMessung.java
  - src/main/java/com/nekyia/heroicmap/Kachelwerk.java
---

# Selbst gezeichnete Karte, Platz je Massstab

Eine Stunde Flug mit 20 Blöcken/s schreibt bei 1 px 4 276 PNG und belegt
118 MiB, bei 2 px 7 366 PNG und 477 MiB, bei 4 px 16 306 PNG und
1 877 MiB. Jede Stufe kostet etwa viermal so viel Platz wie die nächst
gröbere, wie die Fläche in Pixeln.

## Aufbau

- `KachelwerkMessung` wie in
  [Selbst gezeichnete Karte, Schreiben der Kacheln](2026-10-09-selbst-schreiben.md),
  jetzt je Massstab: Kacheln von 256 Pixeln, die feinste Stufe 6, 7 oder 8,
  bis Stufe 0.
- **Farm:** ein Chunk ändert sich je Runde zu 5 s. **Flug:** je Runde 6
  Reihen zu 25 Chunks dazu. 720 Runden, eine Stunde; jede zwölfte schreibt
  auch die groben Stufen.
- **Ein Chunk** hat je Block eine von 16 Zufallsfarben, je Pixel um bis zu
  ±8 je Kanal verrauscht, wie gemittelte Texel. Echtes Gelände hat mehr
  gleiche Flächen und packt in PNG besser; die Zahlen zum Platz sind darum
  eher eine obere Schranke.
- **Platz:** die Summe aller Dateien im Ordner nach der Stunde.

## Ablauf

10.10., 22:25 bis 22:32, ohne Sperre, neben anderen Programmen; Befehl
`./gradlew test --tests '*KachelwerkMessung*' -Pkachelwerk=<datei>`. Dateien
und Platz hängen nicht an der Last. Die Zeiten je Durchlauf stehen im
Bericht des Tests, gelten aber nicht: Zeitmessungen laufen nachts in Ruhe
unter Sperre.

## Ergebnis

| Fall | Massstab | PNG je Stunde | Platz danach |
|---|---|---|---|
| Farm | 1 px | 1 740 | unter 0,1 MiB |
| Farm | 2 px | 1 800 | unter 0,1 MiB |
| Farm | 4 px | 1 860 | unter 0,1 MiB |
| Flug | 1 px | 4 276 | 118,1 MiB |
| Flug | 2 px | 7 366 | 476,6 MiB |
| Flug | 4 px | 16 306 | 1 877,1 MiB |

## Schluss

- **Bei 4 px** gleicht die Zahl der PNG der Messung vom 09.10.: Der Weg ist
  derselbe.
- **Platz** wächst etwa mit dem Quadrat der Pixel je Block; 1 px braucht
  im Flug rund ein Sechzehntel von 4 px.
- **Die Farm** bleibt bei jedem Massstab klein: Es sind immer dieselben
  Kacheln, eine je Stufe.
