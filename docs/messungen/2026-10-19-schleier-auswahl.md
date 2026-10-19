---
title: Schleier, Auswahl statt Sortieren
description: Wie viel schneller ein Bau des Schleiers wird, seit er die 5 000 nächsten Stücke auswählt, statt alle zu sortieren, und nur im Kreis teilt (mod#110); Bau je Phase und Kosten je Frame bei 47 Regionen, eine Reihe am 19.10. unter der Sperre.
date: 2026-10-19
commits: [221c2fc, d50f35d]
code:
  - src/main/java/com/nekyia/heroicmap/Schleier.java
  - src/gametest/java/com/nekyia/heroicmap/Messung.java
---

# Schleier, Auswahl statt Sortieren

Ein Bau des Schleiers mit 47 Regionen an der Grenze von 5 000 Vierecken
dauert im Median 3,44 statt 4,31 ms, rund 20 % weniger. Gespart wird beim
Sortieren, 1,34 statt 0,49 ms; das Sammeln wird etwas teurer, die Höhen
bleiben gleich. Je Frame ändert sich nichts. Geschätzt waren „eher 3 als
4,5 ms“, siehe [Wegpunkte](../wegpunkte.md), „Schleier“.

## Aufbau

- **Gametest `Messung`** mit `-PmessungEffekte=true` und 47 angehefteten
  eigenen Regionen, wie in
  [Schleier, Kosten je Frame](2026-10-11-schleier.md): die Minimap bei
  4 px und Zoom 4, eckig, ungedreht, freie Bildrate, der Spieler steht.
- **Je Lauf** drei Runden „Effekte in der Welt“ aus und an, dazu zehn
  Bauten des Schleiers, einzeln gemessen. Die Zeiten je Phase, Sammeln,
  Sortieren und Höhen, misst ein Patch nur für diese Messung, mit
  Zeitstempeln in `Schleier.baue`; er ist nicht in `main`.
- **Stände:** vor = `221c2fc`, volles Sortieren; nach = `d50f35d`,
  Auswahl der 5 000 nächsten (`Schleier.waehle`) und Teilen nur im Kreis
  (`Schleier.imKreis`).
- **Reihe:** am 19.10. von 15:59 bis 16:20 unter der Sperrdatei, nach
  10 min Ruhe, im Wechsel vor, nach, vor, nach, vor. Der sechste Lauf
  (nach) fiel aus: Um 16:18 startete der User das Spiel und um 16:20 einen
  Server. Lauf 5 lief beim Start des Spiels noch gut eine Minute mit;
  seine Werte liegen im Rahmen der anderen beiden „vor“.

## Läufe

Zeiten in ms je Bau; je Frame in ms gegen „aus“, Median und p95.

| Lauf | Stand | Sammeln | Sortieren | Höhen | Bau, Median | Bau, höchstens | je Frame p50 / p95 |
|---|---|---|---|---|---|---|---|
| 1 | vor | 0,572 | 1,276 | 2,410 | 4,307 | 5,187 | +0,507 / +0,828 |
| 2 | nach | 0,627 | 0,515 | 2,585 | 3,474 | 4,716 | +0,501 / +0,871 |
| 3 | vor | 0,571 | 1,335 | 2,636 | 4,282 | 5,940 | +0,494 / +0,803 |
| 4 | nach | 0,767 | 0,459 | 2,636 | 3,401 | 5,664 | +0,508 / +0,879 |
| 5 | vor | 0,568 | 1,341 | 2,551 | 4,353 | 5,181 | +0,494 / +0,889 |

## Gelesen

- **Bau:** im Median 4,31 → 3,44 ms, −20 %. Höchstens bleibt bei rund
  5,2 ms.
- **Sortieren:** 1,34 → 0,49 ms, −64 %.
- **Sammeln:** 0,57 → 0,70 ms, +22 %. Vermutlich kostet das Einengen auf
  den Kreis mehr, als es an Stücken spart; einzeln gemessen ist das nicht.
- **Höhen:** gleich, 2,55 und 2,61 ms; sie sind jetzt drei Viertel des
  Baus.
- **Je Frame:** unverändert, +0,49 bis +0,51 ms. Das war zu erwarten, denn
  #110 ändert nur den Bau, und der läuft höchstens alle 500 ms.
