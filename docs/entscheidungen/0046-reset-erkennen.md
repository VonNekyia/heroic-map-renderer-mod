---
title: "0046: Reset erkennen"
description: Warum der Mod eine zurückgesetzte Welt an der UID vom Plugin oder an einem neuen Seed bei gleicher Adresse, gleichem Server und gleicher Welt sicher erkennt und ihre alte Karte löscht, ohne UID bei weit gefallener Spielzeit nur ruhen lässt, und die Wegpunkte bleiben; Baustein 3 des Kartenausweises.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Reset.java
  - src/main/java/com/nekyia/heroicmap/Ausweise.java
  - src/main/java/com/nekyia/heroicmap/Downloads.java
  - src/main/java/com/nekyia/heroicmap/Satz.java
---

# 0046: Reset erkennen

## Anlass

Wird eine Welt zurückgesetzt, etwa eine Farmwelt, passt die alte Karte
nicht mehr. Mit festem Seed zeichnete „Selbst“ sonst still in die alte
Karte. Der User hat entschieden: Ist ein Reset sicher erkannt, wird die
alte Karte gelöscht (1b); bei unsicheren Zeichen ruht sie, und die
Kartenliste bietet das Löschen an. Baustein 3 des Konzepts
„Kartenausweis“; das Plugin schickt dafür ab 0.20.1 je Welt die UID
(Baustein 2). Den Ablauf hat der Reviewer freigegeben.

## Entscheidung

- **Sicher, UID:** Der Ausweis kennt eine UID der Welt, und das Plugin
  schickt eine andere. Die Bäume dieser Welt im Ordner werden gelöscht,
  Download und eigene Karte; die eigene beginnt neu im selben Massstab.
- **Sicher, neuer Seed:** Derselbe Server, also dieselbe eingetippte
  Adresse und derselbe Name vom Plugin, dieselbe Welt, ein anderer Hash.
  Die Bäume dieser Welt im alten Ordner werden gelöscht, aber nur, wenn
  der alte Ordner zuletzt besucht war, bevor der neue zum ersten Mal.
- **Unsicher, Spielzeit fällt:** nur ohne UID vom Plugin. Fällt die
  Spielzeit unter ein Viertel der zuletzt gesehenen und um mehr als eine
  Stunde (72 000 Ticks), ruhen die Bäume dieser Welt unter
  `<baum>-bis-<jjjjmmtt>` mit der Marke `ruht.txt`, und eine neue Karte
  beginnt. Viertel und Stunde sind vorläufige Werte, die wir an echten
  Servern nachschärfen.
- **Die Wegpunkte bleiben,** auch wenn sie in der neuen Welt nicht mehr
  stimmen; die Zeile im Chat sagt es. Der User kann das später ändern.
- **Nie bei laufendem Download;** dann versucht es der nächste Besuch.
- **Ein ruhender Baum zeigt nie** (`Satz.fuer`).

## Verworfen

- **Gleicher Name und gleiche Karte reichen für „derselbe Server“:** Ein
  Spiegel meldet denselben Namen wie der Server, den er spiegelt, und
  hat dort eine andere Welt; jeder Besuch löschte die Karte des anderen.
  Darum zählt die eingetippte Adresse mit, und die Zeitbedingung schliesst
  ein Wechselspiel aus.
- **Die Spielzeit auch mit UID prüfen:** Die UID ist eindeutig; ein
  Rückgang der Spielzeit, etwa nach dem Einspielen einer Sicherung, ist
  dann kein Reset.
- **Ohne UID gleich löschen:** Auf Netzwerken mit vielen Pods weicht die
  Spielzeit je Pod ab; gelöscht würde, was nur anders gezählt ist.
- **Den Spieler jedes Mal fragen:** Der User wollte keine Frage im Spiel.

## Folgen

- Ohne Plugin 0.20.1 gibt es keinen sicheren Reset mit festem Seed; dann
  greift nur „unsicher“.
- Nach einem Reset steht im Ausweis die neue Generation: Spielzeit von
  vorn, die neue UID. So löscht der nächste Besuch nicht noch einmal.
- Runden aus einer Vorlage, deren Spielzeit jedes Mal zurückspringt,
  ruhten bei jedem Rückfall; Baustein 6 erkennt sie und lässt sie in Ruhe.
