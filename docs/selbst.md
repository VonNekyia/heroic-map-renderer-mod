---
title: Selbst gezeichnete Karte
description: Die Wahl „Selbst“ - wie der Mod die Chunks, die der Spieler lädt, in eine eigene Karte zeichnet, mit Wahl und Beenden, wann gezeichnet wird, Kacheln als PNG, Pyramide, Kosten, Bild und was fehlt.
code:
  - src/main/java/com/nekyia/heroicmap/Selbst.java
  - src/main/java/com/nekyia/heroicmap/Kachelwerk.java
  - src/main/java/com/nekyia/heroicmap/Pyramide.java
  - src/main/java/com/nekyia/heroicmap/Kacheln.java
  - src/main/java/com/nekyia/heroicmap/Satz.java
  - src/main/java/com/nekyia/heroicmap/Auswahl.java
  - src/main/java/com/nekyia/heroicmap/mixin/LevelExtractorMixin.java
  - src/test/java/com/nekyia/heroicmap/SelbstTest.java
  - src/test/java/com/nekyia/heroicmap/PyramideTest.java
  - src/gametest/java/com/nekyia/heroicmap/Bilder.java
---

# Selbst gezeichnete Karte

Mit der Wahl „Selbst“ zeichnet der Mod die Karte einer Dimension selbst:
jeden Chunk, den der Spieler lädt, mit dem Maler der [Minimap](minimap.md),
4 Pixel je Block, in Kacheln auf der Platte. Die
[Vollbildkarte](vollbildkarte.md) zeigt dann nur diese Karte, nie gemischt
mit der des Servers. Warum: [0004](entscheidungen/0004-karte-selbst-zeichnen.md).

## Wahl

- **Wo:** im Fenster „Karten dieses Servers“ (Knopf „Karte laden …“)
  steht oben „Selbst zeichnen: `<dimension>`“ mit dem Knopf „Selbst“,
  darunter die Massstäbe des Servers. Ein Klick fragt erst im Dialog nach.
  Danach heisst der Knopf „Selbst · an“ und ist aus.
- **Wann:** nur mit einem Ordner der Welt, also auf einem Server, auch ohne
  Plugin, und nicht unter einer Decke (`DimensionType.hasCeiling`), sonst
  fehlt die Zeile (`Selbst.moeglich`).
- **Was:** Die Wahl legt `heroicmap/<welt>/selbst-<dimension>/` an, siehe
  [Download](download.md), „Ablage“, mit `satz.json` (Name „Selbst“,
  Massstab 4) und `4/map.json` (`tileSize` 256, `minZoom` 0, `maxZoom` 8,
  `scale` 4; `Selbst.anlegen`). Im Namen werden andere Zeichen der Kennung
  als `a`–`z`, `0`–`9`, `_` und `-` zu `_`, höchstens 64 Zeichen
  (`Selbst.baum`). Danach zeichnet der Mod gleich alle geladenen Chunks.
- **Vorrang:** Die Vollbildkarte nimmt einen selbst gezeichneten Baum vor
  jedem anderen derselben Dimension (`Satz.fuer`), ohne den Knopf
  „Abgleich“. Downloads vom Server gehen weiter in ihre eigenen Bäume.
- **Beenden:** die Karte in der Kartenliste löschen, siehe
  [Download](download.md), „Kartenliste“. Das Löschen läuft im Worker der
  eigenen Karte, nach allem, was er noch schreibt (`Selbst.loesche`);
  gezeichnet wird dann nicht mehr. Danach zeigt die Vollbildkarte wieder
  die Karte des Servers.

## Wann gezeichnet wird

- **Auslöser:** der Mixin an `LevelExtractor.setSectionDirty`, derselbe wie
  für die Minimap, siehe [Minimap](minimap.md), „Neu zeichnen“: ein Chunk,
  dessen Licht der Client einschaltet, und jede Änderung eines Blocks. Bei
  der Wahl kommen alle geladenen Chunks in Sichtweite dazu.
- **Bereit:** der Chunk und alle 8 Nachbarn geladen und mit Licht
  (`LevelLightEngine.lightOnInColumn`, `Selbst.bereit`). Sonst rechneten
  Schatten und Biomübergang am Rand mit fehlenden Blöcken, und das Licht
  eines neuen Chunks setzt der Client erst später. Chunks am Rand der
  Sichtweite warten, bis der Spieler näher kommt.
- **Je Chunk höchstens alle 5 s** (`Selbst.PAUSE_MS`): Wasser fliesst,
  Getreide wächst.
- **Die Minimap geht vor:** Hat sie zu zeichnen und in der letzten Sekunde
  gearbeitet, wartet die eigene Karte (`Minimap.beschaeftigt`). Ohne HUD,
  etwa mit F1, arbeitet die Minimap nicht; dann wartet niemand auf sie.
- **Last:** je Tick höchstens 1 ms für Abzüge auf dem Render-Thread,
  höchstens 4 Chunks beim Worker, einem eigenen Thread mit niedriger
  Priorität.
- **Wie gezeichnet:** wie die Minimap (`ChunkMaler`), mit den Texturen der
  Packs des Spielers, seinem Biomübergang und dem Licht des Tages, siehe
  [Minimap](minimap.md), „Licht“ und „Was anders ist als top-north“.

## Kacheln

- **Raster:** je Kachel 256 × 256 Pixel, 4 × 4 Chunks, 64 × 64 Blöcke, auf
  Stufe 8. Die Projektion ist die des Renderers, siehe
  [Projektion](projektion.md): Chunk (cx, cz) liegt in Kachel
  (⌊cx / 4⌋, ⌊cz / 4⌋). Ein Chunk ersetzt seinen Ausschnitt ganz
  (`Kachelwerk.lege`).
- **PNG** unter `4/<z>/<x>/<y>.png`, denn Java hat keinen Encoder für WebP.
  `Kacheln` liest `<y>.png`, wenn es keine `<y>.webp` gibt, und prüft wie
  bei WebP die Grösse aus dem Kopf, bevor es dekodiert (`Kacheln.png`).
- **Schreiben** alle 5 s (`Selbst.SCHREIBEN_MS`), beim Wechsel der Welt und
  beim Trennen, über `<y>.png.tmp`; nie liegt eine halbe Kachel da.
  Scheitert das Schreiben, bleibt die Kachel geändert und kommt beim
  nächsten Mal. Was beim Beenden des Spiels noch nicht geschrieben ist,
  fehlt; der Chunk kommt wieder, sobald der Spieler ihn lädt.
- **Im Speicher** bleiben 64 Kacheln, geänderte immer, bis sie geschrieben
  sind.
- **Die offene Vollbildkarte** lädt jede geschriebene Kachel neu
  (`Kacheln.geaendert`); die alte bleibt sichtbar, bis die neue da ist.

## Pyramide

- **Über jeder geschriebenen Kachel** rechnet der Mod ihre Vorfahren bis
  Stufe 0 neu: je 2 × 2 Kacheln, jede auf die halbe Kante verkleinert
  (`Kachelwerk.schreibe`). Eine Kachel der Stufe 0 deckt 16 384 × 16 384
  Blöcke.
- **Verkleinern** wie die Pyramide des Renderers (`Pyramide.halbiere`): je
  2 × 2 Pixel in linearem Licht mit vormultipliziertem Alpha, links oben,
  rechts oben, links unten, rechts unten. Das Verfahren steht in
  [`zoomstufen.md` des Renderers](https://github.com/VonNekyia/heroic-map-renderer/blob/master/docs/benutzung/zoomstufen.md),
  „Verkleinern“; `PyramideTest` prüft dieselben Fälle wie dessen Tests.
- **Rundung:** Java rechnet die sRGB-Kurve in `double`, der Renderer in
  `f32`. Ein Kanal kann deshalb selten um 1 abweichen.

## Kosten

Nicht gemessen. Das Zeichnen kostet je Chunk so viel wie bei der Minimap mit
4 px, siehe [Minimap](minimap.md), „Kosten“: im Median rund 2,6 ms im
Worker und 0,08 ms für den Abzug auf dem Render-Thread. Dazu kommt das
Schreiben der PNG im selben Worker.

## Bild

Der Gametest `Bilder` wählt in der Szene „Selbst“, wartet, bis um den
Spieler alles gezeichnet ist, schreibt und nimmt die Vollbildkarte auf der
feinsten Stufe auf, siehe [Minimap](minimap.md), „Bilder“.

## Was fehlt

- **Nur 4 Pixel je Block.**
- **Nicht im Einzelspieler und nicht unter einer Decke,** siehe „Wahl“.
- **Nur Geladenes:** Was der Spieler nie in Sichtweite hatte, fehlt; einen
  Abgleich mit dem Server gibt es dafür nicht.
