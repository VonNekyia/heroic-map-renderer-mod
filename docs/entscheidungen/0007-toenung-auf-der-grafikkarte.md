---
title: "0007: Die Nadeln auf der Grafikkarte tönen"
description: Warum der Mod das Feld des Wappenschilds auf der Grafikkarte mit color multipliziert und damit je Kanal um höchstens eine Stufe vom Format abweicht, statt wie die Webkarte auf der CPU abzuschneiden.
status: gilt
date: 2026-10-09
issues: [35]
code:
  - src/main/java/com/nekyia/heroicmap/Ebenen.java
---

# 0007: Die Nadeln auf der Grafikkarte tönen

## Anlass

Das Format der Ebenen färbt das Feld des Schilds je Kanal mit
`⌊Feld · Farbe / 255⌋`, abgeschnitten, siehe
[Ebenen](https://github.com/VonNekyia/heroic-map-renderer/blob/master/docs/benutzung/ebenen.md),
„Nadel“. Die Webkarte rechnet das so auf der CPU. Der Mod zeichnet Sprites
im Atlas des GUI.

## Entscheidung

- **Die Grafikkarte tönt:** Das Feld geht mit `color` als Farbe in
  `blitSprite`; sie multipliziert es mit der Farbe und rundet dabei.
- **Abweichung:** je Kanal höchstens eine Stufe gegen die Webkarte. Das
  sieht niemand.

Entschieden vom Reviewer am 09.10.

## Verworfene Alternativen

- **Wie die Webkarte auf der CPU,** je Farbe und Grösse eine eigene Textur,
  gemerkt: genau wie das Format, aber rund eine Stunde mehr Arbeit und je
  Farbe drei Texturen, die der Mod verwalten und freigeben müsste.

## Folgen

- **[Ebenen](../ebenen.md), „Nadeln“,** nennt die Abweichung und verweist
  hierher.
