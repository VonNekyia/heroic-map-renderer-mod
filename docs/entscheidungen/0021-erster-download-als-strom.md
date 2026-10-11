---
title: "0021: Der volle Download als ein Strom"
description: Warum der Mod einen vollen Download als einen ZIP-Strom ohne Kompression vom Renderer holt und Datei für Datei ablegt, die Karte ab der gröbsten Stufe zeigt und nach einem Abbruch mit nach fortsetzt, während der Abgleich einzeln bleibt; warum nicht tar, nicht ein Ordner für den ganzen Baum und ohne Höhen.
status: gilt
date: 2026-10-11
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Laden.java
  - src/main/java/com/nekyia/heroicmap/Downloads.java
  - src/main/java/com/nekyia/heroicmap/Karte.java
---

# 0021: Der volle Download als ein Strom

## Anlass

Der volle Download holte jede Kachel einzeln. Ein Baum der grossen Welt hat
192 796 Kacheln und 4,8 GB; das dauerte rund 20 min, und hinter einem Proxy
brach der Download mit `NETZ` ab, schon bei `map.json`, sehr wahrscheinlich
an den vielen kurzen Verbindungen. Der User will Sekunden. Der Plan steht
an heroic-map-renderer#289.

## Entscheidung

- **Ein Strom je Baum** vom Renderer (`--serve`), `GET <url>.zip` mit
  demselben Token, nur für den vollen Download. Der Abgleich bleibt einzeln
  über das ETag. Kennt der Renderer den Strom nicht (404), lädt der Mod
  einzeln.
- **ZIP ohne Kompression,** je Datei nur der lokale Kopf mit Grösse und
  CRC-32, zuletzt ein leerer Eintrag `ende`. Der Mod liest es mit
  `ZipInputStream` aus dem JDK, ohne eigenen Parser.
- **Die Namen prüft der Mod selbst:** nur `map.json`, `manifest`, `ende`
  und `z/x/y.webp` aus ganzen Zahlen, die im Manifest stehen. Zip-Slip ist
  dieselbe Gefahr wie bei tar.
- **Datei für Datei abgelegt,** über eine Zwischendatei und Umbenennen,
  ohne fsync; ein Abbruch lässt keine halbe Datei.
- **Früh sichtbar:** Der Strom kommt von grob nach fein; ab der gröbsten
  Stufe zeigt die Vollbildkarte den Satz.
- **Fortsetzen** mit `?nach=<pfad>`: Der Server überspringt alles bis
  einschliesslich dieser Kachel; so schont ein zweiter Strom den Deckel des
  Tokens. Nach einem Abbruch setzt der Mod sofort fort, solange ein Versuch
  eine neue Datei bringt; nach zwei Versuchen ohne neue Datei hört er auf,
  damit am Deckel keine Schleife entsteht.
- **Fortschritt** auf der Vollbildkarte nur aus Bytes, mit
  `X-Heroic-Bytes` als Gesamtem.
- **Bei 429** auf den Strom fragt der Mod nach 1,5 s neu an, höchstens
  dreimal: Den Platz eines abgebrochenen Stroms gibt der Server erst frei,
  wenn er den Abbruch merkt.

Entschieden vom Reviewer am 11.10., nach Rücksprache von Backend und Mod.

## Verworfene Alternativen

- **tar:** braucht im Mod einen eigenen Parser; ZIP liest das JDK, mit
  CRC je Datei.
- **Erst alles in einen Ordner neben dem Ziel, dann umbenennen:** Die
  Karte stünde erst am Ende da, bei 4,8 GB nach Minuten.
- **Höhen und Boden im Strom:** Der Teleport holt die eine Region, die er
  braucht, frisch; abgelegte Kopien hätten im Abgleich kein ETag und würden
  alt.
- **Eine Ablage als Pack statt einzelner Dateien:** Lokal begrenzt danach
  das Schreiben der vielen Dateien; das wäre ein eigener, späterer Schritt.

## Folgen

- Ein unvollständiger Satz ist an `strom.txt` im Ordner des Massstabs zu
  erkennen; bis der Strom fertig ist, gibt es keinen Abgleich.
- Siehe [Download](../download.md), „Strom“, und
  [Vollbildkarte](../vollbildkarte.md), „Fortschritt“.
