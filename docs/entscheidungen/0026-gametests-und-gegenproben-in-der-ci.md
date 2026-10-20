---
title: "0026: Gametests und Gegenproben in der CI"
description: Warum Gametests und Gegenproben nicht mehr lokal laufen, sondern in der CI per Dispatch auf eigenen Branches, nebeneinander; lokal nur Unit-Tests und Gametests für Fehlersuche und Bilder unter der Sperre.
status: gilt
date: 2026-10-20
issues: []
code:
  - .github/workflows/ci.yml
---

# 0026: Gametests und Gegenproben in der CI

## Anlass

Drei Sitzungen arbeiten am Mod. Jede liess ihre Gametests und Gegenproben
lokal laufen, nacheinander unter einer gemeinsamen Sperre, denn neben
fremdem Gradle stürzte das Spiel am 11.10. ab (`0xCFFFFFFF`). So wartete
jede auf die anderen, eine Reihe von Gegenproben eine Stunde und mehr. Der
User will die Gametests nebeneinander. Das Repository ist öffentlich, die
Minuten der CI kosten nichts.

## Entscheidung

- **Gametests in der CI:** `ci.yml` läuft auch per `workflow_dispatch`, auf
  jedem Branch, ohne PR. Jede Sitzung startet die volle CI auf ihrem
  Branch, nebeneinander mit allen anderen.
- **Gegenproben ebenso:** je Mutation ein Branch `probe/<pr>-<n>`, per
  Dispatch gestartet, das Ergebnis aus dem Log, danach wird der Branch
  gelöscht.
- **Lokal nur Unit-Tests.** Ein lokaler Gametest bleibt für die
  Fehlersuche und für Bilder (`-Pbilder`), unter der gemeinsamen Sperre.
- Wie es geht, steht in [Bauen und testen](../entwicklung.md),
  „Gametests in der CI“.

## Verworfene Alternativen

- **Lokal nebeneinander,** je Worktree ein Spiel: nicht sicher, siehe der
  Absturz vom 11.10.; dazu teilen sich die Läufe Speicher und Kerne.
- **Ein Eingang `mods` am Dispatch,** um nur eine Hälfte der Matrix zu
  starten: Die Minuten kosten nichts, und eine Gegenprobe soll dieselben
  Läufe sehen wie die PR.

## Folgen

- Die Gametests laufen in der CI in Software (Mesa) und dauern dort gut
  3 min statt gut 2 min, siehe „CI“; dafür wartet niemand mehr.
- Ein Branch muss `main` ab dieser Änderung enthalten, sonst kennt seine
  `ci.yml` keinen Dispatch.
- Mutierter Code steht kurz auf einem öffentlichen Branch `probe/…`.
