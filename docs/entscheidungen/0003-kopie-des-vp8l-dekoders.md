---
title: "0003: Eine geänderte Kopie des VP8L-Dekoders von TwelveMonkeys"
description: Warum der Mod einfache verlustfreie Kacheln mit einer geänderten Kopie des VP8L-Dekoders von TwelveMonkeys liest, bis TwelveMonkeys den Fehler beim Farbindex hinter der Palette behebt.
status: gilt
date: 2026-10-08
issues: [24]
code:
  - src/main/java/com/nekyia/heroicmap/webp
  - src/main/java/com/nekyia/heroicmap/Kacheln.java
---

# 0003: Eine geänderte Kopie des VP8L-Dekoders von TwelveMonkeys

## Anlass

[#24](https://github.com/VonNekyia/heroic-map-renderer-mod/issues/24):
TwelveMonkeys liest Pixel, deren Farbindex hinter der Palette liegt, deckend
in der letzten Farbe der Palette statt durchsichtig. Die Kacheln sind
gültig, der Fehler liegt bei TwelveMonkeys, gemeldet als
[haraldk/TwelveMonkeys#1361](https://github.com/haraldk/TwelveMonkeys/issues/1361).
Einzelheiten: [Vollbildkarte](../vollbildkarte.md), „Farbindex hinter der Palette“.

## Entscheidung

Der Maintainer hat am 08.10. entschieden: eine geänderte Kopie, so klein wie
möglich, mit Hinweis auf Herkunft und Lizenz (BSD-3-Clause) in `NOTICE` und
in der Doku, bis TwelveMonkeys den Fehler behebt. Kopiert ist das Paket
`lossless` von `imageio-webp`, geändert sind darin das Paket und eine
Schleife. Nur einfache verlustfreie WebP gehen über die Kopie, den Kopf
liest `Kacheln.dekodiere` selbst.

## Verworfene Alternativen

- **Nach dem Dekodieren korrigieren,** Alpha 0 für Pixel hinter der
  Palette: Nach dem Dekodieren ist der Index weg. Ein Pixel hinter der
  Palette hat dieselbe Farbe wie einer mit dem letzten Eintrag.
- **Eine Unterklasse:** `VP8LDecoder` und `WebPImageReader` sind `final`,
  der Fehler sitzt in der privaten Methode `readTransform`. Die
  Transformationen, Huffman-Tabellen und der Farbcache, die der Dekoder
  braucht, sind nur im Paket sichtbar; darum alle 12 Dateien.
- **Nur `VP8LDecoder` kopieren, im Paket von TwelveMonkeys:** eine Datei
  statt 12, aber ein geteiltes Paket über zwei Jars. Es ginge nur, solange
  beide im selben Classloader liegen.
- **Das ganze Plugin kopieren:** `WebPImageReader`, den verlustbehafteten
  Dekoder und die Metadaten braucht der Weg nicht.

## Folgen

- 12 Dateien fremder Code, rund 1750 Zeilen, unter BSD-3-Clause im Repo und
  im Jar; der Hinweis in `NOTICE` geht mit dem Jar.
- Die Kopie folgt TwelveMonkeys nicht von selbst. Wann sie wegfällt, steht
  unter [Vollbildkarte](../vollbildkarte.md), „Farbindex hinter der Palette“.
