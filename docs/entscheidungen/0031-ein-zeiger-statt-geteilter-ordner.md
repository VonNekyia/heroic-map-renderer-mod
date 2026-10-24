---
title: "0031: Ein Zeiger statt geteilter Ordner"
description: Warum ein Server die Karte eines anderen mit derselben Welt über einen Zeiger nutzt, den der Spieler setzt, statt über einen Ordner, den der Server per karte.von teilt; warum der Zeiger nur den Namen der Karte trägt, nur liest und nie das Ziel beschreibt; warum er nur unauffällig in der Kartenliste steht und einmal von selbst kommt.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Zeiger.java
  - src/main/java/com/nekyia/heroicmap/Uebergang.java
  - src/main/java/com/nekyia/heroicmap/Satz.java
  - src/main/java/com/nekyia/heroicmap/Kartenliste.java
  - src/main/java/com/nekyia/heroicmap/Downloads.java
---

# 0031: Ein Zeiger statt geteilter Ordner

Ergänzt [0029](0029-was-ein-server-zeigt-gilt-je-server.md).

## Anlass

Bis 0.2.69 teilte ein Server die Karte eines anderen nur, wenn das Plugin es
mit `karte.von` sagte; dann teilten beide den Ordner der Welt. Der User
entschied am 24.10.: „karte.von … können wir eigentlich entfernen, ich will,
dass die Karte so etwas nativ kann und die User entscheiden“, dazu „man kann
ja auch Pointer einfügen“, und: nur in der Kartenliste, denn die Funktion
ist so selten, dass nichts auf sie hinweisen soll. Plan des Plugins,
Fassung 3, freigegeben vom Reviewer.

## Entscheidung

- **Ein Zeiger je Server und Welt,** gesetzt vom Spieler: `zeiger.txt` im
  eigenen Ordner der Welt mit dem Namen der Karte des Ziels. Das Ziel ist
  der Nachbar mit demselben Hash unter derselben Adresse; ein Pfad oder
  eine Adresse steht nie darin.
- **Nur lesen:** Die Downloads im Ziel zählen wie eigene, nach den eigenen.
  Die eigene Karte des Ziels zeigt der Mod nie, und „Selbst“ zeichnet nur
  in den eigenen Ordner.
- **Unauffällig:** eine Zeile unten in der Kartenliste, nur wenn es einen
  solchen Nachbarn gibt. Kein Dialog, kein Hinweis beim Betreten.
- **Fehlt das Ziel,** gilt die Regel ohne Zeiger, und der Zeiger bleibt mit
  „(fehlt)“ stehen, bis der Spieler ihn löst.
- **Übergang:** Wer heute auf build die Karte von main sieht, weil build
  `karte.von: main` hat, behält sie, wenn `karte.von` wegfällt: Der Mod
  merkt sich „build zeigte main“ und setzt den Zeiger genau einmal selbst.
  Ein gelöster kommt nicht wieder. Entscheid des Users, Übergang A.

## Verworfene Alternativen

- **Weiter `karte.von` im Plugin:** Dann entscheidet der Betreiber für alle
  Spieler, und wer auf build selbst zeichnen will, teilt den Ordner doch.
- **Ein Pfad im Zeiger:** Er müsste gegen `..`, absolute Pfade und fremde
  Adressen geprüft werden. Der Name allein kann nur auf einen Nachbarn mit
  derselben Welt zeigen.
- **Eine Kopie der Karte:** doppelter Platz, und der Abgleich von main käme
  auf build nie an.
- **Eine Frage beim Betreten von build:** vom User verworfen.
- **Kein Übergang (B):** Spieler auf build verlören still die Karte von
  main, bis sie den unauffälligen Zeiger fänden.

## Folgen

- Das Plugin kann `karte.von` streichen, sobald dieser Mod draussen ist.
  Die Ebenen von main bleiben Sache des Servers (`karte.von-url`).
- `zeiger.txt` und `server.txt` sind ein paar Byte.
