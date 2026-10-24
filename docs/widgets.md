---
title: Widgets an der Minimap
description: Die Widgets an der Minimap, je eine Zeile Text, Koordinaten, Zeit oder Biom; unten im Stapel nach dem Balken, oben über der Minimap oder neben dem Nordpfeil, oder frei. Im Menü mit dem „+“ im L oder Rechtsklick auf freie Fläche hinzufügen, ziehen, einrasten, mit dem Mausrad skalieren, mit Rechtsklick die Version wechseln, mit Entf oder dem × löschen; wie sie gespeichert werden.
code:
  - src/main/java/com/nekyia/heroicmap/Widgets.java
  - src/test/java/com/nekyia/heroicmap/WidgetsTest.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Einstellungen.java
---

# Widgets an der Minimap

Ein Widget ist eine Zeile Text an der Minimap, im Spiel nur Anzeige, im
Menü an der Minimap bearbeitbar. Es gibt drei Arten: die Koordinaten des
Spielers, das, was bis 0.2.68 die Zeile der Koordinaten war, dazu Zeit und
Biom. Gewünscht vom User am 23.10., Plan vom Reviewer freigegeben am 24.10.,
siehe [0033](entscheidungen/0033-widgets-an-der-minimap.md).

## Arten

| Art | Versionen, in der Reihe des Wechsels | Text |
|---|---|---|
| Koordinaten | `xz`, `xyz` | wie bisher, siehe [Minimap](minimap.md), „Koordinaten“ |
| Zeit | `24h`, `12h`, `tag`, `echt` | die Uhr des Spiels „19:30“, „7:30 PM“, „Tag 12“; die Uhr des Rechners „21:07“ |
| Biom | `name`, `punkt` | der Name des Bioms unter dem Spieler, „Plains“, mit `punkt` davor ein farbiger Punkt „■ Plains“ |

- **Die Uhr des Spiels** ist die der Oberwelt,
  `Level.getOverworldClockTime`, auch in Nether und End; so entschieden in
  [0033](entscheidungen/0033-widgets-an-der-minimap.md). Der Nether hat
  keine eigene Uhr, dort wirbelt die Uhr des Spiels; das End hat eine
  eigene (`default_clock` in `dimension_type`). Der Server schickt dem
  Client alle Uhren, nicht nur die der Dimension
  (`ServerClockManager.createFullSyncPacket`). Beides im Jar von 26.3
  belegt am 24.10. Tick 0 ist 6:00, 1000 Ticks sind eine Stunde, der Tag
  zählt ab 1 (`Widgets.uhr`, `Widgets.tag`).
- **Der Name des Bioms** kommt aus dem Sprachpaket des Spiels,
  `biome.<namespace>.<pfad>`; fehlt der Schlüssel, etwa bei einem Biom aus
  einem Datapack, steht der Pfad. Gelesen wird wie beim Rahmen „biom“ nur in
  einem Chunk, den der Client hat; sonst bleibt der letzte Name, vor dem
  ersten „–“. Übersetzt wird nur, wenn sich das Biom ändert
  (`Minimap.text`).
- **Der Punkt** steht in der Farbe des Rahmens „biom“ für die Kategorie des
  Bioms, in der gewählten Variante, wie das Farbfeld im Wähler, siehe
  [Rahmen](rahmen.md), „Biom“. In einer Höhle, die keine Kategorie hat,
  steht er grau.

## Wo sie stehen

- **Unten** im Stapel unter der Minimap, in der Reihe ihrer Liste, je eine
  Zeile mit 2 Einheiten Abstand. Die erste beginnt dort, wo bisher die
  Zeile der Koordinaten stand; jede folgt denselben Regeln wie sie: mittig,
  zum Schirm hin verschoben, ganz auf dem Schirm, ohne Platz darunter
  darüber (`Minimap.koordinatenLage`).
- **Der Balken des Fortschritts** steht über allen Widgets unten in der
  ersten Zeile, solange ein Download oder Abgleich läuft, siehe
  [Minimap](minimap.md), „Fortschritt“. Er ist kein Widget und lässt sich
  nicht löschen.
- **Oben** im Stapel über der Minimap, die erste Zeile 2 Einheiten über
  ihrem Rand, mittig wie unten. Passt der ganze obere Stapel dort nicht
  auf den Schirm, etwa mit der Minimap oben am Rand, steht er neben dem
  Nordpfeil: bündig mit ihrer Oberkante, untereinander, auf der Seite des
  Reglers „Zoom“, 2 Einheiten jenseits von ihm (`Einstellungen.zoomLage`).
  Im Spiel steht er an derselben Stelle, auch ohne den Regler.
- **Frei** dort, wo man es hinlegt, als Anteil des Platzes zwischen den
  Rändern wie die Minimap selbst; so übersteht es eine andere
  Fenstergrösse und bleibt ganz auf dem Schirm.
- **Das L im Menü** hängt am Stapel unten: Die Reihe mit Form, Auflösung
  und Raster steht hinter dem letzten Widget dort (`Minimap.stapelKasten`,
  `Einstellungen.aufloesungLage`). Unter der Form steht das „+“, siehe
  „Hinzufügen“.
- **Grösse** in ganzen Pixeln des Geräts je Pixel der Schrift, 1 bis 8; neu
  so gross wie der GUI-Massstab. So bleibt die Schrift bei jedem Massstab
  scharf. Kleiner wird ein Widget nur, wenn es nicht zwischen die Ränder
  passt.
- **Bei F3** wie die Minimap, siehe [Minimap](minimap.md), „Bei F3“.

## Bedienung

Im Menü an der Minimap, mit der Maus wie bei der Minimap selbst; im Spiel
zeigen die Widgets nur.

| Eingabe | tut |
|---|---|
| Maus über einem Widget | gelb, dazu rechts oben das ×; ein Tooltip nennt Art, Version und die nächste: „Zeit: 24 h – Klick: 12 h“ |
| Klick links oder rechts | die nächste Version, reihum |
| Rechtsklick auf freie Fläche | die Liste der Arten an der Maus; siehe „Hinzufügen“ |
| links ziehen | verschiebt es; siehe „Ziehen“ |
| Mausrad | eine Stufe grösser oder kleiner |
| `Entf` oder Rücktaste | löscht das Widget unter der Maus |
| Klick auf das × | löscht ebenso |

- **Das ×** ist 8 Einheiten gross, halb über der rechten oberen Ecke des
  Widgets (`Widgets.kreuz`), und steht nur, solange die Maus auf dem Widget
  oder dem × ist. Es liegt über allem, auch über Knöpfen, und nimmt den
  Klick zuerst; so hat es der User gewünscht.
- **Reihenfolge der Treffer:** eine offene Liste nimmt jeden Klick, dann
  das × des Widgets unter der Maus, dann die Widgets, frei vor oben vor
  unten, dann die Knöpfe, dann L und Minimap, zuletzt freie Fläche
  (`Einstellungen.widgetUnter`, `Einstellungen.mouseClicked`).

## Hinzufügen

- **Das „+“** steht im L hinter der Form, fern der Spalte, 20 × 20 Einheiten
  wie die Symbole; passt es dort nicht auf den Schirm, neben ihr, fern der
  Minimap, sonst davor, und die Spalte wird um es kürzer
  (`Einstellungen.lLage`). Ein Klick öffnet darunter die Liste der Arten,
  ohne Platz darüber; die neue kommt unten hinter den Stapel.
- **Rechtsklick auf freie Fläche,** wo kein Widget, Knopf, Teil des L, Griff,
  keine Marke und nicht die Minimap ist, öffnet dieselbe Liste an der Maus,
  ohne Platz darunter über ihr. Die neue liegt mit ihrer Mitte dort, wo die
  Maus war, und rastet nahe der Minimap ein wie beim Ziehen, siehe „Ziehen“
  (`Einstellungen.klickListe`).
- **Die Liste** nennt Koordinaten, Zeit und Biom; die Zeile unter der Maus
  steht gelb. Ein Klick auf eine Art fügt sie in der ersten Version hinzu,
  jeder Klick schliesst die Liste, `Esc` schliesst nur sie, nicht das Menü
  (`Einstellungen.oeffneListe`).
- **Bei 16 Widgets** (`Widgets.MAX`) ist das „+“ grau, und der Rechtsklick
  öffnet nichts.
- **Ohne Koordinaten** steht im Menü nichts mehr an ihrer Stelle; das „+“
  holt sie zurück. Bis Schritt C stand dort grau „Koordinaten aus“.

## Ziehen

- **Greifen** mit der linken Taste; wandert die Maus weiter als 3
  Einheiten, wie beim Klick auf Marken (`MinimapKlick.WEG`), zieht sie es,
  sonst war es ein Klick.
- **Einrasten:** Es zählt die Mitte des Widgets (`Widgets.ziel`).
  - **Unten,** wenn sie waagrecht über der Minimap liegt, bis 8 Einheiten
    daneben, und senkrecht zwischen ihrer Unterkante und dem Ende des
    unteren Stapels samt 8 Einheiten, wie das Einrasten mit `Strg`.
  - **Oben** ebenso über der Oberkante; steht der obere Stapel neben dem
    Nordpfeil, auch über seinen Zeilen.
  - **Im Stapel** fällt es an die Stelle seiner Mitte. Der Stapel zeigt die
    Lücke schon beim Ziehen, denn die Liste wird je Bewegung neu gesetzt,
    aus der vor dem Greifen (`Widgets.setze`, `Einstellungen.ziehe`).
  - **Sonst frei,** dort, wo die Maus es lässt.
- **`Esc` beim Ziehen** legt es zurück, wo es vorher war, und schliesst das
  Menü nicht.

## Gespeichert

- **In `heroicmap.properties`** als `widgets`, je Widget
  `art:version:groesse:unten` oder `…:oben`, frei `…:frei:x:y` mit der Lage
  als Anteil von 0 bis 1, getrennt durch `;`, etwa
  `widgets=koordinaten:xz:0:unten` (`Widgets.lies`, `Widgets.schreibe`).
  Grösse 0 heisst wie der GUI-Massstab. Höchstens 16 (`Widgets.MAX`); ein
  Eintrag mit unbekannter Art, Version oder Anker, Grösse ausser 0 bis 8
  oder Lage ausser 0 bis 1 fehlt, die übrigen bleiben. Leer heisst ohne
  Widgets.
- **Aus 0.2.69** liest der Mod auch `art:version:unten` ohne Grösse.
- **Übernahme:** Fehlt `widgets`, kommt die alte Einstellung `koordinaten`
  als Widget: `xz` und `xyz` je unten, `aus` keins, ohne Eintrag die
  Vorgabe `xz` (`Widgets.ausKoordinaten`). `koordinaten` schreibt der Mod
  danach nicht mehr; eine ältere Version zeigt dann die Vorgabe.
