---
title: "0025: Neue Version von Modrinth"
description: Warum der Mod einmal je Start Modrinth nach einer neueren Version fragt, mit der Spielversion aus dem laufenden Spiel, Vorabversionen nach Semver statt nach dem Kanal, dem Repo als Kontakt im User-Agent, Vorgabe an mit Hinweis auf die IP und der festen Adresse hinter dem Bestätigungsschirm.
status: gilt
date: 2026-10-19
issues: [155]
code:
  - src/main/java/com/nekyia/heroicmap/NeueVersion.java
---

# 0025: Neue Version von Modrinth

## Anlass

Wer den Mod von Hand installiert, merkt nicht, wenn es eine neue Version
gibt. mod#155 schlägt einen Hinweis auf der Vollbildkarte vor; siehe
[Vollbildkarte](../vollbildkarte.md), „Neue Version“.

## Entscheidung

Plan freigegeben vom Reviewer, 19.10.:

- **Modrinth fragen,** einmal je Start im Hintergrund, nur Versionen für
  Fabric und die Version des laufenden Spiels. So stimmt die Frage nach
  einem Update des Spiels von selbst.
- **Vorabversionen nach Semver,** nicht nach dem Kanal: Jede Version liegt
  auf Modrinth als `beta`; ein Filter auf `release` gäbe nie einen
  Hinweis. Eine Version mit `-…` zählt nicht.
- **Der User-Agent nennt das Repo als Kontakt,** keine Mailadresse; sonst
  schickt der Mod nichts mit.
- **Vorgabe an,** mit Schalter im Untermenü. Sein Tooltip sagt, dass
  Modrinth dabei die IP sieht.
- **Fehler bleiben still:** kein Hinweis, keine Meldung im Spiel.
- **Der Link ist fest** und geht über den Bestätigungsschirm des Spiels;
  nichts aus der Antwort kommt hinein.

## Verworfene Alternativen

- **Nach dem Kanal `release` filtern:** gäbe heute nie einen Hinweis.
- **Den Link aus der Antwort nehmen:** Dann bestimmte die Antwort, was der
  Spieler öffnet.
- **Ein Hinweis im Chat:** stört beim Spielen; die Karte öffnet man, wenn
  man Zeit hat.
- **Vorgabe aus:** Dann sähen den Hinweis nur die, die ihn suchen.

## Folgen

- Jeder Start fragt Modrinth einmal, wenn der Schalter an ist.
- Solange das Projekt auf Modrinth nicht öffentlich ist, antwortet die API
  mit 404, und der Mod zeigt nichts.
