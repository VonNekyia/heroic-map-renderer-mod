---
title: "0017: Gametests in der CI, auch mit Sodium"
description: Warum die CI die Gametests unter Xvfb laufen lässt, einmal ohne und einmal mit Sodium in der Version des Modpacks, warum Sodium mit fester Version und Prüfsumme nur für die Gametests kommt und was das für die Lizenz heisst.
status: gilt
date: 2026-10-10
issues: [84]
code:
  - .github/workflows/ci.yml
  - build.gradle.kts
  - gradle/libs.versions.toml
---

# 0017: Gametests in der CI, auch mit Sodium

## Anlass

Bis hier hat die CI die Gametests nur kompiliert, laufen liessen sie nur
die Programmierer lokal. Der Fehler aus mod#84 zeigte sich nur mit
Sodium, siehe [0016](0016-zweiter-haken-an-der-welt.md). Das Modpack
bringt Sodium mit, also läuft fast jeder Spieler damit. Der Reviewer
verlangte deshalb im Review von #92 die Gametests in der CI auch mit
Sodium.

## Entscheidung

- **Die Gametests laufen in der CI,** im Job „Gametests“ unter Ubuntu, in
  einem Fenster ohne Bildschirm: Xvfb mit 24 Bit Farbtiefe, OpenGL in
  Software über Mesa, den Kontext über EGL (`SDL_VIDEO_FORCE_EGL=1`). Das
  Spiel nimmt dort wie lokal sein OpenGL-Backend.
- **Zweimal:** einmal ohne andere Mods, einmal mit Sodium (`-Psodium`),
  als Matrix nebeneinander.
- **Sodium nur für die Gametests:** eine eigene Configuration `sodium`,
  aus dem Maven von Modrinth, das nur die Gruppe `maven.modrinth` liefern
  darf. Sodium liegt auf keinem Klassenpfad zum Kompilieren, nicht im Jar
  und nicht in `fabric.mod.json`. Ohne `-Psodium` löst Gradle sie nicht
  auf.
- **Feste Version mit Prüfsumme:** Version und SHA-256 stehen in
  `gradle/libs.versions.toml`. Vor dem Lauf prüft der Build die SHA-256
  der geladenen Datei und bricht bei Abweichung ab.
- **Folgt dem Modpack:** dieselbe Version wie im Modpack der Website,
  heute `sodium-fabric-0.9.2+mc26.3`. Hebt das Modpack Sodium, zieht diese
  Version mit.
- **Kein Pflicht-Check,** bis Dauer und Stabilität in der CI bekannt sind;
  dann entscheidet der Reviewer.
- **Bilder und Schwellen bleiben:** Weicht in Software ein Bild oder eine
  Schwelle ab, wird sie nicht still gelockert, sondern dem Reviewer mit dem
  Bild gemeldet.

## Lizenz

Sodium steht unter der PolyForm Shield License 1.0.0. Der Mod übernimmt
keinen Code daraus und verteilt nichts davon: Die CI und `-Psodium` laden
das Jar nur, um die eigenen Gametests damit laufen zu lassen. Eine Frage an
den User braucht es deshalb nicht, siehe Regel 25 in `AGENTS.md` des
Hauptrepositorys.

## Verworfene Alternativen

- **Weiter nur lokal mit `-Pzusatzmods`:** Ob jemand vor dem Merge mit
  Sodium testet, hinge an der Erinnerung; mod#84 kam genau so durch.
- **Das Jar mit `curl` in der CI holen:** Dann prüfte nur die CI die
  Prüfsumme, und lokal fehlte derselbe Weg.
- **Gradles Prüfung der Abhängigkeiten (`verification-metadata.xml`):**
  Sie gilt für jede Abhängigkeit des Builds, nicht nur für eine; für ein
  Jar nur zum Testen wäre das zu viel.
- **Weitere Mods aus dem Modpack, etwa Lithium oder Iris:** erst, wenn ein
  Fehler auf sie zeigt; sie kämen genauso dazu.
- **GLX unter Xvfb:** In zwei Läufen fand SDL3 kein Visual für das Fenster
  des Spiels, auch mit `libglx-mesa0`. Siehe
  [Bauen und testen](../entwicklung.md), „CI“.

## Folgen

- **CI-Zeit:** zwei Jobs mehr, nebeneinander, je gut 3 min für die
  Gametests; Einzelheiten in [Bauen und testen](../entwicklung.md), „CI“.
- **Netz:** Mit `-Psodium` braucht der Build den Maven von Modrinth.
