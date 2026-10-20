---
title: Minimap, Zoom 0,5
description: Was Zoom 0,5 der Minimap gegen Zoom 2 kostet, je Frame im Stand und im Flug und bis sie nach dem Wechsel ganz gezeichnet ist; je Frame gleich, ganz gezeichnet nach rund 1 s statt 50 ms.
date: 2026-10-20
commits: [3946add]
code:
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/gametest/java/com/nekyia/heroicmap/Messung.java
---

# Minimap, Zoom 0,5

Zoom 0,5 kostet je Frame so viel wie Zoom 2: mit Minimap im Stand 0,304
statt 0,310 ms Frametime im p50, im Flug 0,266 statt 0,262 ms, im
HUD-Element 0,012 bis 0,013 ms wie bei 2. Nach dem Wechsel auf 0,5 ist die
Minimap nach 0,90 bis 1,05 s ganz gezeichnet, bei 2 nach 50 ms. Bei 0,5
zeichnet sie mit 1 px je Block alle 441 Chunks neu, ±10 statt ±4.

## Aufbau

Wie [Minimap, Kosten](2026-10-05-minimap-kosten.md), „Aufbau“, mit diesen
Unterschieden:

- **Läufe:** nur Zoom 2 und 0,5, in dieser Reihenfolge, mit
  `-PmessungZoom=true` (`Messung.zoom`); freie Bildrate, Auflösung 2 px,
  eckig, genordet, 128 Einheiten Seite, GUI-Massstab 2.
- **Ganz gezeichnet:** Vorher steht die Minimap fertig auf der Stufe
  daneben, 1 vor 2 und 2 vor 0,5. Gemessen wird vom Umschalten, bis
  `Minimap.fertig` wahr ist. `waitFor` prüft einmal je Tick; 50 ms heisst
  also „im nächsten Tick“.
- **Je Frame:** ohne und mit Minimap im Wechsel, je drei Runden im Stand und
  im Flug. Im Flug fliegt ohne Minimap immer in die eine Richtung, mit
  Minimap in die andere; ohne gegen mit vergleicht im Flug zwei Strecken.
  Die Stufen vergleicht darum die Frametime mit Minimap, auf derselben
  Strecke.
- **Drei Läufe** nacheinander, je mit neuem Client.

## Ablauf

20.10., 10:28 bis 10:51, `3946add`, beim Researcher unter seiner
Sperrdatei, nach 10 min Ruhe; Last vorher 0 bis 8 %. Drei Läufe ohne
Fehler, Berichte `messung-zoom-139-1.txt` bis `-3.txt`. Ausgewertet als
Median über die 9 Runden der drei Läufe.

## Ergebnis

Frametime in ms, Median der 9 Runden, in Klammern die Spanne der Runden im
p50; HUD ist die Zeit im HUD-Element mit Minimap.

| Zoom | Lauf | mit p50 | mit p95 | ohne p50 | ohne p95 | HUD p50 | HUD p95 |
|---|---|---|---|---|---|---|---|
| 2 | Stand | 0,310 (0,298–0,316) | 0,431 | 0,282 (0,274–0,289) | 0,392 | 0,012 | 0,017 |
| 0,5 | Stand | 0,304 (0,299–0,314) | 0,430 | 0,280 (0,276–0,285) | 0,384 | 0,012 | 0,017 |
| 2 | Flug | 0,262 (0,259–0,278) | 0,412 | 0,290 (0,238–0,297) | 0,401 | 0,013 | 0,017 |
| 0,5 | Flug | 0,266 (0,259–0,273) | 0,403 | 0,287 (0,236–0,296) | 0,400 | 0,013 | 0,018 |

Ganz gezeichnet nach dem Wechsel:

| Zoom | Chunks je Richtung | Lauf 1 | Lauf 2 | Lauf 3 |
|---|---|---|---|---|
| 2 | 4 | 50 ms | 50 ms | 50 ms |
| 0,5 | 10 | 898 ms | 1047 ms | 948 ms |

## Schluss

- **Je Frame** kostet Zoom 0,5 nicht mehr als 2, im Stand wie im Flug und
  im HUD-Element. Mit gegen ohne Minimap im Stand bei beiden +0,02 bis
  +0,03 ms im p50.
- **Im Flug** taugt ohne gegen mit nicht: Die Runden ohne Minimap fliegen
  die andere Strecke, und die erste war schneller als die beiden anderen,
  0,24 gegen 0,29 ms.
- **Ganz gezeichnet** nach rund 1 s: Bei GUI-Massstab 2 zeichnet die
  Minimap bei 0,5 mit 1 px je Block statt 2 px, also alle (2 · 10 + 1)² =
  441 Chunks neu. Bei 1,9 ms je Chunk im Worker, siehe
  [Minimap, Kosten](2026-10-05-minimap-kosten.md), sind das rund 0,84 s.
  Von 1 auf 2 bleibt die Auflösung, und der Bereich wird kleiner; dort ist
  nichts nachzuzeichnen.
