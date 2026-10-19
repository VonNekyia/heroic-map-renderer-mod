---
title: "0024: Browser ohne Bestätigungsschirm"
description: Warum „Im Browser öffnen“ den Link mit dem Token direkt nach dem Klick öffnet, ohne den Bestätigungsschirm des Spiels und nicht über Blaze3D.openUri, und warum der Mod den Link aus seiner eigenen Basis baut.
status: gilt
date: 2026-10-19
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Webkarte.java
---

# 0024: Browser ohne Bestätigungsschirm

## Anlass

„Im Browser öffnen“ öffnet einen Link mit einem Token, das den Spieler 5 min
lang einmal auf der Webkarte anmeldet. Gewünscht vom User am 20.10.; siehe
[Vollbildkarte](../vollbildkarte.md), „Im Browser öffnen“.

## Entscheidung

Entschieden vom Reviewer, 19.10.:

- **Direkt nach dem Klick,** ohne `ConfirmLinkScreen`. Der zeigt den ganzen
  Link als Text und hat einen Knopf zum Kopieren (per javap am Client
  26.3). Auf einem Stream oder Screenshot stünde das Token, und wer es
  sieht, könnte sich 5 min lang als der Spieler anmelden. Der Klick auf den
  eigenen Knopf ist die Bestätigung.
- **Nicht über `Blaze3D.openUri`,** sondern selbst mit `SDL_OpenURL`, wie
  das Spiel: `openUri` schreibt bei einem Fehler den ganzen Link ins Log.
- **Den Link baut der Mod** aus seiner Basis der Kacheln und dem Token,
  geprüft mit `Adresse.pruefe`. So öffnet er nie eine Adresse, die ihm ein
  Server schickt, und ein direkter Klick ohne Rückfrage lässt sich nicht
  missbrauchen.

## Verworfene Alternativen

- **Mit Bestätigungsschirm:** wie bei Links im Chat. Er zeigte das Token.
- **Der ganze Link vom Plugin:** Dann bestimmte der Server, was der Mod ohne
  Rückfrage öffnet; das Prüfen des Hosts wäre nötig und fehlbar.

## Folgen

- Wer klickt, sieht keinen Zwischenschritt; der Browser geht auf.
- Ob Windows das Fragment weitergibt, prüft der User bei der Abnahme.
