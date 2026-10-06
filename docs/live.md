---
title: Live-Ebene
description: Wie der Mod Änderungen seit dem letzten Lauf des Renderers über die Vollbildkarte legt, mit Raster, Texturen, Biomübergang, wann gezeichnet wird, Ablage je Chunk, Abgleich, Kosten, der Abnahme gegen top-north und was bleibt.
code:
  - src/main/java/com/nekyia/heroicmap/Live.java
  - src/main/java/com/nekyia/heroicmap/Ebene.java
  - src/main/java/com/nekyia/heroicmap/Pyramide.java
  - src/main/java/com/nekyia/heroicmap/ChunkMaler.java
  - src/main/java/com/nekyia/heroicmap/Kacheln.java
  - src/main/java/com/nekyia/heroicmap/Downloads.java
  - src/main/java/com/nekyia/heroicmap/mixin/LevelExtractorMixin.java
  - src/test/java/com/nekyia/heroicmap/EbeneTest.java
  - src/test/java/com/nekyia/heroicmap/PyramideTest.java
  - src/test/java/com/nekyia/heroicmap/LiveTest.java
  - src/gametest/java/com/nekyia/heroicmap/Bilder.java
  - src/gametest/java/com/nekyia/heroicmap/Abnahme.java
---

# Live-Ebene

Die Kacheln vom Server zeigen den Stand des letzten Laufs des Renderers.
Was sich seither in Chunks ändert, die der Client geladen hat, zeichnet der
Mod selbst nach und legt es über die [Vollbildkarte](vollbildkarte.md),
bis der nächste [Abgleich](download.md) die Änderung bringt. Gezeichnet
wird mit dem Maler der [Minimap](minimap.md), aber so, wie der Server
zeichnet: `top-north`, 4 Pixel je Block, Texturen und Biomübergang wie
dort.

![Live-Ebene über dem Testsatz](bilder/live.png)

## Raster

- **Zeichnen:** mit `scale` aus `map.json` Pixeln je Block, heute 4 (`Live`).
  Ein Chunk ist dann 64 Pixel breit und liegt auf Pixel- und
  Kachelgrenzen.
- **Verkleinern** auf die feinste Stufe des Satzes, 2 oder 1 Pixel je
  Block, wie die Pyramide des Renderers (`Pyramide`): je 2 × 2 Pixel in
  linearem Licht mit vormultipliziertem Alpha, links oben, rechts oben,
  links unten, rechts unten. Das Verfahren steht in
  [`zoomstufen.md` des Renderers](https://github.com/VonNekyia/heroic-map-renderer/blob/master/docs/benutzung/zoomstufen.md),
  „Verkleinern“; `PyramideTest` prüft dieselben Fälle wie dessen Tests.
- **In gröberen Stufen** verkleinert die Karte weiter, bis ein Chunk
  1 Pixel breit ist (`Ebene.lege`). Darunter mischte ein Pixel Chunks, die
  der Mod nicht hat; dort bleibt die Kachel des Servers.
- **Ersetzen, nicht mischen:** Das Bild eines Chunks ersetzt seinen
  Ausschnitt der Kachel ganz, auch wo es durchsichtig ist. Fehlt dem Server
  die Kachel, entsteht eine durchsichtige mit dem Chunk darin.
- **Rundung:** Java rechnet die sRGB-Kurve in `double`, der Renderer in
  `f32`. Ein Kanal kann deshalb selten um 1 abweichen.

## Texturen

Der Server zeichnet mit den Vanilla-Assets. Die Live-Ebene nimmt deshalb
die Texturen aus `Minecraft.getVanillaPackResources()`, nicht die Packs des
Spielers; sonst sähe man mit einem Pack Flicken (`ChunkMaler.Texel.vanilla`).

- **Je Sprite** die Datei `textures/<pfad>.png` seines Namens, bei einer
  Animation das erste Bild oben, so hoch wie breit.
- **Ohne Vanilla-Datei,** etwa ein Sprite eines Mods, bleiben die Texel aus
  dem Atlas.
- **Die Minimap** bleibt bei den Packs.

## Biomübergang

Der Radius kommt aus `biomeBlend` in `map.json`, nicht aus der Einstellung
des Spielers. Der Maler mischt die Tönung dafür selbst, wie
`ClientLevel.calculateBlockTint` im Spiel: das Mittel über (2r + 1)² Biome
auf gleicher Höhe, je Kanal ganzzahlig geteilt, belegt per javap am Client
26.3 (`ChunkMaler.Mischung`). Fehlt `biomeBlend` oder liegt es nicht
zwischen 0 und 7, gilt die Einstellung des Spielers.

## Wann gezeichnet wird

- **Auslöser:** `LevelExtractor.setBlockDirty(BlockPos, BlockState,
  BlockState)`, der zweite Mixin des Mods (`LevelExtractorMixin`). Dorthin
  führt `ClientLevel.setBlocksDirty` aus `Level.setBlock`: die Vorhersage
  des Spielers, die Antwort des Servers und Änderungen anderer Spieler in
  Sichtweite. Licht und das Laden von Chunks laufen dort nicht durch.
- **Nur was man von oben sieht** (`Live.markiere`):
  - nur, wenn sich das Aussehen des Blocks ändern kann,
    `ModelManager.requiresRender(alt, neu)`; dieselbe Prüfung macht
    `setBlockDirty` danach selbst;
  - nicht unter dem ersten deckenden Block der Spalte (`isSolidRender`),
    gezählt vom obersten Block nach unten (`Live.sichtbar`). Dort endet
    auch die Spalte des Malers. Ein Tunnel, Öfen oder Redstone im Keller
    lassen die Karte so in Ruhe.
- **Am Rand** eines Chunks kommt auch der Nachbar dran, denn sein Schatten
  am Rand ändert sich mit.
- **Je Chunk höchstens alle 5 s** (`Live.PAUSE_MS`): Wasser fliesst,
  Getreide wächst.
- **Nur mit allen 8 Nachbarn geladen und mit Licht;** sonst rechneten
  Schatten und Biomübergang am Rand mit fehlenden Blöcken. Das Licht eines
  neuen Chunks setzt der Client erst später über eine eigene Warteschlange
  (`setLightEnabled`, `LevelLightEngine.lightOnInColumn`); vorher wären
  Spalten zu dunkel oder zu hell. Laufende Lichtarbeit anderswo hält sie
  nicht auf; der Maler liest das Licht aus der Engine. Ist der Chunk selbst
  nicht mehr geladen, fällt er weg.
- **Die Minimap geht vor:** Solange sie sichtbar ist, zu zeichnen hat und
  eben gearbeitet hat, wartet die Live-Ebene. Ohne HUD, etwa mit F1,
  zeichnet die Minimap nicht; dann wartet die Ebene nicht auf sie.
- **Ohne Uhr des Servers** wartet sie auch: Bis das Plugin `jetzt` schickt,
  passten ihre Zeiten nicht zu `abdeckt_bis`, siehe „Ablage“.
- **Scheitert das Ablegen,** kommt der Chunk zurück in die Reihe, mit
  doppelter Pause je Fehlschlag, höchstens fünfmal in Folge
  (`Live.VERSUCHE`). Jede Ursache steht einmal im Log; bei Dateien zählt
  der Grund ohne den Pfad.
- **Je Tick** höchstens ein Abzug auf dem Render-Thread; das Zeichnen läuft
  in einem eigenen Worker mit niedriger Priorität.
- **Nur mit Satz:** Ohne geladenen Satz für die Dimension, in
  Dimensionen mit Decke wie dem Nether und im Einzelspieler zeichnet sie
  nichts.

## Ablage

- **Je Chunk ein PNG** in der Auflösung der feinsten Stufe des Satzes:
  `heroicmap/<server>/<baum>/overlay/<cx>.<cz>.png`, über eine
  Zwischendatei geschrieben (`Ebene`).
- **Die Zeit der Änderung** steht als mtime, in Serverzeit: Der Mod misst
  den Versatz der Uhren an `jetzt` jeder Nachricht des Plugins
  (`Downloads.serverzeit`). Beim Trennen vergisst er ihn.
- **Schrumpfen** kann der Ordner nur über `abdeckt_bis`, siehe „Abgleich“.
  Zwischendateien eines abgebrochenen Schreibens räumt der Mod beim ersten
  Zugriff auf den Satz.
- **Grösse:** roh 16 KiB je Chunk bei 4 px, 4 KiB bei 2 und 1 KiB bei 1;
  als PNG weniger.
- **Bei offener Karte** lädt nach einem neuen Bild nur die Kachel neu, die
  den Chunk zeigt, in jeder Stufe (`Kacheln.geaendert`). Die alte Textur
  bleibt sichtbar, bis die neue da ist; ein Ergebnis, das während einer
  neueren Änderung entstand, verwirft die Karte und dekodiert noch einmal.
- **Gelesen** wird jedes Bild einmal je offener Karte; die gröberen Stufen
  rechnet die Karte daraus. Sie hält höchstens 4096 Bilder, bei 64 × 64
  Pixeln rund 64 MiB, darüber beginnt sie von vorn. Nach einem Abgleich
  liest sie die Ebene neu.
- **Gelesen auf einmal** (`readAllBytes`): Unter Windows scheitert das
  Ersetzen einer Datei, die jemand offen hält. So hält die Karte ein Bild
  nur kurz offen; trifft es doch, legt die Ebene es später wieder ab, siehe
  „Wann gezeichnet wird“.

## Abgleich

Nach jedem vollständigen Download, ob voll oder Abgleich:

- **Weg fällt,** was älter ist als `abdeckt_bis` aus der `freigabe`; das
  enthalten die Kacheln. Wie das Plugin die Zeit rechnet, steht in
  [`docs/download.md` des Plugins](https://github.com/VonNekyia/heroic-map-renderer-plugin/blob/main/docs/download.md),
  „Was die Kacheln abdecken“.
- **Ohne `abdeckt_bis`** bleiben alle Bilder.
- **Anderer Massstab:** Gröber verkleinert der Mod die Bilder, feiner fallen
  sie weg, denn feinere Pixel hat er nicht.
- **Ein gekappter Download** räumt nichts.
- **Lässt sich ein Bild nicht löschen,** räumt der Mod die übrigen und
  schreibt den Fehler ins Log. Scheitert der Wechsel des Massstabs, räumt er
  trotzdem nach `abdeckt_bis`.

## Kosten

- **Je Chunk:** der Abzug auf dem Render-Thread und das Zeichnen im Worker,
  wie bei der Minimap mit 4 Pixeln je Block, siehe
  [Messung](messungen/2026-10-05-minimap-kosten.md). Verkleinern und PNG
  schreiben kommen im Worker dazu.
- **Je Frame** nichts; die Karte legt die Bilder beim Laden einer Kachel im
  Dekoder hinein.
- **Einmal je Atlas** liest der Worker die Vanilla-Texturen.

## Abnahme

Der Gametest `Abnahme` vergleicht die Live-Ebene Pixel für Pixel mit
`top-north` scale 4 des Renderers an der Testwelt. Stand 06.10., Renderer
`654dc08`, Mod `e0659b9`:

- **Ausschnitt:** Blöcke x −192 bis −1, z 256 bis 447, also 12 × 12
  Chunks mit Wald, Strand und Meer.
- **Renderer:** `--render` mit `--camera top-north --scale 4
  --biome-blend 2 --center -96 352 --size 768`, Assets und Daten aus dem
  Client-Jar 26.3, wie der Mod sie hat.
- **Mod:** Der Gametest legt `r.-1.0.mca` und `r.0.0.mca` aus
  `dimensions/minecraft/overworld/region` der Testwelt in eine neue Welt und
  öffnet sie wieder; die Chunks aus 26.2 zieht der Server beim Laden hoch.
  Zufallsticks sind aus (`random_tick_speed` 0). Dann markiert er jeden
  Chunk und wartet, bis jedes der 144 Bilder des Ausschnitts daliegt.
- **Die Testwelt** liegt nicht im Repo, siehe
  [`eingabedaten.md` des Renderers](https://github.com/VonNekyia/heroic-map-renderer/blob/master/docs/entwicklung/eingabedaten.md).
- **Befehl:** `./gradlew runClientGameTest -Pabnahme=<ordner>`; im Ordner
  liegen `regionen/` und `top-north.png`, dorthin schreibt er
  `bericht.txt` und `mod.png`.

| Abweichung je Kanal höchstens | Anteil der Pixel | Schwelle |
|---|---|---|
| 0 | 50,3 % | |
| 1 | 84,5 % | 80 % |
| 8 | 91,1 % | |
| 16 | 99,2 % | 99 % |
| 32 | 99,9 % | 99,8 % |

![Mod, Renderer und Abweichung mal 8, ein Ausschnitt von 6 × 6 Chunks](bilder/live-abnahme.png)

- **Bis 1** weicht ab, was gerundet ist, siehe „Raster“.
- **Bis 16** weicht fast nur Laub ab: Der Renderer mittelt bei
  `dark_cutout` die dunkle Farbe der Löcher mit, der Mod nicht. Dort ist der
  Mod im Mittel um 7 heller.
- **Darüber** liegen 0,1 % der Pixel, einzeln an Ufern und Kanten.
- **Nähte** an den Chunkgrenzen gibt es keine.
- **Die Schwellen** liegen knapp unter dem Gemessenen. Der Ausschnitt ist
  fest, fünf Läufe gaben denselben Bericht. Fällt eine Schwelle, hat sich
  das Bild geändert, im Mod oder im Renderer.
- **Einmal fiel sie:** Ein schnellerer Lauf zeichnete die Chunks am
  nördlichen Rand, bevor der Client ihr Licht gesetzt hatte; sie wurden zu
  dunkel oder zu hell. Seitdem wartet die Ebene auf das Licht, siehe „Wann
  gezeichnet wird“.

## Bild

`docs/bilder/live.png` nimmt der Gametest `Bilder` auf, siehe
[Bauen und testen](entwicklung.md), „Gametests“: Nach der Vollbildkarte
legt er Gold quer über die Grenze zweier Chunks und wartet, bis deren
Bilder abgelegt sind. Die Karte zeigt sie über dem gemalten Testsatz,
dazu zwei Chunks, in denen sich die Szene selbst geändert hat, etwa durch
fliessende Lava. Im Einzelspieler gibt es keinen Satz vom Server; der Test
setzt ihn (`Live.satzFuerTest`).

## Was bleibt eine Näherung

- **Ganze Chunks,** die der Server neu schickt, laufen nicht über
  `setBlock`, etwa nach grossen Bearbeitungen; sie kommen mit dem
  nächsten Abgleich.
- **Am Fuss einer Klippe** grenzt ein Block unter einem deckenden Block
  seiner Spalte an eine tiefere Nachbarspalte. Ändert er sich, ändern sich
  Schatten und Licht des Nachbarn; die Ebene zeichnet das nicht nach, bis
  sich dort etwas Sichtbares ändert oder der Abgleich kommt.
- **Hängt der Autosave des Servers** länger als die Reserve des Plugins,
  fällt beim Abgleich eine Änderung weg, die die Kacheln noch nicht haben.
  Sie fehlt bis zum nächsten Abgleich oder bis der Chunk sich wieder
  ändert.
- **Was anders ist als `top-north`,** gilt auch hier, siehe
  [Minimap](minimap.md), „Was anders ist als top-north“; nur Texturen und
  Biomübergang folgen dem Server.
