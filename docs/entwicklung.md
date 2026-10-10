---
title: Bauen und testen
description: Versionen, Gradle, Tests, Gametests, Maustasten, CI, Release auf GitHub und Modrinth und die Prüfung der Doku.
code:
  - build.gradle.kts
  - src/gametest/resources/fabric.mod.json
  - src/gametest/java/com/nekyia/heroicmap/Bedienung.java
  - gradle/libs.versions.toml
  - gradle/wrapper/gradle-wrapper.properties
  - .github/workflows/ci.yml
  - .github/workflows/basis.yml
  - .github/workflows/release.yml
  - .github/workflows/modrinth.yml
  - .github/notizen.sh
  - CHANGELOG.md
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
| `KartenblickTest` | wie die Vollbildkarte Kacheln auf den Schirm legt, Zoom über Stufen und Lupe, Schieben, Platzhalter aus gröberen Stufen, siehe [Vollbildkarte](vollbildkarte.md); Lage merken je Dimension und geklemmt stellen, die offene Liste der Ebenen, siehe [Vollbildkarte](vollbildkarte.md), „Lage merken“; Marken auf dem Raster der Kacheln und am Rand neben den Knöpfen, siehe [Wegpunkte](wegpunkte.md), „Am Rand“; Chunklinien auf dem Raster bis zum Rand und nicht zu dicht, auch bei GUI-Massstab 1 auf 4K, siehe [Minimap](minimap.md), „Chunklinien“ |
| `TeleportTest` | der Befehl zum Teleportieren und dass es ihn nur mit `execute` und `tp` im Befehlsbaum gibt, siehe [Vollbildkarte](vollbildkarte.md), „Teleportieren“ |
| `DrehungTest` | wie die Minimap ihr Bild auf den Schirm bringt: Schnitt konvexer Vielecke, Vieleck um den Kreis, Rand mit und ohne Rahmen gedeckt, mit Abstand gegen das Einrasten, Umriss wie auf main, ungedreht auf dem Raster, Fächer, Chunklinien in der Form, siehe [Minimap](minimap.md), „Form“; Blickrichtung oben, Drehung und zurück, Marken auf der Mitte der Bänder, genordet fest und gedreht starr mit, auf ganzen Pixeln, ohne zier, Reichweite √2, Wegpunkt in Blickrichtung, siehe [Minimap](minimap.md), „Drehen“; Formen am Ort der Marken, gedreht und ungedreht, und auf der Karte, siehe [Ebenen](ebenen.md), „Flächen, Kreise und Linien“ |
| `KachelnTest` | WebP lesen, samt Alpha, die Grösse aus dem Kopf vor dem Dekodieren, genau so gross wie eine Kachel; die Grenzen der Banner prüft `SymboleTest`, siehe [Vollbildkarte](vollbildkarte.md), „Kacheln“; Pixel mit einem Index hinter der Palette durchsichtig, alle anderen wie TwelveMonkeys, siehe [Vollbildkarte](vollbildkarte.md), „Farbindex hinter der Palette“ |
| `SatzTest` | den Satz zur Dimension finden, zwei Bäume nach dem Namen, Grenzen für `map.json`, siehe [Vollbildkarte](vollbildkarte.md), „Welcher Satz“ |
| `EbenenTest` | die Ebenen vom Plugin: Liste, Teile in jeder Reihenfolge, die alte Ebene bis die neue ganz ist, halbe verworfen, keine Ebene aus zwei Versionen, auch nicht bei doppelter Kennung, zu viele Nadeln, Teile und zu lange Texte, nur Nadeln mit ihren Vorgaben, Grenzen, kaputte Nachrichten, Reihenfolge und Name, schlichter Text, Banner mit Bild, Nadeln und Banner zusammen, Farben, die Wahl je Welt über den Neustart und eine kaputte Wahl, siehe [Ebenen](ebenen.md); Formen mit Vorgaben, Grenzen und Verworfenen, `#00000000`, Kappen, Punkte über alle Ringe, Deckel der Punkte je Teil, Ebene und über alle Ebenen, ersetzter Teil, verworfene Ebene übergeht ihre Teile, winziges Muster, 10 000 grosse Dreiecke, siehe [Ebenen](ebenen.md), „Grenzen“; Kartenschrift mit Vorgaben, Grenzen und Grenzfällen wie die Webkarte, NFC, siehe [Ebenen](ebenen.md), „Kartenschrift“ |
| `TafelTest` | die Infotafel: das Beispiel des Formats, Grenzen und Tiefe, 64 Bausteine, Setzen mit Umbruch, Bilder verkleinert und nie vergrössert, Spalten und Wertung, siehe [Ebenen](ebenen.md), „Infotafel“ |
| `TafelnTest` | Tafeln vom Plugin: je Objekt und version einmal, keine Tafel gemerkt, fremde Antwort übergangen, neue version leert, höchstens 256, Zeigen nach Ruhe und Nachlauf, Klick hält, Treffer in Fläche mit Loch und Kreis, Frage und Antwort im Kanal, siehe [Ebenen](ebenen.md), „Infotafel“ |
| `TrapezeTest` | die Füllung einer Fläche als Trapeze: Quadrat in beiden Umlaufsinnen, schräge Kante genau, L-Form, Loch in beiden Umlaufsinnen, Überlappen und Schleife nach gerade/ungerade, Fläche wie der Umriss, Stücke mit den Punkten bei Kreis und Treppe, Deckel der Arbeit beim Kamm, die Säge in beiden Richtungen mit derselben Fläche, siehe [Ebenen](ebenen.md), „Flächen, Kreise und Linien“ |
| `FormenTest` | Gehrung mit geteilter Kante, Claim ohne Lücke und ohne Doppeltes, 200 gedrehte Quadrate mit gebrochener Mitte, Ring mit erstem Punkt draussen, Fase an spitzen Ecken, Striche über die Ecke mit Gehrung, Strecken neben dem Schnitt schieben nur das Muster, gestrichelte Weltgrenze nur im Bild, winziges Muster durchgezogen, Kreise nur im Kasten, 10 000 grosse Kreise, Budget der Ecken, Kappen in Doubles, Ecken des Kreises, Umlaufsinn und Schnitt, ganz innen ohne Schnitt, Schlüssel des Speichers, siehe [Ebenen](ebenen.md), „Flächen, Kreise und Linien“; Kartenschrift mittig auf dem Pfad, nie auf dem Kopf, um die Ecke, über die Enden und an einem Punkt, Höhe in Einheiten des GUI mit den Schwellen und der Grenze der Minimap, erst die Kontur, Zeichen ausserhalb der Form, Budget, sichtbar durch den Text, siehe [Ebenen](ebenen.md), „Kartenschrift“ |
| `SymboleTest` | die Symbole der Nadeln und Bilder der Banner: Adresse aus `url` oder `port`, Banner bis 32 × 64 und Symbole genau, ein Banner als WebP 22 × 40 aus `src/test/resources/banner.py`, nur Bilder unter `images/` der eigenen Ebene, Holen mit dem Client der Produktion gegen einen Server auf loopback mit falscher Grösse, 404, genau und über 256 KiB, Weiterleitung, Heimnetz und einem Server, der nach den Headern schweigt; Seite im Schlüssel, Freigabe bei neuer `version` und wenn die Ebene wegfällt, höchstens 200 je Ebene, Banner zählen mit und teilen sich ein Bild, höchstens 1000 über alle Ebenen, nach dem Leeren keine Anfrage, siehe [Ebenen](ebenen.md), „Symbole“ |
| `MitspielerTest` | die Nachricht `spieler` lesen, kaputte Einträge, Obergrenze, verfallen nach 5 s und beim Verlassen, die Antwort auf `show`, der Grund ohne Plugin, siehe [Minimap](minimap.md), „Mitspieler“ |
| `MinimapTest` | den Bereich der Minimap, siehe [Minimap](minimap.md), „Neu zeichnen“; Form, Lage, Einstellungen und Auflösung, Grösse nach dem Fenster, auch aus einer Datei von vor dem Anteil, siehe [Minimap](minimap.md), „Bedienung“; Grösse des Kopfes, Marken am Rand und auf dem Pixel der Karte, siehe [Wegpunkte](wegpunkte.md); Chunklinien auf dem Raster der Karte, erste und letzte im Bild, Kreuzungen einfach gedeckt, siehe [Minimap](minimap.md), „Chunklinien“; Koordinaten in den drei Modi, gespeichert, unter oder über der Minimap, siehe [Minimap](minimap.md), „Koordinaten“ |
| `BiomTest` | der Rahmen „biom“: jedes Biom der Oberwelt in 26.3 mit seiner Kategorie, gegen die Tags des Spiels und von Fabric API auf dem Klassenpfad, ohne und mit `c:`; Höhlen behalten die letzte; Nether und End; Biome eines Datapacks nur über den Namen und mit seinen Tags; jede Kategorie hat einen Ordner; Rückfall über den Namen mit Gegenprobe; Wechsel nach 2 s und Überblendung, siehe [Rahmen](rahmen.md), „Biom“ |
| `SkinTest` | die Rahmen: Palette lesen, die sechs Skins des Pakets und die acht von „biom“, Fehlschlag gemerkt, Bänder eckig nach der Regel, Ring rund in den Bändern, Ornamente auf der Mitte der Bänder, Spiegeln je Ecke, Griff zur Mitte mit 9 × 9, Abstand zum Rand, siehe [Rahmen](rahmen.md) |
| `SelbstTest` | die selbst gezeichnete Karte: Chunk in seiner Kachel, Vorfahr aus vier Kindern, nur das geänderte Viertel, gröbere Stufen später, Schreiben scheitert, dauerhaft scheitert, Speicher bei 64, kaputte PNG, Baum je Dimension, Präfix und Marke, PNG mit falscher Grösse, siehe [Selbst gezeichnete Karte](selbst.md) |
| `KachelwerkMessung` | nur mit `-Pkachelwerk=<datei>`: was `Kachelwerk.schreibe` kostet, siehe [Selbst gezeichnete Karte](selbst.md), „Kosten“ |
| `PyramideTest` | Verkleinern wie die Pyramide des Renderers, siehe [Selbst gezeichnete Karte](selbst.md), „Pyramide“ |
| `ChunkMalerTest` | dünne Flächen mindestens ein Pixel, der Deckel einer Truhe nach `facing` und Hälfte, siehe [Minimap](minimap.md), „Flächen und Pixel“ und „Blockentities“ |
| `WegpunkteTest` | Wegpunkte setzen, löschen, anheften, Farben, kaputte Einträge und Dateien; feste ids, Formen aus Wegpunkten, die beim Verschieben mitgehen und beim Löschen schrumpfen; Formen, Rechtecke, Kreise, Nadeln und Banner anheften, je höchstens 64, eine ganze Ebene anheften und lösen, tote Einträge, Listen für Karte und Minimap; siehe [Wegpunkte](wegpunkte.md) |
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
Mods laufen deshalb auch hier. Für `Blockentities` reicht ein Ordner mit
`sodium-fabric-0.9.2+mc26.3.jar`.

| Gametest | tut |
|---|---|
| `Anmeldung` | die Reihenfolge beim Login: Beim ersten Level gibt es den Spieler noch nicht, wohl aber die Verbindung des Levels, aus der `Downloads.server` liest, siehe [Download](download.md), „Ablage“ |
| `Bilder` | baut eine Szene und nimmt die Minimap auf, danach das Menü und die Vollbildkarte aus einem Testsatz mit zwei Wegpunkten, das Untermenü „Einstellungen …“, jeden Rahmen eckig und rund und das Menü mit Rahmen, die drehende Minimap, das ganze Fenster bei 854 × 480 und 1280 × 720 mit der Seite im Verhältnis, die Szene `formen` mit Flächen, Kreis, Linie und Kartenschrift und der offenen Liste der Ebenen, die Szene `orte` mit Nadeln, einem Banner und ihren Namen, gleich gross auf zwei Stufen der Vollbildkarte, und „Unicode-Schrift erzwingen“, das die Generation der Kartenschrift hebt, zuletzt die selbst gezeichnete Karte der Szene, gewählt über die Knöpfe, neben der Minimap, die als beschäftigt gilt, auch mit Chunklinien; mit `-Pbilder=<ordner>` landen die Bilder dort, siehe [Minimap](minimap.md), „Bilder“, und [Vollbildkarte](vollbildkarte.md), „Bild“ |
| `Bedienung` | das Menü und die Vollbildkarte mit echten Eingaben der Maus (`TestInput`): im Menü verschiebt Ziehen mit der linken wie der rechten Taste die ganze Minimap; auf der Karte verschiebt links ziehen den Inhalt, ein Linksklick öffnet kein Menü, ein Rechtsklick öffnet „Hierher teleportieren“, ein Klick darauf teleportiert; Wegpunkte setzen, ziehen und klicken auf Marken am Rand, Doppelklick, Wegpunkt am eigenen Standort, einen Kreis und eine Nadel vom Server und ein altes Rechteck anheften; eine Region und eine Linie aus Wegpunkten bauen, die Region per Doppelklick anheften und löschen; die Liste der Ebenen aufklappen, eine Ebene aus- und anschalten, per Doppelklick ganz anheften und lösen, zuklappen; Menü und Untermenü bei 1280 × 720 und GUI-Massstab 3; siehe [Minimap](minimap.md), „Bedienung“, [Vollbildkarte](vollbildkarte.md), „Bedienung“, und [Wegpunkte](wegpunkte.md), „Tests“ |
| `Blockentities` | Truhe, Tür, Schild und Kopf, im Einzelspieler gesetzt und abgebaut wie ein Spieler, mit Rechts- und Linksklick; die Minimap zeigt sie ohne weiteres Zutun, verglichen am Bildschirmfoto an der Stelle des Blocks. Mit Sodium über `-Pzusatzmods`; siehe [Minimap](minimap.md), „Neu zeichnen“ |
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

`src/main/resources/assets/heroicmap/icon.png`, 128 × 128 Pixel mit
durchsichtigem Hintergrund, steht in `fabric.mod.json` unter `icon`;
Launcher wie Prism und Mod-Listen zeigen es. Es ist das Logo des Mods, eine
Fichte auf einer Insel, dasselbe wie auf Modrinth; verkleinert aus
600 × 600 Pixeln mit Lanczos, denn es ist eine gerenderte Szene, kein Raster
aus Pixeln. Bis 0.2.14 war es die Insel aus dem Banner des Hauptrepositorys.

## CI

`.github/workflows/ci.yml` hat zwei Jobs:

- **Gradle:** `./gradlew build compileGametestJava` unter Ubuntu mit
  Java 25. Die Gametests kompiliert er nur; laufen lassen kann er sie
  nicht, sie brauchen ein Fenster des Spiels.
  `gradle/actions/setup-gradle` prüft dabei auch, dass `gradle-wrapper.jar`
  ein Wrapper von Gradle ist.
- **Doku:** `pruefe-doku.sh` vom Branch `master` des Hauptrepositorys,
  dieselbe Prüfung wie dort. Der Schritt läuft mit `shell: bash`, also mit
  `pipefail`: Scheitert der Download, wird der Job rot.
  Danach sucht er Konfliktmarken eines Merges (`<<<<<<<`, `>>>>>>>` am
  Anfang einer Zeile) in allen Dateien; eine heisst rot.

`.github/workflows/basis.yml` prüft jede PR mit dem Job „Basis aktuell“:
Ihre Basis muss `main` sein, und sie muss den neuesten Stand von `main`
enthalten, sonst ist sie rot. So landet keine gestapelte PR in einem
fremden Zweig, und nichts wird über einen alten Stand gemergt. Eine PR,
die auf einer anderen aufbaut, bleibt darum Entwurf, bis der Vorgänger
gemergt ist; dann geht sie auf `main`.

## Release

Ein Release geht auf GitHub und Modrinth, siehe
[0011](entscheidungen/0011-modrinth-mit-curl.md).

1. **Version** in `gradle.properties` heben und in `CHANGELOG.md` einen
   Abschnitt `## X.Y.Z` mit englischen Stichpunkten schreiben; beides in
   der PR der Änderung.
2. **Tag** `vX.Y.Z` auf `main` nach dem Merge. `release.yml` baut das
   Jar und prüft: Der Tag passt zu `gradle.properties`, das Jar nennt die
   Version in `fabric.mod.json` und trägt `META-INF/LICENSE` und
   `META-INF/NOTICE`, und jede Datei unter `assets/` liegt Byte für Byte im
   Jar. Dann legt er einen Entwurf an, mit Jar, `SHA256SUMS`
   und den Notizen aus `.github/notizen.sh`: die Stichpunkte der Version und
   jeder Version seit dem letzten veröffentlichten Release, dem höchsten Tag
   `v…` darunter auf `origin`. So stehen Versionen, die kein eigenes Release
   bekamen, mit drin, etwa 0.2.19 in 0.2.20. Eine PR, die diese Dateien
   ändert, baut zur Probe ohne Entwurf.
3. **Veröffentlichen** von Hand auf GitHub. Das veröffentlichte Release
   lädt `modrinth.yml` als Version auf
   [Modrinth](https://modrinth.com/mod/heroic-map): Minecraft 26.3, Fabric,
   Beta solange 0.x, Fabric API als Abhängigkeit, die Notizen als
   Changelog. Gibt es die Version dort schon, lädt er nichts; ältere als
   0.2.9 lädt er nicht. Von Hand: „Run workflow“ mit dem Tag.
4. **Token:** das Secret `MODRINTH_TOKEN`, ein Token des Kontos auf
   modrinth.com mit den Rechten Create versions, Read projects und Read
   versions; Read versions braucht die Prüfung, ob es die Version schon
   gibt, solange das Projekt in Prüfung ist. Fehlt das Secret, warnt der
   Lauf nur.
5. **Nur das Jar des Releases** geht weiter, an jeden Ort, an dem der Mod
   liegt: Modrinth, Modpacks, die eigene Instanz. Ein eigener Build trägt
   dieselbe Versionsnummer, aber andere Bytes; so gibt es je Version genau
   eine Fassung, und ihre SHA-256 steht in `SHA256SUMS` des Releases.

## Doku prüfen

Lokal aus der Wurzel des Repositorys:

```bash
curl -fsSL https://raw.githubusercontent.com/VonNekyia/heroic-map-renderer/master/.github/pruefe-doku.sh | bash
```

Neue Seiten erst nach `git add`, die Prüfung sieht nur, was Git verfolgt.
