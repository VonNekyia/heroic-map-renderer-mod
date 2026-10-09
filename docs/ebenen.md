---
title: Ebenen
description: Wie der Mod die Ebenen vom Plugin empfängt, in Teilen je version, ihre Nadeln als Wappenschild mit Symbol und Namen auf Minimap und Vollbildkarte zeichnet, kleiner beim Hinauszoomen, die Symbole vom Server holt, und wie der Spieler jede Ebene im Menü an- und abschaltet; was noch fehlt.
code:
  - src/main/java/com/nekyia/heroicmap/Ebenen.java
  - src/main/java/com/nekyia/heroicmap/EbenenMenue.java
  - src/main/java/com/nekyia/heroicmap/Symbole.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Karte.java
  - src/main/java/com/nekyia/heroicmap/Kanal.java
  - src/test/java/com/nekyia/heroicmap/EbenenTest.java
  - src/test/java/com/nekyia/heroicmap/SymboleTest.java
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
  seinen Nadeln und halben Teilen. Steht eine Kennung zweimal darin, gilt
  der erste Eintrag.
- **`ebene`:** ein Teil einer Ebene, `teil` von `teile`, mit seiner
  `version` (`Ebenen.teil`).
  - Gelesen schon auf dem Thread des Netzes (`Kanal.lies`,
    `Ebenen.Teil.lies`): Bis 1 MiB JSON parst nicht der Render-Thread.
    Er bekommt nur die Nadeln des Teils; kein JSON bleibt liegen.
  - Ein Teil gilt nur mit der `version`, die die Liste für seine Ebene
    nennt; das Plugin schickt die Liste vor den Teilen. Welche von zwei
    `version` neuer ist, sagt ein Hash nicht, die Liste schon.
  - Erst wenn alle Teile da sind, ersetzen ihre Nadeln die der Ebene, in
    der Reihenfolge der Teile. Bis dahin bleibt die alte.
  - Nennt die Liste eine neue `version`, verwirft der Mod die halben
    Teile der alten. Weil die Liste je Kennung genau eine `version` nennt,
    entsteht so nie eine Ebene aus zwei Versionen.
- **Vergessen** beim Trennen und bei jedem neuen Login; das Plugin schickt
  danach alles neu.
- **Kaputt:** Eine Nachricht, die sich nicht lesen lässt, ändert nichts,
  auch nicht an einer halben Sammlung. Ein kaputtes Objekt fehlt, die
  übrigen gelten.
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
- **Symbol** über dem gefärbten Feld und unter dem Rahmen, Pixel auf
  Pixel, die linke obere Ecke bei (⌊(Breite − Seite) / 2⌋, 3) im Bild des
  Schilds: `symbol.large` 16 × 16 in `large`, `symbol.medium` 9 × 9 in
  `medium`, `small` ohne. Steht die Nadel eine Grösse kleiner, gilt das
  Symbol dieser Grösse; fehlt es, bleibt das Schild leer. Siehe „Symbole“.
- **Farbe:** Die Grafikkarte multipliziert das Feld mit `color`, ohne
  `color` `#D9443A`; das Alpha wirkt nicht. Sie rundet dabei, statt
  abzuschneiden wie die Webkarte; ein Kanal weicht so um höchstens eine
  Stufe ab, siehe
  [0007](entscheidungen/0007-toenung-auf-der-grafikkarte.md).
- **Name** in der Schrift des Spiels, mittig 2 Einheiten unter dem Fuss,
  nur in der Grundgrösse.
- **Grösse** (`Ebenen.stufen`): massgebend ist p, wie viele Einheiten ein
  Block breit ist. Ab p = 1/2 steht die Nadel in ihrer Grundgrösse, ab
  1/8 eine kleiner, ab 1/32 zwei kleiner, darunter gar nicht. Kleiner als
  `small` fällt sie weg.
- **Minimap:** p ist der Zoom, also mindestens 1; die Nadeln stehen
  immer in ihrer Grundgrösse. Gezeichnet wird eine Nadel, deren Fuss auf
  der sichtbaren Karte liegt, mit Rahmen innerhalb seiner Bänder, auch
  gedreht (`Minimap.marke`). Die Nadeln kommen nach Karte und Linien und
  vor Ring und Rahmen: Was am Rand über sie ragt, decken diese. Schild und
  Name bleiben im Quadrat der Minimap. Rund steht ein Schild am Rand so
  auch in den Ecken des Quadrats ausserhalb des Kreises; so ist es gewollt,
  sonst verschwände eine Stadt am Rand.
- **Vollbildkarte:** p ist der Abstand der Chunklinien durch 16
  (`Kartenblick.chunkAbstand`), der Fuss auf dem Raster der Kacheln wie
  die Wegpunkte. Gezeichnet wird, was den Schirm berührt: das Schild 12
  Einheiten zur Seite und 33 nach oben, der Name 11 nach unten und halb so
  weit zur Seite, wie er breit ist.
- **Reihenfolge:** unter Wegpunkten, Mitspielern und dem eigenen Kopf; die
  Ebenen nach `order`, die höhere oben, bei Gleichstand die kleinere `id`
  oben; in einer Ebene in der Reihenfolge der Objekte.
- **An oder aus:** siehe „Umschalten“.
- **Text:** Namen von Ebenen und Nadeln setzt der Mod als schlichten Text;
  Codes mit `§` streicht er.

## Symbole

- **Adresse** aus der Liste `ebenen`, nur wenn die Liste gilt: `url`,
  sonst `port` an der IP der Verbindung zum Spielserver,
  `http://<ip>:<port>/tiles`, IPv6 in eckigen Klammern, wie bei der
  `freigabe` (`Symbole.basis`). Fehlt beides, gibt es keine Symbole.
- **Ein Symbol** liegt unter `<Adresse>/layers/<modname>/<Feld>`,
  `modname` vor dem `:` der Kennung (`Symbole.uri`). Das Feld ist
  `images/<Name>.png` oder `.webp`, ohne Unterordner und ohne Punkt vorn;
  anderes holt der Mod nicht.
- **Geholt** erst, wenn eine Nadel es zeichnet, in einem eigenen Thread,
  einmal je Ebene, Feld, Seite und `version`, höchstens 200 je Ebene wie
  die Bilder im Format. Eine neue `version` gibt alle Symbole der Ebene
  frei und holt neu, denn unter gleichem Namen kann ein Bild neu sein.
- **Geprüft** wie der Download der Karte, siehe [Download](download.md),
  „Sicherheit“: die Adresse gegen das Heimnetz, keine Weiterleitung, kein
  Proxy, ohne Token. Höchstens 256 KiB, Header und Körper zusammen in
  höchstens 10 s, über denselben Weg wie die Kacheln (`Laden.sende`).
  PNG oder WebP nur als einfaches `VP8L`, genau in seiner Grösse
  (`Symbole.hole`). Ein Fehler steht im Log, das Schild bleibt leer.
- **Freigegeben** wird ein Symbol, wenn seine Ebene eine neue `version`
  bekommt oder aus der Liste fällt, und alle beim Trennen, bei einem neuen
  Login und mit einer neuen Adresse. Danach fragt ein Auftrag, der noch
  wartet, nicht mehr.

## Umschalten

- **Untermenü „Ebenen …“** im Untermenü „Einstellungen …“, siehe
  [Minimap](minimap.md), „Bedienung“ (`EbenenMenue`): je Ebene ein
  Schalter mit ihrem Namen in der Sprache des Spiels, die oberste zuerst.
  Passen nicht alle auf den Schirm, blättern `<` und `>`. Ohne Ebenen
  steht dort „Der Server schickt keine Ebenen.“
- **Vorgabe** ist `visible` der Ebene; die Wahl des Spielers geht vor.
- **Gespeichert** gleich beim Umschalten, in `ebenen.properties` im Ordner
  der Welt neben `wegpunkte.json`, je Kennung `an` oder `aus` als `true`
  oder `false` (`Ebenen.wechsel`, `Ebenen.setze`). Je Welt eines Servers
  gilt also eine eigene Wahl. Eine unlesbare Datei gilt nicht; dann zählt
  `visible`.

## Grenzen

Die ersten beiden wie im Format, die übrigen sind eigene Grenzen des Mods.
So kann ein Server den Speicher des Mods nicht füllen:

| Was | Höchstens | Darüber |
|---|---|---|
| Ebenen | 64 | die übrigen fehlen, das Log nennt es |
| Nadeln je Ebene | 1000 | die Sammlung ist verworfen, die alte Ebene bleibt |
| Teile je Ebene | 256; für 4 MiB braucht ein Plugin rund 130 | der Teil gilt nicht |
| Nachricht | 1 MiB | verworfen, siehe [Download](download.md), „Kanal“ |
| Name einer Nadel oder Ebene | 64 Zeichen | der Name fehlt |
| Kennung, `version`, Dimension | 129 Zeichen | Nachricht oder Nadel gilt nicht |
| Feld eines Symbols | 76 Zeichen | das Symbol fehlt |
| Symbole je Ebene | 200 | die übrigen fehlen, das Log nennt es |
| Bild eines Symbols | 256 KiB, 10 s | das Symbol fehlt |

- **Speicher:** Halbe Sammlungen gibt es höchstens eine je Ebene der
  Liste, also 64, mit je höchstens 1000 Nadeln. Symbole höchstens 200 je
  Ebene, also 12 800 Texturen von je 1 KiB, rund 13 MiB.
- **Kosten:** Je Frame geht der Mod alle Nadeln der sichtbaren Ebenen
  durch, im schlimmsten Fall 64 000. Ein Raster nach Regionen kommt erst,
  wenn eine Messung es verlangt.

## Was noch fehlt

- **Infotafel** beim Anklicken; Regionen und Kreise (#36).
