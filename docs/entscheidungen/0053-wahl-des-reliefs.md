---
title: "0053: Wahl des Reliefs"
description: Warum der Spieler im Mod das Relief der Karte wählt, Flach, Relief oder Relief + Schatten, global mit der Vorgabe Relief, an einem Knopf der Vollbildkarte; warum der Mod Schicht und Rest nur lädt, wenn die Wahl nicht die Art der Kacheln ist, sie beim Zurückwechseln bis zum nächsten Download behält, und das Relief auch auf der eigenen Karte und der Minimap nah selbst rechnet.
status: gilt
date: 2026-10-25
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Relief.java
  - src/main/java/com/nekyia/heroicmap/Kacheln.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
---

# 0053: Wahl des Reliefs

## Anlass

Der User am 24.10.: Die Karte von main zeichnet mit `slope`, die Webkarte
zeigt nur ihn; im Mod soll man ohne, `slope` und `shadows` wählen können.
Der Renderer schreibt dafür ab 0.19.0 eine Schicht und einen Rest, siehe
seine Entscheidung 0132; das Plugin liefert beide ab 0.21.0 aus. Den Plan
des Mods gab der Reviewer am 25.10. frei, mit den Antworten des Users.

## Entscheidung

- **Drei Arten:** im Spiel „Flach“, „Relief“, „Relief + Schatten“, Englisch
  „Flat“, „Relief“, „Relief + Shadows“; im Code und in den Einstellungen
  `ohne`, `slope`, `shadows`. Wahl des Users.
- **Global,** Vorgabe `slope`, wie die Karte von main. Es ist eine Vorliebe
  wie die Auflösung; ob eine Karte sie kann, hängt am Baum.
- **Der Knopf** auf der Vollbildkarte unter „Zum Spieler“, ein Klick
  wechselt reihum; dort sieht man die Wirkung sofort. Wahl des Users.
- **Gerechnet** nur, wenn die gewählte Art nicht die der Kacheln ist; dann
  aus Kachel, Schicht und Rest nach der Regel des Renderers, beim
  Dekodieren, auf jeder Stufe. Siehe [Vollbildkarte](../vollbildkarte.md),
  „Relief“.
- **Laden nur bei Bedarf:** Schicht und Rest nur, wenn gerechnet werden muss,
  immer beide, denn jede andere Art geht über die Farbe ohne Relief, und die
  braucht an abgeschnittenen Pixeln den Rest.
- **Zurückwechseln:** Schicht und Rest bleiben bis zum nächsten Download
  des Baums, dann löscht er sie. So ist eine liegende Schicht nie älter als
  ihr Baum, und ein Fehlklick kostet keinen Abgleich. Wahl des Users.
- **Die eigene Karte und die Minimap nah** rechnen das Relief selbst, mit
  denselben Codes je Spalte wie der Renderer; nah sieht die Minimap dann aus
  wie weit. Wahl des User, gleich mit 0.2.75.
- **Messungen** im Fenster des Reviewers: Zeit je Kachel, wenn gerechnet
  wird, höchstens +100 %; Zeit je Chunk im Maler mit Relief höchstens +5 %
  bei 4 und 8 px.

## Verworfene Alternativen

- **Je Server:** bräuchte eine Ablage je Welt und eine Anzeige, was wo gilt;
  verlangt hat es niemand.
- **Im Untermenü „Einstellungen …“:** selten geändert passte es dorthin,
  die Wirkung sieht man aber erst auf der Karte. Der User wählte die Karte.
- **Schicht und Rest immer laden:** rund 11 % mehr Bandbreite und Platz für
  jeden, auch mit `slope`, das schon in den Kacheln steckt.
- **Beim Zurückwechseln sofort löschen:** einfacher zu erklären, aber ein
  Fehlklick kostet einen Abgleich.
- **Die eigene Karte ohne Relief lassen:** nah und weit sähen weiter
  verschieden aus.

## Folgen

- Mit der Vorgabe `slope` sieht die Minimap nah für alle anders aus als
  bisher, nämlich wie weit; das steht deutlich im CHANGELOG.
- Ein Baum aus einem Renderer vor 0.19.0 hat keine Schicht; dort ist der
  Knopf grau und nennt den Grund.
- Gemessen am 26.10., alle Ziele erreicht: je Kachel +99 %, die Minimap
  nah zeichnet so schnell wie Flach, die eigene Karte im Worker höchstens
  +4,1 % und +7 % Platz bei 4 px; siehe
  [Relief-Wahl](../messungen/2026-10-26-relief-wahl.md).
