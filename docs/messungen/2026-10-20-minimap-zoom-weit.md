---
title: Minimap, Zoom 1/32
description: Was Zoom 1/32 der Minimap aus Kacheln gegen Zoom 2 kostet, je Frame im Stand und im Flug und bis sie nach dem Wechsel ganz gezeichnet ist; im Stand gleich, im Flug 0,06 ms mehr im p50, ganz gezeichnet so schnell wie bei 2.
date: 2026-10-20
commits: [ec3eede]
code:
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Kacheln.java
  - src/gametest/java/com/nekyia/heroicmap/Messung.java
---

# Minimap, Zoom 1/32

Zoom 1/32 zeichnet die Minimap aus den Kacheln eines Satzes. Im Stand
kostet das je Frame so viel wie Zoom 2: mit Minimap 0,309 statt 0,308 ms
Frametime im p50. Im Flug sind es 0,327 statt 0,269 ms, 0,06 ms mehr im
p50 und 0,04 ms im p95; im HUD-Element nur 0,001 ms davon. Nach dem
Wechsel ist die Minimap nach 47 bis 48 ms ganz gezeichnet, so schnell wie
bei 2.

## Aufbau

Wie [Minimap, Zoom 0,5](2026-10-20-minimap-zoom-halb.md), „Aufbau“, mit
diesen Unterschieden:

- **Läufe:** Zoom 2 und 1/32, in dieser Reihenfolge, mit
  `-PmessungZoom=0.03125` (`Messung.zoom`).
- **Satz:** Vorher legt die Messung einen Satz aus Rauschen in den Ordner
  der Welt (`Messung.rauschsatz`): 4 px, Kacheln von 256 Pixeln, die Stufen
  bis eine feiner als die, die die Minimap bei 1/32 nimmt, je ±8192 Blöcke
  um den Ursprung. Rauschen packt PNG schlecht; das Dekodieren kostet so
  eher mehr als bei einer echten Karte.
- **Ganz gezeichnet** heisst im Kachelmodus: keine Kachel lädt mehr
  (`Minimap.fertig`).

## Ablauf

20.10., 10:51 bis 11:16, `ec3eede`, beim Researcher unter seiner
Sperrdatei, gleich nach [Minimap, Zoom 0,5](2026-10-20-minimap-zoom-halb.md);
vorher 10 min Ruhe, Last 0 bis 8 %. Drei Läufe ohne Fehler, Berichte
`messung-154-1.txt` bis `-3.txt`. Ausgewertet als Median über die 9 Runden
der drei Läufe.

## Ergebnis

Frametime in ms, Median der 9 Runden, in Klammern die Spanne der Runden im
p50; HUD ist die Zeit im HUD-Element mit Minimap.

| Zoom | Lauf | mit p50 | mit p95 | ohne p50 | ohne p95 | HUD p50 | HUD p95 |
|---|---|---|---|---|---|---|---|
| 2 | Stand | 0,308 (0,301–0,323) | 0,425 | 0,282 (0,275–0,286) | 0,382 | 0,012 | 0,018 |
| 1/32 | Stand | 0,309 (0,303–0,333) | 0,425 | 0,282 (0,277–0,309) | 0,375 | 0,013 | 0,018 |
| 2 | Flug | 0,269 (0,261–0,283) | 0,439 | 0,291 (0,241–0,304) | 0,403 | 0,013 | 0,018 |
| 1/32 | Flug | 0,327 (0,320–0,348) | 0,481 | 0,245 (0,239–0,267) | 0,388 | 0,014 | 0,019 |

Ganz gezeichnet nach dem Wechsel:

| Zoom | Chunks je Richtung | Lauf 1 | Lauf 2 | Lauf 3 |
|---|---|---|---|---|
| 2 | 4 | 50 ms | 50 ms | 50 ms |
| 1/32 | 14 | 48 ms | 47 ms | 48 ms |

## Schluss

- **Im Stand** kostet 1/32 nicht mehr als 2.
- **Im Flug** 0,06 ms mehr im p50, in allen drei Läufen, verglichen mit
  Minimap auf derselben Strecke. Im HUD-Element sind es 0,001 ms; der Rest
  fällt ausserhalb an. Woher, misst die Reihe nicht.
- **Ohne gegen mit** taugt im Flug nicht als Vergleich, wie in
  [Minimap, Zoom 0,5](2026-10-20-minimap-zoom-halb.md), „Schluss“: Die
  Runden ohne Minimap fliegen die andere Strecke und streuen von 0,24 bis
  0,30 ms. Rechnet man trotzdem mit gegen ohne, wirkt 1/32 rund 0,1 ms
  teurer als 2.
- **Ganz gezeichnet** nach weniger als einem Tick: Die Kacheln der groben
  Stufe sind wenige, und die Chunks ruhen im Kachelmodus. Die Reichweite
  von 14 Chunks ist die Sichtweite plus 2 (`Minimap.reichweite`).
