---
title: Ebenen
description: Wie der Mod die Ebenen vom Plugin empfängt, in Teilen je version, und ihre Nadeln als Wappenschild mit Namen auf Minimap und Vollbildkarte zeichnet, kleiner beim Hinauszoomen; was noch fehlt.
code:
  - src/main/java/com/nekyia/heroicmap/Ebenen.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Karte.java
  - src/main/java/com/nekyia/heroicmap/Kanal.java
  - src/test/java/com/nekyia/heroicmap/EbenenTest.java
---

# Ebenen

Ein Plugin auf dem Server legt Ebenen über die Karte, etwa die Städte einer
Nation (#35). Der Mod empfängt sie über den Kanal und zeichnet vorerst nur
ihre Nadeln, auf Minimap und Vollbildkarte. Das Format beschreibt der
Renderer:
[Ebenen](https://github.com/VonNekyia/heroic-map-renderer/blob/master/docs/benutzung/ebenen.md);
die Nachrichten das Plugin:
[Ebenen](https://github.com/VonNekyia/heroic-map-renderer-plugin/blob/main/docs/ebenen.md),
„Mod“.

## Empfang

- **`ebenen`:** die Liste, je Ebene `id`, `name`, `visible`, `order` und
  `version` (`Ebenen.liste`). Was nicht mehr darin steht, ist weg, mit
  seinen Nadeln und halben Teilen.
- **`ebene`:** ein Teil einer Ebene, `teil` von `teile`, mit seiner
  `version` (`Ebenen.teil`).
  - Ein Teil gilt nur mit der `version`, die die Liste für seine Ebene
    nennt; das Plugin schickt die Liste vor den Teilen. Welche von zwei
    `version` neuer ist, sagt ein Hash nicht, die Liste schon.
  - Erst wenn alle Teile da sind, ersetzen ihre Nadeln die der Ebene, in
    der Reihenfolge der Teile. Bis dahin bleibt die alte.
  - Nennt die Liste eine neue `version`, verwirft der Mod die halben
    Teile der alten.
- **Vergessen** beim Trennen und bei jedem neuen Login; das Plugin schickt
  danach alles neu.
- **Kaputt:** Eine Nachricht, die sich nicht lesen lässt, ändert nichts.
  Ein kaputtes Objekt fehlt, die übrigen gelten.
- **Gross:** Ein Teil hat bis 64 KiB, ein Teil mit einem einzelnen grossen
  Objekt bis 1 MiB. So viel liest der Kanal, siehe [Download](download.md),
  „Kanal“; sonst würde eine Ebene mit einer grossen Region nie ganz.

## Nadeln

- **Nur `pin`:** Andere Objekte und unbekannte Felder übergeht der Mod.
  `y` braucht er nicht, denn seine Karten sind von oben gesehen. Ohne
  `dimension` gilt `minecraft:overworld`; der Mod zeigt nur die Nadeln der
  Dimension des Spielers.
- **Schild und Nadel** dieselben Bilder wie auf der Webkarte, in drei
  Grössen, je ein Feld in Graustufen und ein Rahmen mit Nadel, als Sprites
  im Atlas des GUI unter `textures/gui/sprites/ebenen/`:

  | Grösse | Bild in Einheiten | Fuss |
  |---|---|---|
  | `large` | 23 × 33 | Mitte der Unterkante |
  | `medium` | 15 × 23 | Mitte der Unterkante |
  | `small` | 9 × 15 | Mitte der Unterkante |

  Ohne `size` gilt `medium`.
- **Farbe:** Die Grafikkarte multipliziert das Feld mit `color`, ohne
  `color` `#D9443A`; das Alpha wirkt nicht. Sie rundet dabei, statt
  abzuschneiden wie die Webkarte; ein Kanal weicht so um höchstens eine
  Stufe ab. So hat es der Reviewer entschieden.
- **Name** in der Schrift des Spiels, mittig 2 Einheiten unter dem Fuss,
  nur in der Grundgrösse.
- **Grösse** (`Ebenen.stufen`): massgebend ist p, wie viele Einheiten ein
  Block breit ist. Ab p = 1/2 steht die Nadel in ihrer Grundgrösse, ab
  1/8 eine kleiner, ab 1/32 zwei kleiner, darunter gar nicht. Kleiner als
  `small` fällt sie weg.
- **Minimap:** p ist der Zoom, also mindestens 1; die Nadeln stehen
  immer in ihrer Grundgrösse. Gezeichnet wird eine Nadel, deren Fuss in
  der Form liegt, auch gedreht (`Minimap.marke`). Schild und Name bleiben
  im Quadrat der Minimap.
- **Vollbildkarte:** p ist der Abstand der Chunklinien durch 16
  (`Kartenblick.chunkAbstand`), der Fuss auf dem Raster der Kacheln wie
  die Wegpunkte.
- **Reihenfolge:** unter Wegpunkten, Mitspielern und dem eigenen Kopf; die
  Ebenen nach `order`, die höhere oben, in einer Ebene in der Reihenfolge
  der Objekte.
- **Verborgen:** Eine Ebene mit `visible: false` zeichnet der Mod nicht.

## Grenzen

Wie im Format, mehr übergeht der Mod:

| Was | Höchstens |
|---|---|
| Ebenen | 64; die übrigen fehlen, das Log nennt es |
| Nadeln je Ebene | 1000 |
| Teile je Ebene | 10 000 |

- **Kosten:** Je Frame geht der Mod alle Nadeln der sichtbaren Ebenen
  durch, im schlimmsten Fall 64 000. Ein Raster nach Regionen kommt erst,
  wenn eine Messung es verlangt.

## Was noch fehlt

- **Symbole** im Schild, 16 × 16 und 9 × 9 Pixel vom Server.
- **Umschalten** je Ebene im Menü.
- **Infotafel** beim Anklicken; Regionen und Kreise (#36).
