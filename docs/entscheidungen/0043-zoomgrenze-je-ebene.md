---
title: "0043: Zoomgrenze je Ebene"
description: Wie der Mod `maxBlocksPerPixel` einer Ebene umrechnet, wie bei den Namen 0,625 / Länge eines Blocks in Einheiten des GUI, warum er die Teile jenseits der Grenze empfängt und nur nicht zeichnet, und warum Angeheftetes bleibt.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Ebenen.java
  - src/main/java/com/nekyia/heroicmap/Wegpunkte.java
  - src/main/java/com/nekyia/heroicmap/Karte.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Tafeln.java
---

# 0043: Zoomgrenze je Ebene

## Anlass

Plugin 0.20.0 schickt je Ebene `maxBlocksPerPixel`: Die Ebene ist nur zu
sehen, solange ein Pixel des Bildschirms höchstens so viele Blöcke zeigt,
genau auf der Grenze noch. Den Vertrag beschreibt der Renderer, für die
Webkarte in CSS-Pixeln. Der Mod braucht eine eigene Umrechnung. Plan mit
Plugin Code Map abgestimmt, freigegeben vom Reviewer am 24.10.

## Entscheidung

- **Einheit wie bei den Namen,** siehe
  [0035](0035-namen-weit-draussen-kleiner.md): 10 Einheiten im Mod sind
  16 px der Webkarte. Blöcke je Pixel sind 0,625 / Länge eines Blocks in
  Einheiten des GUI, auf der Vollbildkarte `Karte.blockBreite()`, auf der
  Minimap der Zoom. So verschwindet eine Ebene im Mod bei derselben Grösse
  der Dinge wie im Web, und Namen und Zoomgrenze folgen derselben
  Umrechnung.
- **Empfangen, nicht zeichnen:** Jenseits der Grenze behält der Mod die
  Teile, die das Plugin ohne Anfrage schickt, und zeichnet sie nur nicht.
  Kein Ziel für Zeigen, eine offene Tafel geht zu.
- **Angeheftetes bleibt** jenseits der Grenze, auf Minimap und
  Vollbildkarte, mit seiner Tafel. Der Spieler hat es ausdrücklich gewählt,
  wie bei der Ausnahme der Webkarte für Marker mit offener Tafel.
  Entschieden vom Reviewer (E1); der User kann es ändern.
- **Liste:** angehakt wie gewählt, der Name jenseits der Grenze grau mit
  Tooltip.
- Siehe [Ebenen](../ebenen.md), „Zoomgrenze“.

## Verworfene Alternativen

- **Die Einheit des GUI als Pixel,** Blöcke je Pixel = 1 / Länge eines
  Blocks: Dann verschwände eine Ebene im Mod, wenn die Dinge um den Faktor
  1,6 kleiner stehen als auf der Webkarte, und Namen und Grenze folgten
  zwei Umrechnungen.
- **Den Pixel des Framebuffers:** Die Grenze hinge vom GUI-Massstab ab;
  dieselbe Karte zeigte bei Massstab 2 und 4 verschiedene Ebenen.
- **Teile erst an der Grenze holen,** wie die Webkarte: Das wäre ein neues
  Protokoll mit dem Plugin; die Teile kommen heute ohne Anfrage.
- **Auch Angeheftetes ausblenden,** streng nach der Grenze: Ein Spieler,
  der eine Stadt anheftet, will sie auch weit draussen sehen.

## Folgen

- Der Renderer nennt im Vertrag, was im Mod gilt; das trägt Plugin Code
  Map an Frontend weiter.
- Ohne das Feld gibt es keine Grenze; ältere Server bleiben, wie sie sind.
