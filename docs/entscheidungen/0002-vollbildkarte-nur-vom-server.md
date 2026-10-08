---
title: "0002: Die Vollbildkarte nur vom Server"
description: Warum die Vollbildkarte nur Kacheln vom Server zeigt und der Mod die Live-Ebene darüber nicht mehr zeichnet.
status: teilweise abgelöst durch 0004
date: 2026-10-08
issues: [25]
code:
  - src/main/java/com/nekyia/heroicmap/Kacheln.java
  - src/main/java/com/nekyia/heroicmap/Laden.java
  - src/main/java/com/nekyia/heroicmap/HeroicMap.java
---

# 0002: Die Vollbildkarte nur vom Server

## Anlass

Die Live-Ebene zeichnete Änderungen in Chunks, die der Client geladen hatte,
mit dem Maler der Minimap und legte sie über die Vollbildkarte, bis der
nächste Abgleich sie brachte. Ausserhalb des Gebiets, das der Server
gerendert hat, entstanden so einzelne Flecken auf dunklem Grund. Der User
hielt die Karte deshalb für kaputt.
[#25](https://github.com/VonNekyia/heroic-map-renderer-mod/issues/25).

## Entscheidung

Die Vollbildkarte zeigt nur Kacheln aus dem Satz vom Server. Selbst
gezeichnet wird nur die [Minimap](../minimap.md). Seit
[0004](0004-karte-selbst-zeichnen.md) gibt es daneben die Wahl „Selbst“,
eine eigene Karte, die die des Servers ersetzt; über deren Kacheln legt der
Mod weiter nichts. Neue Änderungen bringt
der Abgleich, bis zu 10-mal am Tag. Entschieden vom Maintainer am 08.10.

- Weg sind `Live`, `Ebene`, `Pyramide`, der Mixin an `setBlockDirty`, die
  Vanilla-Texel und der feste Biomübergang im Maler, die Uhr des Servers
  aus `jetzt` und das Räumen nach `abdeckt_bis`.
- Ein alter Ordner `overlay/` je Baum fällt beim Start weg, siehe
  [Download](../download.md), „Ablage“.

## Verworfene Alternativen

- **Die Live-Ebene behalten:** zeigt Änderungen vor dem Abgleich, aber
  ausserhalb des gerenderten Gebiets als Flecken. Dazu braucht sie einen
  zweiten Weg im Maler, der wie der Server zeichnet, samt Abnahme gegen den
  Renderer. Der Abgleich bringt dieselben Änderungen etwas später.

## Folgen

- Was sich seit dem letzten Lauf des Renderers ändert, zeigt die
  Vollbildkarte erst nach dem nächsten Abgleich.
- Der Mod liest `abdeckt_bis` aus der `freigabe` und `jetzt` für die Uhr
  nicht mehr. Das Plugin darf sie weiter schicken.
