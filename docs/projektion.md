---
title: Projektion
description: Wo ein Block der Welt auf Minimap und Vollbildkarte liegt, genordet von oben wie top-north des Renderers, und der Test gegen dessen projektion.json.
code:
  - src/main/java/com/nekyia/heroicmap/Projektion.java
  - src/test/java/com/nekyia/heroicmap/ProjektionTest.java
---

# Projektion

Minimap und Vollbildkarte liegen genordet von oben, wie die Karte
`top-north` des Renderers aus der Richtung `s`: Norden oben, Osten rechts.
Die Ecke des Blocks (x, z) mit den kleinsten Koordinaten liegt bei Pixel
(x · scale, z · scale), die Höhe zählt nicht (`Projektion.zuPixel`). Der
Mod zeichnet mit 1, 2 oder 4 Pixeln je Block.

## Gleich wie der Renderer

Der Renderer legt seine Projektion in
[`projektion.json`](https://github.com/VonNekyia/heroic-map-renderer/blob/master/renderer/tests/fixtures/projektion.json)
fest. `ProjektionTest` lädt die Datei vom Branch `master` des
Hauptrepositorys und prüft jeden Eintrag zu `top-north` aus `s`, Stand
05.10. 66 Einträge bei scale 6, 12, 16, 24, 32 und 48.

- **Scale 4, 2 und 1** stehen nicht in der Datei. Die Formel ist für jeden
  scale dieselbe.
- **Ohne Netz** schlägt der Test fehl. Eine Kopie der Datei im Repository
  gäbe dieselbe Tatsache zweimal.
- **Ändert der Renderer** seine Projektion, wird der Test rot, und der Mod
  zieht nach.
