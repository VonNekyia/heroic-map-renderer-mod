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
  - src/main/java/com/nekyia/heroicmap/Kartenblick.java
  - src/test/java/com/nekyia/heroicmap/KartenblickTest.java
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
| Rechtsklick, „Wegpunkt setzen“ | der Eintrag unter „Hierher teleportieren“; setzt einen Wegpunkt auf den Block unter der Maus, in der ersten der 8 Farben (`Wegpunkte.FARBEN`), die in der Dimension noch frei ist, sind alle vergeben, reihum. Steht dort schon einer, bleibt es einer. Den Eintrag gibt es auch ohne Recht zum Teleportieren und unter einer Decke |
| Rechtsklick auf einen Wegpunkt | das Menü für seinen Block: „Hierher teleportieren“, wenn erlaubt, und „Wegpunkt löschen“ |
| Klick auf eine Marke | legt sie beim Loslassen in die Mitte: einen Wegpunkt, einen Mitspieler oder den eigenen Spieler. So kommt man vom Wegpunkt zum eigenen Spieler zurück. Wer auf einer Marke zu ziehen beginnt und weiter als 3 Einheiten zieht (`Karte.ZUG`), zieht nur die Karte |
| Doppelklick auf einen Wegpunkt oder Mitspieler | heftet ihn an die Minimap oder löst ihn wieder |

- **Doppelklick:** Das Spiel meldet einen Klick als doppelt, wenn derselbe
  Knopf im selben Schirm weniger als 250 ms nach dem letzten kommt, gleich
  wo, und nur, wenn der Schirm den letzten Klick angenommen hat
  (`mouseClicked` gab `true`; `MouseHandler.onButton`, belegt per javap am
  Client 26.3). Die Karte nimmt einen Klick auf eine Marke, einen Knopf
  oder einen Eintrag des Menüs an. Der erste Klick hat die Marke schon in
  die Mitte gelegt; der zweite zählt deshalb für die Marke des ersten
  (`Karte.letzte`), nicht für die unter der Maus. Jeder andere Klick
  vergisst sie; ein schneller Klick nach „Wegpunkt setzen“ heftet so nichts
  an.
- **Treffer:** eine halbe Kopfseite und eine Einheit um die Mitte der
  Marke. Ein Wegpunkt oder Mitspieler geht dem eigenen Kopf vor, sonst
  liesse sich ein Wegpunkt am eigenen Standort nicht greifen; sonst die
  zuletzt gezeichnete zuerst. Die Knöpfe oben rechts gehen vor.
- **Ohne Namen:** Die Farbe unterscheidet die Wegpunkte; unten links stehen
  die Koordinaten unter der Maus, siehe [Vollbildkarte](vollbildkarte.md),
  „Bedienung“.

## Am Rand

- **Vollbildkarte:** Eine Marke, die nicht mindestens 14 Einheiten
  (`Karte.RAND`) vom Rand des Schirms liegt, rückt auf der Linie von der
  Mitte des Schirms zu ihr bis dorthin (`Kartenblick.marke`). Die Seite
  sagt so die Richtung, nicht die Entfernung. Die 14 Einheiten lassen Platz
  für den Namen über einem Kopf. Den Knöpfen rechts oben weicht eine Marke
  am Rand aus, oben nach links, rechts nach unten.
- **Auf der Karte:** Eine Marke steht auf dem Pixel, auf dem die Karte
  ihren Ort zeichnet, und wackelt beim Ziehen und Laufen nicht gegen sie.
  Auf der Vollbildkarte liegen alle Kacheln auf ganzen Einheiten, also um
  denselben Rest links ihrer exakten Lage; die Marke rückt um denselben
  Rest (`Kartenblick.rasterX`). Umgekehrt nehmen „Wegpunkt setzen“,
  „Hierher teleportieren“ und die Koordinaten unten links den Block, den
  die Karte unter der Maus zeichnet (`Kartenblick.basisRasterX`). Auf der
  Minimap rechnet die Marke von der Kante des Bildes aus `Minimap.ecke`
  (`Minimap.pixel`, `Minimap.marke`).
- **Namen** über Köpfen stehen ganz auf dem Schirm; reichten sie unter die
  Knöpfe rechts oben, stehen sie links daneben (`Kartenblick.name`). Die
  Knöpfe misst die Karte an ihren eigenen Grenzen.
- **Minimap:** Angeheftete Wegpunkte und Mitspieler, die ausserhalb der
  Form liegen, stehen an ihrem Rand in ihrer Richtung, rund am Kreis, eckig
  am Quadrat, eine halbe Kopfseite und eine Einheit nach innen geklemmt. Mitspieler, die nicht angeheftet
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
- **Auf ganze Pixel** des Schirms gelegt, wie die Karte darunter.
- **Texel:** Gleich breit sind die 8 Texel eines Gesichts nur, wenn seine
  Seite in Pixeln ein Vielfaches von 8 ist. Bei 6 Einheiten und
  GUI-Massstab 2 sind es 12 Pixel, die Texel also abwechselnd 1 und 2
  Pixel breit. Ob 6 Einheiten gut aussehen, sieht der User im Spiel.

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
  Datei unlesbar, gibt es keine Wegpunkte, und der Mod verschiebt sie nach
  `wegpunkte.json.kaputt`; die nächste Änderung legt eine neue an. Scheitert
  das Verschieben, bleiben neue Wegpunkte nur im Speicher.

## Tests

- `WegpunkteTest`: setzen, löschen, anheften, über den Neustart behalten,
  kaputte Einträge, eine kaputte Datei bleibt gesichert, Farben bleiben
  nach dem Löschen verschieden, nur im Speicher.
- `MinimapTest`: `kopfWaechstMitDerSeite`, `amRandInSeinerRichtung` und
  `markeAufDemPixelDerKarte`.
- `KartenblickTest`: `markenAufDemRasterDerKacheln`,
  `klickTrifftDenGezeichnetenBlock`, `nameBleibtAufDemSchirmUndNebenDenKnoepfen`
  und `markeWeichtDenKnoepfenAus`.
- Gametest `Bedienung` mit echten Eingaben: Rechtsklick und „Wegpunkt
  setzen“; die Karte ziehen, bis der Wegpunkt am rechten Rand steht; auf
  der Marke ziehen zieht nur die Karte; ein Klick legt sie in die Mitte;
  ein schneller Klick nach „Wegpunkt setzen“ heftet nichts an; ein
  Doppelklick heftet an; am eigenen Standort holt ein Klick den Spieler
  zurück, und ein Rechtsklick bietet „Wegpunkt löschen“.
- Gametest `Bilder`: die Vollbildkarte mit einem angehefteten Wegpunkt und
  einem am Rand, siehe [Vollbildkarte](vollbildkarte.md), „Bild“.

## Was fehlt

- **Namen, Farbe wählen, verschieben:** Ein Wegpunkt hat nur Block und
  Farbe; ändern heisst löschen und neu setzen.
- **Je Welt:** Wegpunkte gelten je Server, nicht je Welt des Servers.
