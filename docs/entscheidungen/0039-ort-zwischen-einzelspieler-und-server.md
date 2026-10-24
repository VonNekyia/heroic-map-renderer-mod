---
title: "0039: Ort zwischen Einzelspieler und Server"
description: Warum „Ort …“ den Ordner einer Welt oder eine Karte auch zwischen Einzelspieler und Server umzieht, mit einer Wahl des Ziels und dem Speicherstand statt Adresse und Server, als Umbenennen wie zwischen Adressen; ergänzt 0038.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Ort.java
  - src/main/java/com/nekyia/heroicmap/OrtFenster.java
  - src/main/java/com/nekyia/heroicmap/Kartenliste.java
---

# 0039: Ort zwischen Einzelspieler und Server

## Anlass

[0038](0038-ort-einer-karte-aendern.md) liess „Ort …“ nur zwischen
Adressen zu. Der User am 24.10. zu Einzelspieler ↔ Server: „eher Nische,
aber wäre cool“. Etwa eine Welt, die im Einzelspieler gebaut und dann auf
einen Server geladen wurde, oder umgekehrt. Die Skizze von Mod Code Map 2
hat der Reviewer freigegeben. Diese Seite ergänzt 0038; der Rest von 0038
gilt.

## Entscheidung

- **Derselbe Umzug** wie zwischen Adressen (`Ort.zieheOrdner`,
  `Ort.zieheBaum`): ein Umbenennen, ganz oder gar nicht, „Ganzer Ordner“ als
  Vorgabe.
- **Der Hash bleibt:** Im Einzelspieler wie auf einem Server ist er der
  `biomeZoomSeed` der Welt im Client (`Downloads.weltOrdner`). Dieselbe
  Welt mit demselben Seed hat also an beiden Orten denselben Hash, und
  Kacheln und Wegpunkte passen.
- **Das Ziel wählt der Spieler** im Fenster mit „Server | Einzelspieler“,
  vorgewählt nach der Herkunft.
  - **Server:** Adresse, Server und Hash wie bisher.
  - **Einzelspieler:** der Speicherstand, der Name seines Ordners in
    `saves/`, frei getippt und bereinigt wie beim Anlegen
    (`Ort.speicherordner`), und der Hash. Kein Server, denn im
    Einzelspieler gibt es kein `karte-`.
- **Ob es den Speicherstand gibt,** steht unter den Feldern. Fehlt er, geht
  der Umzug trotzdem; die Karte gilt, sobald es ihn gibt.
- **Eigene Karten** gehören danach dem neuen Server. Im Einzelspieler
  gehören sie keinem, wie bei einem Server ohne Namen (`-`).
- **Ein Punkt am Ende** eines Ordnernamens geht in „Ort …“ nicht, auch
  nicht bei einer Adresse: Windows nähme ihn weg, und der Ordner hiesse
  anders. Bei der automatischen Ablage lehnt der Mod nichts ab; dort fällt
  der Punkt am Ende des Hosts weg, und `example.net.` liegt bei
  `example.net`. So will es der Reviewer.

## Verworfen

- **Der Speicherstand als Liste zum Wählen:** Eine Karte darf vor dem
  Speicherstand da sein, etwa vor dem Herunterladen einer Welt; getippt
  geht das.
- **Einzelspieler als besondere Adresse im Feld „Adresse“,** etwa mit
  `einzelspieler:` davor: eine versteckte Schreibweise, die niemand kennt.
- **`zeiger.txt` beim Umzug in den Einzelspieler löschen:** Im Einzelspieler
  liest sie niemand; beim Weg zurück gilt sie wieder.

## Folgen

- In der Kartenliste ist „Ort …“ nur noch bei der Ablage vor dem Hash grau.
- Der Gametest erreicht jetzt auch die Gründe „Welt, in der du bist“ und
  „gezeigt“, denn die Welt des Tests liegt im Einzelspieler und darf
  umziehen.
