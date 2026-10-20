---
title: "0027: Abgleich bis zur Hälfte, mit Fortschritt"
description: Warum ein Abgleich still bis zur Hälfte des Satzes laden darf statt bis zu einem Zehntel, woran er ohne Stand misst und warum er den Kasten des Fortschritts zeigt wie der Strom.
status: gilt
date: 2026-10-20
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Freigabe.java
  - src/main/java/com/nekyia/heroicmap/Laden.java
  - src/main/java/com/nekyia/heroicmap/Downloads.java
  - src/main/java/com/nekyia/heroicmap/Karte.java
---

# 0027: Abgleich bis zur Hälfte, mit Fortschritt

## Anlass

Der User am 20.10. in der Abnahme: Ein Abgleich zeigt den Download nicht
wie ein voller, und er bringt zu wenig; höchstens die Hälfte des vollen
Downloads sei richtig. Bis hier lud ein Abgleich still höchstens 11 % des
gespeicherten Satzes, und das Plugin deckelte ihn bei 10 %. Nach einer
längeren Pause brauchte die Karte so mehrere Tage, und während eines
Abgleichs zeigte die Vollbildkarte nichts.

## Entscheidung

- **Bis zur Hälfte:** Still lädt ein Abgleich im gespeicherten Massstab
  höchstens 55 % der gespeicherten Grösse des Satzes (`Freigabe.passend`):
  die Hälfte, mit Spiel wie zuvor 11 % für 10 %. Darüber fragt wie bisher
  ein Dialog.
- **Ohne Stand** misst ein Abgleich nach dem Dialog am Doppelten der
  gezeigten `bytes` statt am Zehnfachen (`Freigabe.mass`), denn sein
  Deckel ist die Hälfte des Satzes.
- **Ohne Stand im Ordner** lädt der Mod weiter voll statt abzugleichen
  (`Downloads.vollStattAbgleich`): Auch mit der Hälfte je Tag käme eine
  gelöschte Karte erst am zweiten Tag ganz zurück.
- **Den Deckel setzt das Plugin;** es hebt ihn zugleich auf die Hälfte.
  Ein älteres Plugin mit 10 % lädt mit diesem Mod weiter still, denn
  10 % liegen unter 55 %.
- **Fortschritt:** Auch ein Abgleich zeigt den Kasten neben „Karte
  laden …“ wie der Strom (`Karte.fortschritt`, `Laden.Fortschritt`), in
  Zeile 1 „Karte wird abgeglichen“. Gesamt sind die Bytes der Kacheln,
  die laut Manifest fehlen, höchstens die `bytes` der `freigabe`;
  empfangen zählt jedes Byte einer geholten Kachel (`Laden.lade`). Tempo
  und Restzeit wie beim Strom.
- **Der einzelne volle Download** ohne Strom zeigt weiter keinen Kasten;
  er war nicht Teil des Wunschs.

Wie es geht, steht in [Download](../download.md), „Zustimmung und
Grösse“, und [Vollbildkarte](../vollbildkarte.md), „Fortschritt“.

## Verworfene Alternativen

- **Gesamt als die `bytes` der `freigabe`:** Sie sind der Deckel, nicht,
  was fehlt. Bei einem kleinen Abgleich stünde der Balken fast leer, wenn
  er fertig ist.
- **Der Abgleich als Strom:** Ein Abgleich holt nur die Kacheln, die laut
  Manifest fehlen, einzeln; dazu kommt nur der Kasten.

## Folgen

- Ein Abgleich kann je Tag bis zur Hälfte des Satzes laden; was darüber
  läge, holt wie bisher der nächste, und das Ergebnis heisst „zum Teil
  geladen“.
- Ohne das neue Plugin bleibt es bei einem Zehntel je Tag.
