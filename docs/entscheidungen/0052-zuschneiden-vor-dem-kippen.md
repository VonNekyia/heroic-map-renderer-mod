---
title: "0052: Zuschneiden vor dem Kippen"
description: Warum der Mod vor dem Kippen einer Pflanze nur abschneidet, was ausserhalb des Blocks ganz durchsichtig ist, nicht alles Durchsichtige, damit Pakete mit zu hohen Modellen nicht gestaucht werden und die Pflanzen des Spiels bitgleich bleiben.
status: gilt
date: 2026-10-25
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/ChunkMaler.java
---

# 0052: Zuschneiden vor dem Kippen

## Anlass

Der User meldete am 25.10. mit einem Paket für Felder, dass auf der Minimap
nur die Hälfte des Blocks Weizen ist. Das Paket baut reifen Weizen aus
Flächen von y −1/16 bis 31/16; seine Textur ist 32 × 32 und nur im Viertel
links unten deckend. Im Spiel ist die obere Hälfte durchsichtig. Gekippt
nach [0049](0049-pflanzen-gekippt.md) zählte sie beim Einpassen mit; der
sichtbare Weizen lag gestaucht in der Südhälfte. Nachgebaut mit einem
eigenen Testpaket, das Bild vorher und nachher aus dem Gametest.

## Entscheidung

- **Eng:** Vor dem Kippen fällt nur weg, was ausserhalb des Blocks liegt und
  dort im Rechteck der UV ganz durchsichtig ist. Die Regel im Einzelnen steht
  in [Minimap](../minimap.md), „Zuschneiden“. Vorgegeben vom Reviewer am
  25.10.
- **Die Pflanzen des Spiels** bleiben bitgleich, so sehen sie aus wie in
  den Bildern, nach denen der User 0049 gewählt hat.
- **Der Renderer** schneidet nach derselben Regel; Minimap und Webkarte
  bleiben gleich.

## Verworfene Alternativen

- **Auf das ganze deckende Rechteck zuschneiden:** Mohn und Gras haben
  durchsichtige Ränder; gekippt wären sie weniger gestaucht und sähen anders
  aus als gewählt.
- **Nur die gezeichneten Pixel einpassen:** bräuchte das Bild schon vor dem
  Einpassen, je Pflanze zweimal abgetastet.

## Folgen

- Ein Modell, das über den Block reicht und dort deckt, bleibt gestaucht wie
  bisher; das ist gewollt, es ist dann wirklich so hoch.
- Kosten: nur beim Vorrechnen der Draufsicht nach
  [0051](0051-pflanzen-vorgerechnet.md), einmal je Modell und Versatz.
