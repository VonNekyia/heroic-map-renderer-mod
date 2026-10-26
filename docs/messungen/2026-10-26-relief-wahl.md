---
title: Relief-Wahl, Zeit je Kachel, je Chunk und Platz
description: Was die Relief-Wahl (0053) kostet - (a) je Kachel der Vollbildkarte, wenn der Mod eine andere Art rechnet; (b) je Chunk der Minimap nah mit Relief, dazu Blockänderungen unter der Oberfläche; (c) je Chunk der eigenen Karte mit Schicht, Schreiben und Platz.
date: 2026-10-26
commits: [6dc875b]
code:
  - src/main/java/com/nekyia/heroicmap/Relief.java
  - src/main/java/com/nekyia/heroicmap/Kacheln.java
  - src/main/java/com/nekyia/heroicmap/ChunkMaler.java
  - src/main/java/com/nekyia/heroicmap/Ring.java
  - src/main/java/com/nekyia/heroicmap/Selbst.java
  - src/test/java/com/nekyia/heroicmap/ReliefMessung.java
  - src/gametest/java/com/nekyia/heroicmap/Messung.java
---

# Relief-Wahl, Zeit je Kachel, je Chunk und Platz

Alle drei Ziele aus [0053](../entscheidungen/0053-wahl-des-reliefs.md) sind
erreicht. (a) Rechnet der Mod eine andere Art als die gebackene, kostet eine
Kachel im Faden des Dekoders +99 % (5,92 → 11,78 ms), Ziel höchstens
+100 %; den grössten Teil kostet das Lesen des Rests. (b) Das Zeichnen je
Chunk der Minimap nah bleibt mit Relief im Rauschen (−2,1 bis +1,0 % bei 1
bis 16 px); der Abzug auf dem Render-Thread kostet je Chunk 0,02 ms mehr,
das sind die Höhen. Änderungen unter der Oberfläche malen so viele Chunks
wie Flach. (c) Die eigene Karte braucht im Worker +3,6 bis +4,1 % je Chunk,
zum Schreiben aller Stufen +80 bis +85 % für die zweite PNG, und +7 % Platz
bei 4 px, +21 % bei 1 px.

## Aufbau

- **Stand:** main `6dc875b`, mit mod#227. Für (b) und (c) ein Worktree, in
  dem nur der Gametest `Messung` eingetragen ist.
- **Ablauf:** erst (a), dann (b) und (c), je unter eigener Sperrdatei.
  Reihenfolge je Messung A B B A A B, je Lauf der Median der Runden, je
  Stand der Median der drei Läufe.
- **Last:** vor jedem Lauf 30 s unter 10 %; kam sie in 2 min nicht darunter,
  30 s unter 20 %. Kein Spiel lief.
- **(a) je Kachel:** JUnit `ReliefMessung` über einen Baum der Testwelt von
  Renderer 0.19.0, `top-north-s`, 4 px, mit `slope` gebacken, samt Schicht
  und Rest, 349 Kacheln aller Stufen. 11 Runden über alle Kacheln, die
  erste wärmt nur auf. Je Kachel Lesen und Dekodieren; in B dazu Schicht
  lesen, Rest lesen, wo ein Kanal 255 ist, und rechnen (`Relief.rechne`).
  A ist `slope`, gewählt wie gebacken, nichts zu rechnen; B ist `ohne`.
  Das Füllen der Textur ist in beiden gleich und fehlt.
- **(b) je Chunk der Minimap nah:** Gametest `Messung` mit
  `-PmessungChunks=true`: Welt „normal“, Mittag, Sichtweite 12, 585
  geladene Chunks, je scale 1 bis 16 drei Runden; Abzug auf dem
  Render-Thread und Zeichnen samt Relief. A ist `-PmessungRelief=ohne`
  (Flach), B `slope`.
- **(b) Updates:** `-PmessungUpdates=40`, 4 px, Zoom 4: je Runde 40
  Blockänderungen bei y −50, je in einem anderen Chunk, Zeit, bis die
  Minimap wieder fertig ist, und wie viele Chunks sie malte; drei Runden.
- **(c) eigene Karte:** `-PmessungSelbst=true` in denselben Läufen wie
  (b), `Messung.selbst`: je 1, 2 und 4 px drei Runden über dieselben Chunks
  wie bei „Selbst“ abgezogen, mit Relief samt Höhen und Ring; gemalt, mit
  Relief samt Codes und Schicht, und in die Kacheln gelegt; dann alle
  Stufen in einen leeren Ordner geschrieben. Danach die Bytes von Kacheln
  und Schicht.

## Ablauf

26.10. im Fenster des Reviewers, (a) 13:14 bis 13:28, (b) und (c) 13:29 bis
13:56. Last vor jedem Lauf unter 10 %, wo nicht anders genannt.

| Messung | Lauf | Stand | Zeit |
|---|---|---|---|
| (a) | 1 | A | 13:15:19 bis 13:15:49 |
| (a) | 2 | B | 13:16:23 bis 13:17:18 |
| (a) | 3 | B | 13:17:51 bis 13:18:40 |
| (a) | 4 | A | 13:20:11 bis 13:20:38 |
| (a) | 5 | A | 13:23:48 bis 13:24:20, unter 20 % |
| (a) | 6 | B | 13:27:29 bis 13:28:19, unter 20 % |
| (b), (c) | 1 | A | 13:29:55 bis 13:31:46 |
| (b), (c) | 2 | B | 13:33:04 bis 13:34:53 |
| (b), (c) | 3 | B | 13:35:26 bis 13:37:15 |
| (b), (c) | 4 | A | 13:37:49 bis 13:39:33 |
| (b), (c) | 5 | A | 13:42:40 bis 13:44:28, unter 20 % |
| (b), (c) | 6 | B | 13:45:01 bis 13:46:48 |
| Updates | 1 bis 6 | A B B A A B | 13:47:21 bis 13:55:58 |

## (a) Zeit je Kachel

| Stand | Läufe | Median |
|---|---|---|
| A, `slope`, nichts zu rechnen | 5,917 · 5,863 · 6,876 ms | 5,92 ms |
| B, `ohne`, Schicht und Rest | 13,264 · 11,732 · 11,784 ms | 11,78 ms |

- **B gegen A:** +5,87 ms, +99 %. Ziel höchstens +100 %: erfüllt, knapp.
- **Streuung:** Lauf 5 von A und Lauf 2 von B liegen rund 15 % über den
  beiden anderen ihres Stands.
- **Woraus B besteht,** Mittel je Kachel der Läufe 3 und 6: Lesen und
  Dekodieren 6,5 ms, Schicht 1,9 ms, Rest 3,0 ms, Rechnen 0,25 ms.
- **Wann:** nur, wenn die gewählte Art nicht die gebackene ist, und nur im
  Faden des Dekoders; bis die neue Textur da ist, zeigt die Karte die alte.

## (b) Zeit je Chunk, Minimap nah

Zeichnen je Chunk im Worker, Median der Läufe, in Klammern die Läufe:

| scale | A, Flach | B, Relief | B gegen A |
|---|---|---|---|
| 1 | 2,088 ms (2,089 · 1,961 · 2,088) | 2,109 ms (2,165 · 2,109 · 2,039) | +1,0 % |
| 2 | 2,269 ms (2,329 · 2,116 · 2,269) | 2,290 ms (2,290 · 2,302 · 2,183) | +0,9 % |
| 4 | 2,846 ms (2,895 · 2,689 · 2,846) | 2,838 ms (2,838 · 2,850 · 2,758) | −0,3 % |
| 8 | 5,120 ms (5,120 · 4,901 · 5,142) | 5,057 ms (5,057 · 5,111 · 4,907) | −1,2 % |
| 16 | 14,05 ms (14,05 · 13,66 · 14,46) | 13,76 ms (13,76 · 13,77 · 13,48) | −2,1 % |

- **Zeichnen:** Die Unterschiede sind kleiner als die Spanne der Läufe
  eines Stands. Ziel höchstens +5 % bei 4 und 8 px: erfüllt.
- **Abzug** auf dem Render-Thread: A 0,086 bis 0,089 ms, B 0,105 bis
  0,108 ms je Chunk, +0,02 ms, +21 bis +24 %. Das sind die Höhen, 49 × 49
  Spalten (`ChunkMaler.hoehen`).

**Updates,** je Runde Zeit bis fertig und gemalte Chunks:

| Lauf | Stand | Runde 1 | Runde 2 | Runde 3 |
|---|---|---|---|---|
| 1 | A | 100 ms, 40 | 150 ms, 48 | 100 ms, 40 |
| 2 | B | 100 ms, 40 | 149 ms, 47 | 100 ms, 40 |
| 3 | B | 150 ms, 49 | 100 ms, 40 | 100 ms, 40 |
| 4 | A | 100 ms, 40 | 99 ms, 40 | 100 ms, 47 |
| 5 | A | 100 ms, 40 | 150 ms, 49 | 100 ms, 40 |
| 6 | B | 100 ms, 40 | 149 ms, 48 | 100 ms, 40 |

- A und B malen dieselben Chunks: meist genau die 40 geänderten, je Stand
  in drei Runden 47 bis 49. Das kommt also nicht vom Relief; der Ring malt
  bei Änderungen unter der Oberfläche nichts dazu.
- Die Zeit misst in Ticks zu 50 ms, so lange wartet der Gametest je Schritt;
  sie sagt nur, dass A und B gleich lang brauchen.

## (c) Eigene Karte

| Massstab | Worker A → B | Abzug A → B | Schreiben A → B |
|---|---|---|---|
| 1 px | 1,995 → 2,067 ms, +3,6 % | 0,083 → 0,103 ms | 132 → 243 ms, +85 % |
| 2 px | 2,181 → 2,264 ms, +3,8 % | 0,084 → 0,103 ms | 202 → 364 ms, +80 % |
| 4 px | 2,734 → 2,845 ms, +4,1 % | 0,084 → 0,104 ms | 421 → 760 ms, +81 % |

- **Worker** je Chunk, Spanne der Läufe bei 4 px: A 2,72 bis 2,82 ms, B
  2,74 bis 2,86 ms. Ziel höchstens +5 %: erfüllt, bei 4 px knapp.
- **Schreiben** aller Stufen auf einmal, nach den 585 Chunks: Die Schicht
  ist eine zweite PNG je Kachel. Im Spiel schreibt der Worker geänderte
  Kacheln der zwei feinsten Stufen alle 5 s, die gröberen jede Minute, im
  Hintergrund mit niedriger Priorität, siehe [Selbst](../selbst.md),
  „Kacheln“.

Platz nach denselben 585 Chunks, Median der Läufe von B; die Kacheln sind in
A und B gleich gross:

| Massstab | Kacheln | Bytes der Kacheln | Bytes der Schicht | Schicht gegen Kacheln |
|---|---|---|---|---|
| 1 px | 28 | 121 989 | 25 179 | +20,6 % |
| 2 px | 44 | 417 417 | 48 451 | +11,6 % |
| 4 px | 90 | 1 502 131 | 105 158 | +7,0 % |

- Je Kachel der Schicht rund 0,9 bis 1,2 KB: Sie ist grau, mit wenigen
  Codes, und PNG packt sie gut. Bei 1 px sind die Kacheln selbst klein,
  darum fällt die Schicht dort stärker ins Gewicht.

## Schluss

- **(a)** liegt knapp unter dem Ziel. Die Kosten fallen nur beim Laden an,
  und nur mit einer anderen Art als der gebackenen.
- **(b)** Mit Relief zeichnet die Minimap nah so schnell wie Flach; der
  Ring malt nur, wenn sich Höhen ändern.
- **(c)** Die eigene Karte bleibt im Worker unter +5 %; das Schreiben und
  der Platz wachsen mit der zweiten PNG.
- Die Zahlen sind die Vergleichsbasis für spätere Messungen dieser Teile.
