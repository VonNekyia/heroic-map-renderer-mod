---
title: "0001: Die Minimap mit dem Tesselator des Spiels"
description: Warum die Minimap jeden Block mit dem Tesselator des Spiels zerlegt und die Flächen nach oben rastert, statt Kartenfarben oder einer Mittelfarbe je Block.
status: gilt
date: 2026-10-05
issues: [155]
code:
  - src/main/java/com/nekyia/heroicmap/ChunkMaler.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
---

# 0001: Die Minimap mit dem Tesselator des Spiels

## Anlass

[heroic-map-renderer#155](https://github.com/VonNekyia/heroic-map-renderer/issues/155)
fragt, wie der Mod die Minimap aus den geladenen Chunks zeichnet, wie nah
sie am Stil der Serverkarte liegen soll und was das je Frame kostet. Die
Vollbildkarte kommt als Download der Karte `top-north`, mit 1, 2 oder 4
Pixeln je Block.

## Entscheidung

Die Minimap zerlegt jeden Block mit `ModelBlockRenderer.tesselateBlock` und
rastert die Flächen nach oben mit 1, 2 oder 4 Pixeln je Block, gemittelt wie
der Renderer. Tönung, weiche Beleuchtung, Culling und Varianten rechnet
damit das Spiel; eigener Code bleibt für die Spalte, das Rastern, das
Mitteln und die Lightmap des Tages, siehe [Minimap](../minimap.md).
Freigegeben vom Reviewer am 05.10. unter diesen Bedingungen:

- Gemessen werden der Median je Chunk und die Frametime p95 mit und ohne
  Minimap, bei Sichtweite 12.
- Das Budget bleibt bei 2 ms je Frame. Kostet die Minimap im p95 mehr als
  1 ms zusätzlich, kommt ein Worker dazu.
- Wird es dennoch zu teuer, fällt nur die Stufe 1 px auf Weg B zurück.

## Verworfene Alternativen

- **A, Kartenfarben:** `MapColor` je Block, Relief aus der Höhe wie beim
  Kartengegenstand. Am billigsten, aber 62 Grundfarben, weit weg von
  `top-north`.
- **B, Mittelfarbe:** je Zustand die gemittelte Oberseite mal Tönung, Relief
  aus der Höhe. Bei 1 px nah an der Serverkarte, bei 4 px flache Quadrate
  ohne Textur und ohne weiche Beleuchtung. Der Wechsel von der Minimap zur
  Vollbildkarte sähe anders aus.

## Folgen

- **Teurer** als A und B, je Chunk Millisekunden statt Hundertstel, siehe
  [Minimap](../minimap.md), „Kosten“.
- **Ein Worker** zeichnet, denn auf dem Render-Thread stieg das p95 im Flug
  um 1,9 ms; mit Worker um höchstens 0,15 ms, siehe
  [Minimap, Kosten](../messungen/2026-10-05-minimap-kosten.md).
- **Gleiches Bild** wie der Download, bis auf die Unterschiede in
  [Minimap](../minimap.md), „Was anders ist als top-north“.
- **Tönungen anderer Mods** über `BlockColors` kommen von selbst mit.
