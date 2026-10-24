---
title: "0029: Was ein Server zeigt, gilt je Server"
description: Warum die Wahl der Karte je Dimension je Server gilt, in zeigt.txt im Ordner der Welt, warum die eigene Karte einem Server gehört, warum ohne Wahl mit Namen erst der Download kommt und ohne Namen wie bisher die eigene, warum statt des stillen Vorrangs eine Frage mit drei Knöpfen steht und warum eine ruhende Karte keinen Abgleich bekommt.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Zeigt.java
  - src/main/java/com/nekyia/heroicmap/Satz.java
  - src/main/java/com/nekyia/heroicmap/Selbst.java
  - src/main/java/com/nekyia/heroicmap/AlteKarte.java
  - src/main/java/com/nekyia/heroicmap/Downloads.java
  - src/main/java/com/nekyia/heroicmap/Kartenliste.java
---

# 0029: Was ein Server zeigt, gilt je Server

Löst [0004](0004-karte-selbst-zeichnen.md) teilweise ab: im Satz, dass die
eigene Karte statt der des Servers gilt, bis der Spieler sie löscht.

## Anlass

Der User meldete am 24.10.: Wer die Karte von main lädt und auf build
„Selbst“ wählt, zeichnet beide selbst; Spieler melden „Die Karte von main
ist weg“. build zeigt mit `karte.von: main` die Karte von main, beide
teilen den Ordner der Welt, und die eigene Karte ging dort jedem Download
vor, auch auf main. Dazu wünschte er, dass der Mod bei „Selbst“ fragt, ob
die alte Karte gelöscht wird, und sie sonst ruhen lässt. Plan freigegeben
vom Reviewer am 24.10., Fassung 2.

## Entscheidung

- **Wer der Server ist,** sagt ab Plugin 0.17.2 `backend` an der Antwort
  auf `show`. Ohne Namen gilt der Schlüssel `-`, auch im Einzelspieler.
- **Je Server und Dimension eine Wahl** in `zeigt.txt` im Ordner der Welt.
  So ist derselbe geteilte Download auf main an und ruht auf build.
- **Ohne Wahl** mit Namen erst der Download, dann die eigene Karte; ohne
  Namen wie bisher die eigene vor dem Download. So ist die Karte von main
  gleich nach dem Update wieder da, und für Server ohne Plugin und im
  Einzelspieler bleibt alles, wie es war.
- **Die eigene Karte gehört einem Server:** Ihr Baum trägt die CRC über
  Server und Dimension, `satz.json` nennt den Server. Ein Server zeigt nie
  die eines anderen und zeichnet nie in sie. Ein alter Baum ohne Server
  gehört einem Server ohne Namen; ein Server mit Namen übernimmt ihn in der
  Kartenliste.
- **Eine Frage statt des Vorrangs:** bei „Selbst“ über einem Download und
  vor dem ersten vollen Download über der eigenen Karte; „Löschen“,
  „Behalten, ruht“, „Abbrechen“, wie Vanilla beim Öffnen einer alten Welt.
- **Ruhend heisst je Server:** kein Abgleich auf diesem Server.

## Verworfene Alternativen

- **Eine Marke `ruht.txt` je Baum (Plan, Fassung 1):** Sie gälte für alle
  Server, die den Ordner teilen; der Download ruhte auf build und main.
- **Den Ordner je Server trennen:** Dann teilten main und build auch den
  Download nicht mehr, und `karte.von` liefe ins Leere. Das bleibt eine
  eigene Wahl des Spielers, später.
- **Die eigene Karte weiter vorziehen, nur je Server:** Ein Spieler, der auf
  build schon selbst zeichnete, sähe auf build nie den Download, ohne es zu
  wissen; die Frage macht die Wahl sichtbar.
- **Bis zur Antwort auf `show` mit dem Gedächtnis des letzten Servers
  zeichnen:** Hinter einem Proxy ist das bei gleicher Welt oft der andere,
  und der erste Chunk landete in seiner Karte.

## Folgen

- Ohne Plugin 0.17.2 auf den Servern bleibt es beim Schlüssel `-`: wie
  bisher, nur mit Frage und Kartenliste.
- `zeigt.txt` und das Feld `server` in `satz.json` sind ein paar Byte.
- Bis zur Antwort auf `show`, höchstens die 2 s, die das Plugin auf den
  Proxy wartet, oder 3 s ohne Kanal, zeichnet „Selbst“ nicht; Chunks, die
  in dieser Zeit kommen, zeichnet es danach.
