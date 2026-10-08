---
title: Wegpunkte
description: Wegpunkte auf der Vollbildkarte setzen und löschen, Marken für Wegpunkte, Spieler und Mitspieler am Rand, Klick zum Zentrieren, Doppelklick zum Anheften an die Minimap, Grösse der Köpfe, Ablage in wegpunkte.json und was fehlt.
code:
  - src/main/java/com/nekyia/heroicmap/Wegpunkte.java
  - src/main/java/com/nekyia/heroicmap/Karte.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Mitspieler.java
  - src/test/java/com/nekyia/heroicmap/WegpunkteTest.java
  - src/test/java/com/nekyia/heroicmap/MinimapTest.java
  - src/gametest/java/com/nekyia/heroicmap/Bedienung.java
---

# Wegpunkte

Wegpunkte setzt der Spieler auf der [Vollbildkarte](vollbildkarte.md). Dort
stehen sie als Raute in ihrer Farbe, wie Mitspieler und der eigene Spieler;
was ausserhalb des Schirms liegt, steht am Rand in seiner Richtung. Ein
Klick legt eine Marke in die Mitte, ein Doppelklick heftet einen Wegpunkt
oder Mitspieler an die [Minimap](minimap.md). So hat es der User am 08.10.
gewünscht.

## Bedienung

| Eingabe auf der Vollbildkarte | tut |
|---|---|
| Rechtsklick, „Wegpunkt setzen“ | der Eintrag unter „Hierher teleportieren“; setzt einen Wegpunkt auf den Block unter der Maus, in der nächsten von 8 Farben (`Wegpunkte.FARBEN`). Steht dort schon einer, bleibt es einer. Den Eintrag gibt es auch ohne Recht zum Teleportieren und unter einer Decke |
| Rechtsklick auf einen Wegpunkt | das Menü für seinen Block: „Hierher teleportieren“, wenn erlaubt, und „Wegpunkt löschen“ |
| Klick auf eine Marke | legt sie in die Mitte: einen Wegpunkt, einen Mitspieler oder den eigenen Spieler. So kommt man vom Wegpunkt zum eigenen Spieler zurück |
| Doppelklick auf einen Wegpunkt oder Mitspieler | heftet ihn an die Minimap oder löst ihn wieder |

- **Doppelklick:** Das Spiel meldet einen Klick als doppelt, wenn derselbe
  Knopf im selben Schirm höchstens 250 ms nach dem letzten kommt, gleich wo
  (`MouseHandler`, belegt per javap am Client 26.3). Der erste Klick hat die
  Marke schon in die Mitte gelegt; der zweite zählt deshalb für die Marke
  des ersten (`Karte.letzte`), nicht für die unter der Maus.
- **Treffer:** eine halbe Kopfseite und eine Einheit um die Mitte der
  Marke, die zuletzt gezeichnete zuerst. Die Knöpfe oben rechts gehen vor.
- **Ohne Namen:** Die Farbe unterscheidet die Wegpunkte; unten links stehen
  die Koordinaten unter der Maus, siehe [Vollbildkarte](vollbildkarte.md),
  „Bedienung“.

## Am Rand

- **Vollbildkarte:** Eine Marke, die nicht mindestens 14 Einheiten
  (`Karte.RAND`) vom Rand des Schirms liegt, rückt auf der Linie von der
  Mitte des Schirms zu ihr bis dorthin (`Minimap.rand`). Die Seite sagt so
  die Richtung, nicht die Entfernung. Die 14 Einheiten lassen Platz für den
  Namen über einem Kopf.
- **Minimap:** Angeheftete Wegpunkte und Mitspieler ausserhalb der Form
  stehen an ihrem Rand, rund auf dem Kreis, eckig am Quadrat, eine halbe
  Kopfseite und eine Einheit nach innen. Mitspieler, die nicht angeheftet
  sind, stehen wie bisher nur in der Form; Wegpunkte, die nicht angeheftet
  sind, gar nicht.
- **Mitspieler** gibt es nur, solange der Server sie nennt, siehe
  [Minimap](minimap.md), „Mitspieler“. Ein angehefteter Mitspieler, den er
  nicht nennt, fehlt auch am Rand.
- **Angeheftet** zeigt die Vollbildkarte mit einem Ring, dessen Farbe
  einmal in 2 s durch alle Töne läuft (`Karte.BUNT_MS`); die Minimap zeigt
  keinen Ring. Gewünscht hat der User den Ring für Mitspieler; Wegpunkte
  haben ihn ebenso, sonst sähe man nicht, welche angeheftet sind.

## Grösse

- **Köpfe und Wegpunkte** sind 6 Einheiten des GUI gross (`Minimap.KOPF`),
  vorher waren es 8. Auf der Minimap wachsen sie mit deren Seite:
  6 × Seite / 128, mindestens 4, also 4 bei 64 Einheiten und 12 bei 256
  (`Minimap.kopf`). Mit dem GUI-Massstab wachsen sie wie alles im GUI.
- **Rand und Pfeil** wachsen mit: Kopf, Pfeil und Raute sind in Achteln
  oder Sechzehnteln gezeichnet und mit der Grösse skaliert
  (`Minimap.avatar`, `Mitspieler.kopf`, `Minimap.wegpunkt`).
- **Auf ganze Pixel** des Schirms gelegt, sonst wären die Texel eines
  Kopfes ungleich breit.

## Ablage

- **Je Server** in `wegpunkte.json` im Ordner des Servers,
  `heroicmap/<server>/`, siehe [Download](download.md), „Ablage“. Gelesen
  beim Betreten des Servers (`ClientPlayConnectionEvents.JOIN`), vergessen
  beim Trennen. Im Einzelspieler gibt es keinen Ordner des Servers; dort
  liegen sie nur im Speicher.
- **Format:**

  ```json
  {"wegpunkte":[{"dimension":"minecraft:overworld","x":12,"z":-40,"farbe":0,"minimap":true}],
   "spieler":["00000000-0000-0000-0000-000000000001"]}
  ```

  `farbe` ist ein Index in `Wegpunkte.FARBEN`, `minimap` heisst angeheftet,
  `spieler` sind die angehefteten Mitspieler.
- **Schreiben** nach jeder Änderung, über `wegpunkte.json.tmp`, dann
  verschieben; nie liegt eine halbe Datei da.
- **Lesen:** Ein unlesbarer Eintrag fällt weg, die übrigen bleiben. Ist die
  Datei unlesbar, gibt es keine Wegpunkte; die nächste Änderung
  überschreibt sie.

## Tests

- `WegpunkteTest`: setzen, löschen, anheften, über den Neustart behalten,
  kaputte Einträge, nur im Speicher.
- `MinimapTest`: `kopfWaechstMitDerSeite` und `amRandInSeinerRichtung`.
- Gametest `Bedienung` mit echten Eingaben: Rechtsklick und „Wegpunkt
  setzen“, die Karte ziehen, bis der Wegpunkt am rechten Rand steht, ein
  Klick legt ihn in die Mitte, ein Doppelklick nach mehr als 250 ms heftet
  ihn an.
- Gametest `Bilder`: die Vollbildkarte mit einem angehefteten Wegpunkt und
  einem am Rand, siehe [Vollbildkarte](vollbildkarte.md), „Bild“.

## Was fehlt

- **Namen, Farbe wählen, verschieben:** Ein Wegpunkt hat nur Block und
  Farbe; ändern heisst löschen und neu setzen.
- **Je Welt:** Wegpunkte gelten je Server, nicht je Welt des Servers.
