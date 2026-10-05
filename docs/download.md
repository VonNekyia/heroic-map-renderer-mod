---
title: Download
description: Wie der Mod die Karte vom Plugin lädt, mit dem Kanal heroicmap:karte, den Befehlen, Zustimmung und Grösse, der Prüfung der Adresse, Manifest und Prüfsumme, den harten Grenzen, der Ablage je Server, Baum und Massstab, Fortsetzen und Abgleich, und was der Offline-Modus heisst.
code:
  - src/main/java/com/nekyia/heroicmap/Kanal.java
  - src/main/java/com/nekyia/heroicmap/Downloads.java
  - src/main/java/com/nekyia/heroicmap/Laden.java
  - src/main/java/com/nekyia/heroicmap/Adresse.java
  - src/test/java/com/nekyia/heroicmap/LadenTest.java
  - src/test/java/com/nekyia/heroicmap/AdresseTest.java
---

# Download

Der Mod lädt die Karte, die das Plugin eines Servers anbietet, als einzelne
Kacheln auf die Platte. Das Protokoll steht an einer Stelle, in
[`docs/download.md` des Plugins](https://github.com/VonNekyia/heroic-map-renderer-plugin/blob/main/docs/download.md),
Plan und Entscheidungen an
[heroic-map-renderer#154](https://github.com/VonNekyia/heroic-map-renderer/issues/154).
Diese Seite sagt, was der Mod davon tut. Die Vollbildkarte aus den Kacheln
kommt mit dem nächsten Schritt.

## Kanal

- **`heroicmap:karte`** in beide Richtungen, UTF-8-JSON ohne Längenpräfix
  (`Kanal`). Der Mod meldet ihn für beide Richtungen an; Fabric sagt es dem
  Server mit `minecraft:register`, und das Plugin schickt dann sein
  `angebot`.
- **Empfangen:** `angebot` merkt sich der Mod, `freigabe` startet einen
  Download, `abgelehnt` zeigt er dem Spieler. Nachrichten mit einem anderen
  `v` als 1 oder über 64 KiB verwirft er.
- **Senden:** `anfrage` nur, wenn `ClientPlayNetworking.canSend` wahr ist,
  also wenn das Plugin den Kanal angemeldet hat.

## Befehle

| Befehl | tut |
|---|---|
| `/heroicmap angebot` | die angebotenen Karten mit Dimension und Grösse je Massstab |
| `/heroicmap laden <baum> <1, 2 oder 4>` | fragt einen vollen Download an; vorher sagt der Mod, dass er gegen die Grenze des Servers zählt, und bei einem anderen Massstab, dass der alte Satz wegfällt |
| `/heroicmap abgleich <baum>` | fragt einen Abgleich von Hand an, im gespeicherten Massstab |

Die Vollbildkarte bekommt dafür später Knöpfe.

## Zustimmung und Grösse

- **Vor einem vollen Download** fragt ein Dialog: Er nennt den Spielserver,
  den Host der Karte, die Grösse aus `bytes` und den Massstab, und dass der
  Mod Daten von diesem Host lädt.
- **Die Zustimmung** gilt je Paar aus Spielserver und Host, gespeichert in
  `heroicmap/zustimmung.txt` im Spielordner.
- **Der tägliche Abgleich:** Schickt das Plugin von sich aus eine `freigabe`
  mit `art` `abgleich` und die Zustimmung liegt vor, lädt der Mod im
  Hintergrund, ohne Dialog.
- **Im Einzelspieler** gibt es keinen Server und keinen Download.

## Sicherheit

- **Adresse** (`Adresse.pruefe`): nur `http` und `https` mit Host. Zeigt der
  Host auf loopback, link-local, ein privates Netz oder `fc00::/7`, der
  Spielserver aber nicht, lehnt der Mod ab; sonst könnte ein Server
  Anfragen in das Heimnetz des Spielers lenken.
- **Keine Weiterleitungen** (`Redirect.NEVER`): So geht das Token nie an
  einen dritten Host.
- **Das Token** ist für den Mod undurchsichtig; er schickt es nur im Header
  `Authorization: Bearer`.
- **Offline-Modus:** Ein Server im Offline-Modus verschlüsselt die
  Spielverbindung nicht. Dann ist auch `manifest_sha256` über den Kanal
  nicht abgesichert, und ohne HTTPS kann ein Lauscher das Token mitlesen;
  der Deckel der Bytes je Token begrenzt, was er damit lädt.
- **Kacheln** prüft der Mod nur über ihre Grösse, nicht ihren Inhalt.

## Ablauf

`Laden.lade`, im Hintergrund:

1. **`map.json`** holen; daraus `minZoom` und `maxZoom`. Die Stufe des
   Massstabs: 4 px ist `maxZoom`, 2 px eine gröber, 1 px zwei.
2. **Manifest** holen und SHA-256 über das gzip mit `manifest_sha256`
   vergleichen. Passt es nicht, endete inzwischen ein Lauf; der Mod fragt
   einmal neu an und bekommt dasselbe Token mit dem neuen Manifest.
3. **Entpacken** und die Zeilen bis zur Stufe des Massstabs nehmen.
4. **Löschen,** was lokal liegt und nicht mehr im Manifest steht.
5. **Laden,** was fehlt oder ein anderes ETag hat, über 4 Verbindungen mit
   Keep-Alive (`java.net.http.HttpClient`, HTTP/1.1). Gespeichert wird das
   ETag aus der Antwort, denn eine Kachel kann neuer sein als das Manifest.
6. **Der Index** `etags.txt` wird am Ende geschrieben, auch nach einem
   Abbruch; was er nennt, lädt der nächste Versuch nicht noch einmal.

## Harte Grenzen

| Grenze | Wert | bei Verstoss |
|---|---|---|
| Manifest entpackt, `map.json` | 64 MiB | Abbruch |
| Zeile des Manifests | `z/x/y` ganze Zahlen, `z` zwischen `minZoom` und `maxZoom`, Grösse bis 4 MiB, ETag ohne Leer- und Steuerzeichen | Abbruch |
| eine Kachel | 4 MiB | Abbruch |
| Summe des Geladenen | `bytes` aus `freigabe` plus 10 % | der Rest bleibt liegen, „zum Teil geladen“ |
| Antwort | 200 | 401, 403 und 429 heissen abgelehnt, alles andere Abbruch |

`z/x/y` wird ein Dateipfad; deshalb nur ganze Zahlen, auch beim Lesen des
eigenen Index. Ein Abbruch meldet dem Spieler den Grund.

## Ablage

- **Ordner:** `heroicmap/<server>/<baum>/<massstab>/` im Spielordner, darin
  `map.json`, `etags.txt` und `z/x/y.webp`. `<server>` ist die Adresse des
  Servers, klein, andere Zeichen als Buchstaben, Ziffern, Punkt und Strich
  werden `_`. Ein Baum heisst nur `[a-z0-9_-]`, höchstens 64 Zeichen.
- **Schreiben** über eine Zwischendatei, dann verschieben; nie liegt eine
  halbe Kachel da.
- **Eine Auflösung je Baum:** Ist ein Download vollständig, fallen die
  anderen Massstäbe dieses Baums weg. Bis dahin bleibt der alte Satz, damit
  der Spieler nicht ohne Karte dasteht; ein gekappter Download löscht nichts.
- **Fortsetzen:** Was mit gleichem ETag schon da ist, lädt der Mod nicht.
  Ein Abgleich ist derselbe Weg mit weniger Kacheln.

## Was bleibt eine Näherung

- **Auflösen und Laden** sind zwei Schritte: Der Host kann zwischen der
  Prüfung der Adresse und dem Abruf auf eine andere Adresse zeigen.
- **Plattenplatz** prüft der Mod nicht; der Dialog nennt nur die Grösse.
