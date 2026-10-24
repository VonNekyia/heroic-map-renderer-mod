---
title: "0045: Karte kopieren per Hardlink"
description: Warum „Kopie“ in der Kartenliste die Karte eines anderen Servers derselben Welt als eigene Karte anlegt, je Kachel ein Hardlink, über die „Selbst zeichnen“ in PNG zeichnet und dabei den Link löscht; ohne Hardlinks echte Kopie nur nach Rückfrage; Baustein 4 des Kartenausweises.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Kopie.java
  - src/main/java/com/nekyia/heroicmap/Kachelwerk.java
  - src/main/java/com/nekyia/heroicmap/Kartenliste.java
---

# 0045: Karte kopieren per Hardlink

## Anlass

Der User am 24.10.: Ein Bauserver ist eine Kopie der Hauptwelt mit
demselben Seed. Dort soll ein Spieler die Karte des Hauptservers als
Grundlage nehmen und darüber selbst zeichnen, was sich auf dem Bauserver
ändert. Der Zeiger zeigt nur, er zeichnet nicht. Baustein 4 des Konzepts
„Kartenausweis“, vom Reviewer freigegeben; ohne Hardlinks hat er die
Rückfrage entschieden, der User kann es ändern.

## Entscheidung

- **„Kopie“ in der Kartenliste,** neben „Karte von … nutzen“ und „lösen“:
  Die gezeigte Karte des anderen Servers für die Dimension des Spielers
  wird die eigene Karte dieses Servers, und er zeigt sie.
- **Je Kachel ein Hardlink:** Die Kopie kostet kaum Platz. Beide Schreiber
  ersetzen eine Kachel über eine Zwischendatei mit `ATOMIC_MOVE`; der Link
  bricht so je Kachel auf, und die Karte, aus der sie stammt, bleibt
  unberührt. Auch ein Abgleich der Quelle ändert die Kopie nicht.
- **WebP als Grundlage:** Downloads sind WebP, „Selbst zeichnen“ schreibt
  PNG. Fehlt die PNG einer Kachel, liest das Kachelwerk die WebP daneben;
  nach dem Schreiben der PNG löscht es den Link. Verlustfrei, also
  bitgleich.
- **Die Pyramide** der Quelle kommt mit; `map.json` bekommt
  `downscale: nearest`, damit das Kachelwerk sie nicht neu baut.
- **Ohne Hardlinks,** etwa auf exFAT, eine echte Kopie nur nach Rückfrage
  mit der Grösse.
- **Grau,** wenn dieser Server schon eine eigene Karte der Dimension hat
  oder ein Download in die Quelle läuft.

## Verworfen

- **Echte Kopie immer:** Eine Karte kann 5 GB haben; die Kopie kostete so
  viel Platz und Minuten.
- **Kopie beim Zeichnen, Kachel für Kachel:** Die Kopie zeigte bis dahin
  Lücken, und „Selbst“ müsste die Quelle kennen.
- **WebP schreiben:** Java hat keinen Encoder für WebP; PNG ist da.
- **Die PNG neben der WebP stehen lassen:** Die Anzeige nimmt WebP vor PNG
  und zeigte die alte Kachel.

## Folgen

- Die Kopie ist eine gewöhnliche eigene Karte: löschen, an und ruht,
  „Ort …“ wie jede andere.
- Löschen der Kopie löscht nur ihre Links; die Quelle bleibt.
- Liegen Quelle und Kopie auf verschiedenen Laufwerken, etwa über eine
  Junction, scheitert der Link, und die Liste nennt den Fehler.
