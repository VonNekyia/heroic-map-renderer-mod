---
title: "0048: biom automatisch oder fest"
description: Warum der Rahmen „biom“ im Wähler „Automatisch“ als ersten Eintrag und Vorgabe zeigt und daneben eine feste Kategorie erlaubt, statt nur still dem Biom zu folgen.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Waehler.java
---

# 0048: biom automatisch oder fest

Ergänzt [0008](0008-biom-rahmen-als-vorgabe.md) und
[0032](0032-rahmen-familien-und-waehler.md).

## Anlass

Der User meldete aus seinem Test: „beim biom skin keine variante mehr die
auto wechselt oder es war für mich nicht ersichtlich“. Der Rahmen folgte
weiter dem Biom unter dem Spieler, doch der Wähler zeigte darunter
„&lt; Grasland &gt;“. Das sah aus wie eine feste Wahl, stellte aber nur ein,
welche Kategorie die Farbfelder meinen; nichts sagte „automatisch“. Eine
feste Kategorie gab es nicht. Den Plan hat der Reviewer freigegeben.

## Entscheidung

- **„Automatisch“** steht im Wähler als erster Eintrag vor den acht
  Kategorien und ist die Vorgabe. Dann folgt der Rahmen dem Biom wie
  bisher; die Farbfelder meinen die Kategorie unter dem Spieler, ein
  Tooltip nennt sie.
- **Eine feste Kategorie** bleibt fest, wo der Spieler auch steht.
- **Übernommen** mit dem Klick auf die Vorschau, wie Familie und Variante.
- **Gespeichert** als `rahmen_biom`, `auto` oder die Kategorie. Eine Datei
  ohne den Schlüssel, etwa aus 0.2.70, ist automatisch.

## Verworfen

- **Nur ein Hinweis „automatisch“ im Wähler:** Der User könnte dann keine
  Kategorie festhalten, und die Reihe „&lt; Kategorie &gt;“ sähe weiter wie
  eine Wahl aus.
- **Ein eigener Schalter im Untermenü:** Die Rahmen wählt der Spieler im
  Wähler; ein zweiter Ort dafür wäre schwer zu finden.

## Folgen

- Das Widget „Biom“ zeigt weiter das echte Biom, auch bei fester Kategorie.
- Mit fester Kategorie blendet der Rahmen beim Wechsel des Bioms nicht über.
