---
title: Bauen und testen
description: Versionen, Gradle, Tests, Gametests, Maustasten, CI und die Prüfung der Doku.
code:
  - build.gradle.kts
  - src/gametest/resources/fabric.mod.json
  - src/gametest/java/com/nekyia/heroicmap/Bedienung.java
  - gradle/libs.versions.toml
  - gradle/wrapper/gradle-wrapper.properties
  - .github/workflows/ci.yml
  - src/main/resources/fabric.mod.json
  - src/main/resources/assets/heroicmap/icon.png
---

# Bauen und testen

Der Mod baut mit Gradle über den Wrapper und Fabric Loom. Er braucht
Java 25; Gradle holt es über die Toolchain in `build.gradle.kts`.

## Versionen

- **Minecraft, Fabric Loader, Fabric API, Loom und JUnit** stehen in
  `gradle/libs.versions.toml`. Minecraft folgt dem Server.
- **Gradle** steht in `gradle/wrapper/gradle-wrapper.properties`.
- **TwelveMonkeys** steht auch in `gradle/libs.versions.toml`. Wer es hebt,
  prüft die Kopie seines Dekoders, siehe
  [Vollbildkarte](vollbildkarte.md), „Farbindex hinter der Palette“.

## Bauen

```bash
./gradlew build
```

Das baut das Jar nach `build/libs/` und lässt die Tests laufen.

## Tests

| Test | prüft |
|---|---|
| `ProjektionTest` | die Projektion gegen `projektion.json` des Renderers, braucht Netz, siehe [Projektion](projektion.md) |
| `LadenTest` | den Download gegen einen kleinen Server auf loopback: Fortsetzen, geänderte und gelöschte Kacheln, Stufen, Prüfsumme, Token, Budget, Stand zum Fortsetzen, Heimnetz, alle harten Grenzen, Fristen, keine Weiterleitung, den Index während des Downloads, den alten Ordner `overlay` beim Start löschen, siehe [Download](download.md) |
| `FreigabeTest` | die `freigabe` lesen, auch mit `port` statt `url`, reservierte Namen, das Präfix der eigenen Karte, und wann der Mod fragt, siehe [Download](download.md), „Zustimmung und Grösse“ |
| `ReiheTest` | Downloads nacheinander, je Baum höchstens einer, auch nach einem `Error`, ein gehaltener Schlüssel beim Löschen, siehe [Download](download.md), „Reihe“ |
| `AblageTest` | der Ordner der Welt je Wahl und je Dimension, Backends hinter einem Proxy, ein neuer Login ohne wartende `freigabe`n, gleiche Hashes, Umzug ins Leere ohne `overlay/`, die Kartenliste über alle Ablagen, unlesbare Ordner, Abbruch, Symlinks und Junctions, siehe [Download](download.md), „Ablage“ |
| `KanalTest` | die `anfrage`, `neu` nur, wenn wahr, und `show`, siehe [Download](download.md), „Kanal“ |
| `AdresseTest` | die Prüfung der Adresse, siehe [Download](download.md), „Sicherheit“ |
| `KartenblickTest` | wie die Vollbildkarte Kacheln auf den Schirm legt, Zoom über Stufen und Lupe, Schieben, Platzhalter aus gröberen Stufen, siehe [Vollbildkarte](vollbildkarte.md); Marken auf dem Raster der Kacheln und am Rand neben den Knöpfen, siehe [Wegpunkte](wegpunkte.md), „Am Rand“; Chunklinien auf dem Raster bis zum Rand und nicht zu dicht, auch bei GUI-Massstab 1 auf 4K, siehe [Minimap](minimap.md), „Chunklinien“ |
| `TeleportTest` | der Befehl zum Teleportieren und dass es ihn nur mit `execute` und `tp` im Befehlsbaum gibt, siehe [Vollbildkarte](vollbildkarte.md), „Teleportieren“ |
| `DrehungTest` | wie die Minimap ihr Bild auf den Schirm bringt: Schnitt konvexer Vielecke, Vieleck um den Kreis, Rand mit und ohne Rahmen gedeckt, mit Abstand gegen das Einrasten, Umriss wie auf main, ungedreht auf dem Raster, Fächer, Chunklinien in der Form, siehe [Minimap](minimap.md), „Form“; Blickrichtung oben, Drehung und zurück, Marken auf der Mitte der Bänder und auf ganzen Pixeln, Reichweite √2, Wegpunkt in Blickrichtung, siehe [Minimap](minimap.md), „Drehen“ |
| `KachelnTest` | WebP lesen, samt Alpha, die Grösse aus dem Kopf vor dem Dekodieren, siehe [Vollbildkarte](vollbildkarte.md), „Kacheln“; Pixel mit einem Index hinter der Palette durchsichtig, alle anderen wie TwelveMonkeys, siehe [Vollbildkarte](vollbildkarte.md), „Farbindex hinter der Palette“ |
| `SatzTest` | den Satz zur Dimension finden, zwei Bäume nach dem Namen, Grenzen für `map.json`, siehe [Vollbildkarte](vollbildkarte.md), „Welcher Satz“ |
| `EbenenTest` | die Ebenen vom Plugin: Liste, Teile in jeder Reihenfolge, die alte Ebene bis die neue ganz ist, halbe verworfen, nur Nadeln mit ihren Vorgaben, Grenzen, kaputte Nachrichten, Reihenfolge und Name, Stufen nach der Breite eines Blocks, Farben, siehe [Ebenen](ebenen.md) |
| `MitspielerTest` | die Nachricht `spieler` lesen, kaputte Einträge, Obergrenze, verfallen nach 5 s und beim Verlassen, die Antwort auf `show`, der Grund ohne Plugin, siehe [Minimap](minimap.md), „Mitspieler“ |
| `MinimapTest` | den Bereich der Minimap, siehe [Minimap](minimap.md), „Neu zeichnen“; Form, Lage, Einstellungen und Auflösung, siehe [Minimap](minimap.md), „Bedienung“; Grösse des Kopfes, Marken am Rand und auf dem Pixel der Karte, siehe [Wegpunkte](wegpunkte.md); Chunklinien auf dem Raster der Karte, erste und letzte im Bild, Kreuzungen einfach gedeckt, siehe [Minimap](minimap.md), „Chunklinien“ |
| `SkinTest` | die Rahmen: Palette lesen, die sechs Skins des Pakets, Fehlschlag gemerkt, Bänder eckig nach der Regel, Ring rund in den Bändern, Ornamente auf der Mitte der Bänder, Spiegeln je Ecke, Griff zur Mitte mit 9 × 9, Abstand zum Rand, siehe [Rahmen](rahmen.md) |
| `SelbstTest` | die selbst gezeichnete Karte: Chunk in seiner Kachel, Vorfahr aus vier Kindern, nur das geänderte Viertel, gröbere Stufen später, Schreiben scheitert, dauerhaft scheitert, Speicher bei 64, kaputte PNG, Baum je Dimension, Präfix und Marke, PNG mit falscher Grösse, siehe [Selbst gezeichnete Karte](selbst.md) |
| `KachelwerkMessung` | nur mit `-Pkachelwerk=<datei>`: was `Kachelwerk.schreibe` kostet, siehe [Selbst gezeichnete Karte](selbst.md), „Kosten“ |
| `PyramideTest` | Verkleinern wie die Pyramide des Renderers, siehe [Selbst gezeichnete Karte](selbst.md), „Pyramide“ |
| `WegpunkteTest` | Wegpunkte setzen, löschen, anheften, Farben, kaputte Einträge und Dateien, siehe [Wegpunkte](wegpunkte.md) |
| `LichtTest` | die Lightmap gegen die Werte aus der Doku des Renderers, siehe [Minimap](minimap.md), „Licht“ |

## Gametests

Die Client-Gametests starten das Spiel mit einem Fenster und laufen nicht
in der CI:

```bash
./gradlew runClientGameTest
```

Mit `-Pzusatzmods=<ordner>` lädt das Spiel im Gametest zusätzlich die
Mods aus diesem Ordner (`fabric.addMods`), etwa die eines Modpacks ohne
Fabric API und ohne diesen Mod. So lässt sich prüfen, ob eine andere Mod
stört; seit 26.x sind die Namen im Spiel nicht mehr verschleiert, fertige
Mods laufen deshalb auch hier.

| Gametest | tut |
|---|---|
| `Anmeldung` | die Reihenfolge beim Login: Beim ersten Level gibt es den Spieler noch nicht, wohl aber die Verbindung des Levels, aus der `Downloads.server` liest, siehe [Download](download.md), „Ablage“ |
| `Bilder` | baut eine Szene und nimmt die Minimap auf, danach das Menü und die Vollbildkarte aus einem Testsatz mit zwei Wegpunkten, das Untermenü „Einstellungen …“, jeden Rahmen eckig und rund und das Menü mit Rahmen, die drehende Minimap, zuletzt die selbst gezeichnete Karte der Szene, gewählt über die Knöpfe, neben der Minimap, die als beschäftigt gilt, auch mit Chunklinien; mit `-Pbilder=<ordner>` landen die Bilder dort, siehe [Minimap](minimap.md), „Bilder“, und [Vollbildkarte](vollbildkarte.md), „Bild“ |
| `Bedienung` | das Menü und die Vollbildkarte mit echten Eingaben der Maus (`TestInput`): im Menü verschiebt Ziehen mit der linken wie der rechten Taste die ganze Minimap; auf der Karte verschiebt links ziehen den Inhalt, ein Linksklick öffnet kein Menü, ein Rechtsklick öffnet „Hierher teleportieren“, ein Klick darauf teleportiert; Wegpunkte setzen, ziehen und klicken auf Marken am Rand, Doppelklick, Wegpunkt am eigenen Standort; Menü und Untermenü bei 1280 × 720 und GUI-Massstab 3; siehe [Minimap](minimap.md), „Bedienung“, [Vollbildkarte](vollbildkarte.md), „Bedienung“, und [Wegpunkte](wegpunkte.md), „Tests“ |
| `Messung` | nur mit `-Pmessung=<datei>`: Zeit je Chunk und Frametime mit und ohne Minimap, siehe [Minimap](minimap.md), „Kosten“ |
| `Server` | nur mit `-Pserver=<adresse>`: von Ende zu Ende gegen einen echten Paper-Server mit dem Plugin, Angebot, voller Download des kleinsten Massstabs des ersten Baums, jeder Dialog mit Ja, die Vollbildkarte als Bild `server-karte`; siehe unten |
| `Uebernahme` | nur mit `-Puebernahme=<datei>`: was eine Kachel der Vollbildkarte den Render-Thread kostet, siehe [Vollbildkarte](vollbildkarte.md), „Kacheln“ |

### Maustasten

- **Seit 26.3 zählt das Spiel die Maustasten wie SDL:** links 1, Mitte 2,
  rechts 3 (`InputConstants.MOUSE_BUTTON_LEFT`, `MOUSE_BUTTON_MIDDLE`,
  `MOUSE_BUTTON_RIGHT`), nicht 0, 2 und 1 wie GLFW. `SDLEventHandler`
  reicht die Taste von SDL unverändert in `MouseButtonInfo`, und diese
  Zahl gibt `MouseButtonEvent.button()`; belegt per javap am Client 26.3
  (`SDLEventHandler`, `InputConstants`, `MouseHandler.onButton`).
- **Code und Gametests** nennen die Konstanten, keine Zahlen.
- **`TestInput`** reicht die Zahl unverändert an `MouseHandler.onButton`
  weiter. Ein Gametest mit 0 und 1 drückt also eine Taste, die es nicht
  gibt, und die linke statt der rechten. So war `Bedienung` grün, obwohl
  beim User links das Teleport-Menü öffnete und Ziehen nichts tat.

### Gegen einen echten Server

Der Gametest `Server` verbindet sich wie der Testserver der Fabric API über
`ConnectScreen.startConnecting` mit der Adresse aus `-Pserver`. Der Server
braucht:

- **Paper in der Version des Clients,** heute 26.3; ein Client spricht nur
  das Protokoll seiner Version. Das Plugin baut gegen die API 26.2 und läuft
  darauf.
- **`online-mode=false`:** Der Client im Gametest hat keine Mojang-Sitzung.
  Auch der Testserver der Fabric API stellt das so ein.
- **`server-ip=127.0.0.1`:** Ohne Anmeldung kommt jeder mit jedem Namen
  herein. Das ist für einen Testserver auf demselben Rechner richtig, aber
  nur, solange er von aussen nicht erreichbar ist.
- **Keine Whitelist,** oder der Client darauf: `whitelist off` oder
  `whitelist add Player0`, so heisst der Client im Gametest. Weist der
  Server ihn ab, endet der Gametest gleich mit dem Bild `server-abgewiesen`,
  denn der Grund steht nur auf dem Schirm.
- **Das Plugin mit einem Baum zum Download und dem Webserver** aus
  heroic-map-renderer#151. Läuft der Webserver auf demselben Rechner wie
  der Server, passt die Prüfung der Adresse, siehe [Download](download.md),
  „Sicherheit“.

Er löscht vorher den Ordner dieses Servers unter `heroicmap/`, lädt den
kleinsten Massstab des ersten Baums und bestätigt jeden Dialog. Er läuft
unter der Sperrdatei und nur, wenn kein Minecraft-Client des Users offen
ist.

Lauf am 06.10. gegen Paper 26.3-157 mit dem Plugin, ein Baum `top-north-s`
aus der Testwelt: 46 Kacheln, 2,2 MB, Code 0. Das Bild `server-karte`:

![Vollbildkarte nach dem Download vom Paper-Server](bilder/server-karte.png)

## Icon

`src/main/resources/assets/heroicmap/icon.png`, 300 × 300 Pixel mit
durchsichtigem Hintergrund, steht in `fabric.mod.json` unter `icon`;
Launcher wie Prism und Mod-Listen zeigen es. Es ist die Insel aus dem Banner
des Hauptrepositorys, die Ebene „Insel“ in `docs/bilder/quellen/banner.aseprite`
von heroic-map-renderer, also eine Szene aus den Tests, ohne Verlauf und
Schrift, mittig auf ein Quadrat gesetzt.

## CI

`.github/workflows/ci.yml` hat zwei Jobs:

- **Gradle:** `./gradlew build` unter Ubuntu mit Java 25.
  `gradle/actions/setup-gradle` prüft dabei auch, dass `gradle-wrapper.jar`
  ein Wrapper von Gradle ist.
- **Doku:** `pruefe-doku.sh` vom Branch `master` des Hauptrepositorys,
  dieselbe Prüfung wie dort. Der Schritt läuft mit `shell: bash`, also mit
  `pipefail`: Scheitert der Download, wird der Job rot.

## Doku prüfen

Lokal aus der Wurzel des Repositorys:

```bash
curl -fsSL https://raw.githubusercontent.com/VonNekyia/heroic-map-renderer/master/.github/pruefe-doku.sh | bash
```

Neue Seiten erst nach `git add`, die Prüfung sieht nur, was Git verfolgt.
