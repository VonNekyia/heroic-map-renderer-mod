---
title: Vollbildkarte, Übernahme der Kacheln
description: Was eine Kachel der Vollbildkarte den Render-Thread kostet, mit Pixeln kopieren und hochladen wie zuerst gebaut oder nur hochladen wie jetzt, und wie schnell der Thread des Dekoders Kacheln liefert.
date: 2026-10-06
commits: [3794c7f]
code:
  - src/main/java/com/nekyia/heroicmap/Kacheln.java
  - src/gametest/java/com/nekyia/heroicmap/Uebernahme.java
---

# Vollbildkarte, Übernahme der Kacheln

Nur hochladen kostet den Render-Thread im Median 0,05 ms je Kachel, Pixel
kopieren und hochladen 0,105 ms. Der Thread des Dekoders braucht rund
2,8 ms je Kachel und liefert so rund 6 Kacheln je Frame bei 60 fps. Beim
Öffnen hält das den Render-Thread also rund 0,3 ms je Frame, vorher rund
0,6 ms. 160 Kacheln, ein Schirm in 4K bei GUI-Massstab 1, kosten ihn
zusammen 8,3 bis 8,6 ms statt 17,0 bis 17,6 ms.

## Aufbau

- **Kacheln:** die 16 Kacheln der feinsten Stufe des Testsatzes aus
  `src/gametest/resources/satz/4/2/`, je 256 × 256, verlustfrei.
- **Gametest `Uebernahme`:** je Kachel im Thread des Gametests dekodieren
  (`Kacheln.dekodiere`) und ein `NativeImage` füllen (`Kacheln.pixel`).
  Dann auf dem Render-Thread je einmal:
  - **alt:** `NativeImage` füllen, `DynamicTexture` anlegen, hochladen,
    eintragen, wie zuerst gebaut;
  - **neu:** nur `DynamicTexture` anlegen, hochladen, eintragen, wie jetzt.
  Gemessen mit `System.nanoTime` innerhalb der Aufgabe auf dem
  Render-Thread; das Freigeben danach zählt nicht.
- **Runden:** eine zum Aufwärmen, dann 10, die Reihenfolge von alt und neu
  wechselt je Runde; 160 Werte je Grösse und Lauf.
- **Client:** Titelbildschirm, Fenster 854 × 480; die Kosten je Kachel
  hängen nicht an der Grösse des Fensters.
- **Befehl:** `./gradlew runClientGameTest -Puebernahme=<datei>`, unter der
  Sperrdatei, Last unter 10 % in den 30 s vor jedem Lauf, kein Spiel offen.

## Ablauf

| Lauf | Zeit | Last davor | gültig |
|---|---|---|---|
| 1 | 06.10., 03:03:58 bis 03:04:35 | 65,8 % | nein |
| 2 | 06.10., 03:05:37 bis 03:06:07 | 5 % | ja |
| 3 | 06.10., 03:07:09 bis 03:07:39 | 3,8 % | ja |
| 4 | 06.10., 03:09:06 bis 03:09:36 | 4 % | ja |

Lauf 1 ist verworfen: Eine andere Sitzung schätzte bis 03:04:19 mit bis zu
24 Threads, und die Prüfung der Last im Messskript las die Zahl mit Komma
nicht und brach nicht ab. Das Skript ist korrigiert, Lauf 4 holt ihn nach.
Die Zahlen stehen in der Ausgabe des Gametests je Lauf.

## Ergebnis

Je Kachel, die drei gültigen Läufe:

| Grösse | p50 | p95 | max | Summe über 160 |
|---|---|---|---|---|
| Thread des Dekoders: dekodieren | 2,78 bis 2,85 ms | 3,22 bis 3,30 ms | 3,48 bis 3,66 ms | 448 bis 459 ms |
| Thread des Dekoders: `NativeImage` füllen | 0,05 ms | 0,07 ms | 0,07 bis 0,15 ms | 8,6 bis 8,7 ms |
| Render-Thread alt: füllen, hochladen | 0,104 bis 0,106 ms | 0,137 bis 0,149 ms | 0,18 bis 0,29 ms | 17,0 bis 17,6 ms |
| Render-Thread neu: hochladen | 0,049 bis 0,050 ms | 0,070 bis 0,081 ms | 0,10 bis 0,21 ms | 8,3 bis 8,6 ms |

## Schluss

- **Kein Stau beim Öffnen:** Die Kacheln kommen so schnell, wie ein
  Thread dekodiert, rund 350 je Sekunde. Auch wie zuerst gebaut hielte das
  den Render-Thread nur rund 0,6 ms je Frame; ein spürbarer Hänger entsteht
  nicht.
- **Trotzdem im Faden füllen:** Es halbiert die Zeit auf dem Render-Thread
  und kostet im Faden 0,05 ms neben 2,8 ms fürs Dekodieren.
- **Was nicht gemessen ist:** das Zeichnen der Kacheln je Frame und andere
  Grafikkarten; das Hochladen hängt am Treiber.
