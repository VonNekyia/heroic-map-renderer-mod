---
title: "0011: Releases auf Modrinth mit curl"
description: Warum ein Release des Mods per Workflow mit curl über die API auf Modrinth landet und keine fremde Action den Token bekommt.
status: gilt
date: 2026-10-10
issues: []
code:
  - .github/workflows/modrinth.yml
  - .github/workflows/release.yml
  - .github/notizen.sh
---

# 0011: Releases auf Modrinth mit curl

Ein veröffentlichtes Release auf GitHub lädt der Workflow `modrinth.yml`
als Version auf Modrinth, mit `curl` gegen die API v2. Den Token hat nur
dieser Schritt, aus dem Secret `MODRINTH_TOKEN`.

## Grund

- **Keine fremde Action mit dem Token:** Eine Action eines Dritten bekäme
  das Recht, Versionen des Projekts anzulegen; ein Update der Action wäre
  ein Weg zum Token. Mit `curl` und `jq`, die der Runner mitbringt, steht
  alles im Repository.
- **Dieselben Notizen wie auf GitHub:** `notizen.sh` schreibt sie aus
  `CHANGELOG.md` und `NOTICE`, für beide.
- **Wie beim Plugin:** Dort lädt `hangar.yml` auf dieselbe Art nach Hangar.

## Folgen

- Das Projekt `heroic-map` ist noch in Prüfung; ohne Token antwortet die
  API dafür mit 404. Darum holt der Workflow die ID zur Laufzeit, und der
  Token braucht drei Rechte: Versionen anlegen (Create versions), Projekte
  lesen (Read projects) und Versionen lesen (Read versions), Letzteres für
  die Prüfung, ob es die Version schon gibt.
- Der User legt den Token auf modrinth.com an und trägt ihn als Secret
  ein; keine Sitzung fasst ihn an.
- 0.2.0 und 0.2.1 liegen von Hand auf Modrinth; der Workflow lädt erst ab
  0.2.9 hoch.
