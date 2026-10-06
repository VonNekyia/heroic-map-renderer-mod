---
title: Vollbildkarte
description: Die Karte über den ganzen Schirm aus den geladenen Kacheln, mit Bedienung, welchem Satz sie zeigt, Stufen und Lupe, wie Kacheln gelesen und behalten werden, Spieler und Koordinaten, dem Bild aus dem Gametest und was fehlt.
code:
  - src/main/java/com/nekyia/heroicmap/Karte.java
  - src/main/java/com/nekyia/heroicmap/Kartenblick.java
  - src/main/java/com/nekyia/heroicmap/Kacheln.java
  - src/main/java/com/nekyia/heroicmap/Satz.java
  - src/test/java/com/nekyia/heroicmap/KartenblickTest.java
  - src/test/java/com/nekyia/heroicmap/KachelnTest.java
  - src/test/java/com/nekyia/heroicmap/SatzTest.java
  - src/gametest/resources/satz/liste.txt
---

# Vollbildkarte

Die Vollbildkarte zeigt den Satz Kacheln, den der Mod vom Plugin geladen
hat ([Download](download.md)), über den ganzen Schirm. Sie liest nur von der
Platte und braucht keine Verbindung; ohne geladenen Satz für die Dimension
sagt sie das und zeigt nichts.

![Vollbildkarte aus dem Testsatz](bilder/vollbildkarte.png)

## Bedienung

| Eingabe | tut |
|---|---|
| Taste `,` | öffnet die Karte, schliesst sie wieder; frei belegbar unter „Heroic Map“ |
| Ziehen mit links | verschiebt die Karte, der Inhalt folgt der Maus |
| Mausrad | zoomt, siehe „Stufen und Lupe“ |
| `Esc` | schliesst |

- **Das Spiel läuft weiter,** die Karte hält es nicht an (`isPauseScreen`).
- **Unten links** stehen der Name des Satzes und `x`, `z` des Blocks unter
  der Maus.
- **Beim Öffnen** liegt der Spieler in der Mitte.

## Welcher Satz

- **Je Baum** schreibt der Mod nach einem vollständigen Download
  `satz.json` neben die Massstäbe: Name und Dimension aus dem `angebot`,
  dazu der Massstab. Ein gekappter Download schreibt sie nicht.
- **Die Karte** nimmt unter dem Ordner des Servers den Baum, dessen
  Dimension die des Spielers ist (`Satz.fuer`). Kachelgrösse, Stufen und
  `scale` liest sie aus `map.json` des Satzes.
- **`map.json` kommt vom Server** und hat deshalb Grenzen (`Satz.lies`):

  | Feld | erlaubt |
  |---|---|
  | `tileSize` | Zweierpotenz von 64 bis 1024 |
  | `minZoom`, `maxZoom` | `0 ≤ minZoom ≤ maxZoom ≤ 30`, und die Stufe des Massstabs nicht unter `minZoom` |
  | `scale` | ab 1 |
  | Massstab aus `satz.json` | 1, 2 oder 4 |

- **Ein Baum ohne `satz.json`, `map.json` oder mit unlesbarer Datei oder
  verletzter Grenze** zählt nicht und verdeckt keinen anderen.
- **Bietet ein Server zwei Bäume derselben Dimension an,** nimmt die Karte
  den ersten, den das Dateisystem nennt.

## Stufen und Lupe

- **Die feinste Stufe** des Satzes hängt am Massstab: 4 px ist `maxZoom`,
  2 px eine gröber, 1 px zwei (`Satz.stufe`). Dort öffnet die Karte, ein
  Pixel der Kachel auf eine Einheit des GUI.
- **Mausrad zurück** geht eine Stufe gröber, bis `minZoom`.
- **Mausrad vor** geht eine Stufe feiner, auf der feinsten vergrössert die
  Lupe auf 2 und 4 Einheiten je Pixel.
- **Die Mitte** rechnet `Kartenblick` in Pixeln der Basis, der Stufe
  `maxZoom`; so bleibt sie beim Zoomen stehen. Die Kacheln einer Stufe
  liegen auf ganzen Einheiten nebeneinander, ohne Naht.

## Kacheln

- **WebP lesen:** Das Spiel liest Texturen nur als PNG. Die Kacheln
  dekodiert TwelveMonkeys `imageio-webp`, im Jar des Mods mitgeliefert;
  der Hinweis seiner Lizenz steht in [`NOTICE`](../NOTICE).
- **Grösse vor dem Dekodieren:** Der Kopf der WebP nennt Breite und Höhe.
  Sind sie nicht genau `tileSize`, gilt die Kachel als leer, bevor
  TwelveMonkeys Speicher anlegt; sonst könnte eine kleine Datei mit einem
  Kopf von 16384 × 16384 rund 1 GiB verlangen.
- **Im Hintergrund:** Ein Thread dekodiert und füllt das `NativeImage`; auf
  dem Render-Thread bleibt nur das Hochladen als `DynamicTexture`. Die
  Rückgabe an den Render-Thread steht im `finally`, auch nach einem `Error`.
  Bis eine Kachel da ist, bleibt ihr Platz dunkel.
- **Kosten** ([Messung](messungen/2026-10-06-vollbildkarte-uebernahme.md)):
  Eine Kachel 256² dekodiert in rund 2,8 ms, der Thread liefert also rund
  350 je Sekunde, etwa 6 je Frame bei 60 fps. Das Hochladen kostet den
  Render-Thread rund 0,05 ms je Kachel, also rund 0,3 ms je Frame beim
  Öffnen. 160 Kacheln, ein Schirm in 4K bei GUI-Massstab 1, kosten ihn
  zusammen rund 8 ms, verteilt über eine knappe halbe Sekunde.
- **Behalten:** die 192 zuletzt gezeigten Texturen, bei 256² Pixeln rund
  48 MiB auf der Grafikkarte. Die älteste gibt der Mod frei.
- **Fehlt eine Kachel** oder lässt sie sich nicht lesen, merkt sich die
  Karte das und fragt nicht wieder, bis sie neu öffnet.
- **Beim Schliessen** gibt die Karte alle Texturen frei.

## Spieler und Koordinaten

Pfeil und Koordinaten rechnen mit derselben Projektion wie der Renderer und
mit `scale` aus `map.json`, siehe [Projektion](projektion.md). Der Pfeil ist
derselbe wie auf der Minimap.

## Bild

`docs/bilder/vollbildkarte.png` nimmt der Gametest `Bilder` auf, siehe
[Bauen und testen](entwicklung.md), „Gametests“. Er legt einen gemalten
Testsatz an, `scale` 4, Stufen 0 bis 2, 24 Kacheln, aus
`src/gametest/resources/satz/`. Der Satz ist keine gerenderte Welt; er
prüft, dass TwelveMonkeys im Spiel lädt und die Kacheln richtig liegen.

## Was fehlt

- **Live-Ebene:** Änderungen seit dem letzten Lauf des Renderers zeichnet
  der Mod noch nicht über die Karte.
- **Knöpfe** für Laden und Abgleich; bis dahin die Befehle unter
  [Download](download.md), „Befehle“.
- **Platzhalter aus einer gröberen Stufe,** solange eine Kachel lädt.
