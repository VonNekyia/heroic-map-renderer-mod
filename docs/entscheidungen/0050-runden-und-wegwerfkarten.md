---
title: "0050: Runden und Wegwerfkarten"
description: Warum „Selbst zeichnen“ auf einem Server auch neue Welten und Runden desselben Netzes und Servers zeichnet, warum eine Welt mit demselben Seed ab dem zweiten Rückfall der Spielzeit als Runde gilt und ihre Karte nicht mehr ruht, und warum eigene Karten von Runden und Eintagswelten nach 7 Tagen und über 500 MB gehen; Baustein 6 des Kartenausweises.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Wegwerf.java
  - src/main/java/com/nekyia/heroicmap/Selbst.java
  - src/main/java/com/nekyia/heroicmap/Reset.java
  - src/main/java/com/nekyia/heroicmap/Ausweise.java
  - src/main/java/com/nekyia/heroicmap/Ausweis.java
---

# 0050: Runden und Wegwerfkarten

Ergänzt [0046](0046-reset-erkennen.md).

## Anlass

Auf manchen Servern lebt eine Welt nur kurz: eine Runde aus einer Vorlage,
eine Welt für ein Ereignis, eine Farmwelt, die oft neu kommt. „Selbst
zeichnen“ galt bis dahin je Weltordner; in jeder neuen Welt zeichnete der
Mod nichts, bis der Spieler wieder wählte. Ohne UID liess 0046 die Karte
einer Runde bei jedem Rückfall der Spielzeit ruhen, und solche Karten
blieben für immer liegen.

Entscheidungen des Users:

- 3b: Wegwerfkarten gehen nach 7 Tagen ohne Besuch, und alle zusammen
  höchstens 500 MB, die ältesten zuerst.
- Am 24.10. zu Frage 2 des Plans: ja, „Selbst zeichnen“ gilt, einmal auf
  einem Server angeschaltet, auch für neue Welten und Runden desselben
  Netzes und Servers. Ohne Klick des Spielers entsteht nichts.
  Rundenkarten sind Wegwerfkarten.

Den Plan hat der Reviewer freigegeben.

## Entscheidung

- **„Selbst“ für neue Welten:** Der Klick auf „Selbst“ auf einem Server
  merkt den Massstab je eingetippter Adresse und Server
  (`selbst-weiter.txt`). Betritt der Spieler dort eine Welt, für deren
  Dimension es weder eine eigene Karte noch eine andere Karte gibt und
  `zeigt.txt` nichts wählt, zeichnet der Mod selbst. Löscht der Spieler
  die eigene Karte des Servers in der Welt, in der er ist, endet es.
  Nie im Einzelspieler.
- **Rückfall:** Die Spielzeit fällt wie in 0046 weit. Jede neue
  Generation zählt einen im Ausweis.
- **Runde:** mit derselben UID jeder Rückfall; ohne UID ab dem zweiten,
  wenn die Spielzeit wieder höchstens eine Stunde vom Anfang der letzten
  Generation entfernt ist. Die Karte bleibt und füllt sich mit jeder
  Runde. Der erste Rückfall ohne UID ruht wie bisher.
- **Wegwerfkarte:** die eigene Karte einer Welt eines Servers, die nur an
  einem Tag besucht wurde oder mindestens zwei Rückfälle hat. Sie geht
  7 Tage nach dem letzten Besuch der Welt; liegen alle übrigen zusammen
  über 500 MB, gehen die am längsten nicht besuchten. Nie Downloads, nie
  im Einzelspieler, nie ohne Ausweis. Einmal je Start im Hintergrund.

## Verworfen

- **Runde schon ab dem ersten Rückfall ohne UID:** Eine echte
  Zurücksetzung auf eine frische Welt sähe gleich aus; die Karte stünde
  über der falschen Welt. Der Plan nannte den zweiten.
- **Wegwerf ab einem Rückfall:** Eine eingespielte Sicherung der
  Hauptwelt ist auch einer; deren Karte ginge dann nach einer Woche ohne
  Besuch. Mit zwei Rückfällen ist das selten.
- **„Selbst“ auch über einem Download einer neuen Welt:** Es verdeckte
  still die Karte des Servers; die Frage nach der alten Karte ist eine
  Entscheidung des Spielers.
- **Auch Downloads wegwerfen:** Der Server bietet sie wieder an, aber ein
  grosser Download kostet Zeit und Last; der Spieler löscht sie in der
  Kartenliste.
- **Eine eigene Wahl im Menü:** mehr Arbeit, ohne dass jemand danach
  fragte; der User wollte „gilt einmal angeschaltet“.

## Folgen

- Der Gametest prüft das Aufräumen nicht beim Start des Spiels; das
  übernimmt ein Unit-Test über Alter, Grösse und welche Karten zählen.
- Die Übernahme von „Selbst“ in neue Welten braucht einen Server; im
  Gametest gibt es keinen, die Regel prüft ein Unit-Test.
- Ein Ausweis ohne Rückfälle schreibt das Feld nicht; alte Ausweise lesen
  sich weiter.
