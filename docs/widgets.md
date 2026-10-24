---
title: Widgets an der Minimap
description: Die Widgets an der Minimap, je eine Zeile Text, heute die Koordinaten; unten im Stapel nach dem Balken, oben über der Minimap oder neben dem Nordpfeil, oder frei. Im Menü ziehen, einrasten, mit dem Mausrad skalieren, mit Rechtsklick die Version wechseln, mit Entf oder dem × löschen; wie sie gespeichert werden.
code:
  - src/main/java/com/nekyia/heroicmap/Widgets.java
  - src/test/java/com/nekyia/heroicmap/WidgetsTest.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Einstellungen.java
---

# Widgets an der Minimap

Ein Widget ist eine Zeile Text an der Minimap, im Spiel nur Anzeige, im
Menü an der Minimap bearbeitbar. Heute gibt es eine Art, die Koordinaten
des Spielers; sie sind das, was bis 0.2.68 die Zeile der Koordinaten war.
Gewünscht vom User am 23.10., Plan vom Reviewer freigegeben am 24.10.,
siehe [0033](entscheidungen/0033-widgets-an-der-minimap.md). Weitere Arten
und das Hinzufügen kommen in einem eigenen Schritt.

## Arten

| Art | Versionen | Text |
|---|---|---|
| Koordinaten | `xz`, `xyz` | wie bisher, siehe [Minimap](minimap.md), „Koordinaten“ |

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
  `Einstellungen.aufloesungLage`).
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
| Maus über einem Widget | gelb, dazu rechts oben das ×; über den Koordinaten nennt ein Tooltip die nächste Version |
| Klick links oder rechts | die nächste Version, reihum |
| links ziehen | verschiebt es; siehe „Ziehen“ |
| Mausrad | eine Stufe grösser oder kleiner |
| `Entf` oder Rücktaste | löscht das Widget unter der Maus |
| Klick auf das × | löscht ebenso |

- **Das ×** ist 8 Einheiten gross, halb über der rechten oberen Ecke des
  Widgets (`Widgets.kreuz`), und steht nur, solange die Maus auf dem Widget
  oder dem × ist. Es liegt über allem, auch über Knöpfen, und nimmt den
  Klick zuerst; so hat es der User gewünscht.
- **Reihenfolge der Treffer:** das × des Widgets unter der Maus, dann die
  Widgets, frei vor oben vor unten, dann die Knöpfe, dann L und Minimap
  (`Einstellungen.widgetUnter`, `Einstellungen.mouseClicked`).
- **Ohne Koordinaten** steht im Menü hinter dem unteren Stapel grau
  „Koordinaten aus“; ein Klick darauf holt sie unten zurück
  (`Minimap.setzeKoordinaten`). Ein Knopf zum Hinzufügen jeder Art kommt
  mit dem nächsten Schritt.

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
