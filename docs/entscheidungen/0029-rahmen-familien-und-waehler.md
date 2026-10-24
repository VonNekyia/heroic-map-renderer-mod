---
title: "0029: Rahmen als Familien mit Varianten, gewählt im Wähler"
description: Warum die Rahmen Familien mit Recolors sind statt einzelner Skins, wie Varianten, Stellung und Lage der Marken in Dateien stehen, warum ein Wähler mit Vorschau das Durchschalten ersetzt und die mittlere Taste Marken durchsichtig macht; ergänzt 0005, 0012 und 0022.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Skin.java
  - src/main/java/com/nekyia/heroicmap/Waehler.java
  - src/main/java/com/nekyia/heroicmap/MinimapKlick.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
---

# 0029: Rahmen als Familien mit Varianten, gewählt im Wähler

## Anlass

Der User am 23.10.: Der Designer zeichnet einen neuen Satz, dazu einen
Rahmen mit den Buchstaben N, E, S, W. Mehrere Rahmen sind nur andere Farben
desselben Rahmens, etwa die goldene und die grau-weisse Fassung; jeder
Rahmen von „biom“ soll sich je Kategorie einstellen lassen. Statt durch
alle Rahmen zu schalten, soll ein Rechtsklick eine Vorschau aller
Rahmenarten zeigen, mit den Farben darunter: ein Klick auf eine Farbe färbt
die Vorschau um, ein Klick auf die Vorschau übernimmt.

## Entscheidung

- **Familien mit Varianten:** `ohne`, `biom`, `schild`, `rose`, `lettern`
  (`Skin.FAMILIEN`). Eine Variante ist ein Recolor: eigener Ordner mit
  `palette.txt`, `info.txt` und den Bildern, denn ein Recolor färbt auch
  die Marken um. Bei „biom“ ist jede Kategorie eine Familie
  `biom/<kategorie>`.
  - `schild`: `grau`, `gold`, `papier`, vorher `grau`, `holz`, `papier`.
  - `rose`: `silber`, `gold`, `pergament`, vorher `kompass`, `uhr`,
    `kartograph`.
  - `lettern`: neu, je Farbe innen und aussen.
- **Format,** vom Reviewer mit dem Designer festgelegt:
  - `varianten.txt` je Familie, eine Variante je Zeile, die erste die
    Vorgabe; dahinter wahlweise `ordner=` und Schalter, die `info.txt`
    überschreiben. Namen nur aus a–z, 0–9 und _, denn sie werden Pfade im
    Atlas.
  - `info.txt`: `drehung=karte|aussen|aufrecht`, ein altes `aussen=ja`
    gilt weiter; `lage=mitte|kante|innen|aussen` und `lage_norden`.
  - Eigene Bilder je Richtung `osten`, `sueden`, `westen` für die
    Buchstaben.
- **`kante` statt `innen` für „biom“:** Der Designer nannte die Lage, die
  der User für „biom“ gewählt hat (auf dem Band, aussen bündig, mod#189),
  zuerst `innen`; `innen` heisst bei ihm aber ganz in der Karte mit einer
  Einheit Abstand. So hat es der Reviewer am 24.10. entschieden: ein
  eigener Wert `kante`.
- **Der Wähler ersetzt das Durchschalten:** Links- wie Rechtsklick auf eine
  Marke öffnen ihn (Variante B' aus dem Plan, der User widersprach nicht).
  Je Familie eine kleine Minimap mit ihrem Rahmen, darunter ein Farbfeld je
  Ordner; „lettern“ dazu innen und aussen, „biom“ eine Kategorie, die sich
  mit „&lt; &gt;“ wechseln lässt (vom Reviewer freigegeben). Ein Klick auf
  ein Feld färbt nur die Vorschau, einer auf die Vorschau übernimmt; Esc
  und ein Klick daneben schliessen ohne Änderung.
- **Die mittlere Taste macht eine Marke durchsichtig,** wie vorher der
  Rechtsklick; den braucht jetzt der Wähler. Mit den HD-Bildern geht sie
  später reihum Pixel, HD, aus.
- **Gespeicherte Wahlen ziehen um:** `rahmen_wahl=holz` und ein altes
  `rahmen=holz` werden `schild` mit Variante `gold`, und so fort
  (`Skin.ALT`). Neu gespeichert als `rahmen_wahl` und
  `rahmen_variante.<familie>`.
- **Abstand zum Rand je Familie** über alle ihre Varianten, bei „biom“ über
  alle Kategorien; so springt die Minimap beim Wechsel der Farbe nicht.

## Verworfen

- **Varianten mit der mittleren Taste durchschalten:** so im ersten Wunsch
  des Users, dann von ihm selbst durch den Wähler ersetzt.
- **Linksklick schaltet weiter zum nächsten Rahmen (A)** und **Linksklick
  tut nichts (B):** B' gibt keinen toten Klick und keinen versehentlichen
  Wechsel.
- **Ein Ordner je Familie, Recolors nur als Palette:** Der Designer färbt
  auch die Marken um, eine Palette allein reicht nicht.

## Folgen

- Zwei PRs folgen: das Band nach der Formel des Designers, dann die
  HD-Bilder mit der mittleren Taste für Pixel, HD, aus.
- Ein neuer Rahmen ist ein Ordner mit `varianten.txt`, ein Eintrag in
  `Skin.FAMILIEN` und `Skin.MIT_VARIANTEN` und die Namen je Sprache.
