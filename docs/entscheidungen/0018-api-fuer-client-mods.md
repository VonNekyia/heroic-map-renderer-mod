---
title: "0018: API für Client-Mods"
description: Warum andere Fabric-Mods Ebenen über eine kleine öffentliche Klasse anlegen, im JSON der Ebenen vom Server, mit englischen Namen, eingebunden über einen Entrypoint und Modrinth Maven, und warum die Ebene vom Server bei gleicher Kennung gilt.
status: gilt
date: 2026-10-11
issues: [35]
code:
  - src/main/java/com/nekyia/heroicmap/api/HeroicMapClientApi.java
  - src/main/java/com/nekyia/heroicmap/Ebenen.java
---

# 0018: API für Client-Mods

## Anlass

Der letzte Punkt von mod#35: Andere Fabric-Mods sollen beim Spieler eigene
Ebenen anlegen. Der User: „füge sie noch hinzu, ist doch nicht viel“. Plan
vom Reviewer freigegeben.

## Entscheidung

- **Eine Klasse,** `com.nekyia.heroicmap.api.HeroicMapClientApi`, mit
  `VERSION`, `put`, `remove` und dem Entrypoint-Interface `Listener`. Die
  Version steigt mit jeder Änderung, wie bei `skin-api.ts` des Renderers.
- **Englische Namen** für alles Öffentliche, wie die API des Plugins;
  intern bleibt alles deutsch.
- **Das Format vom Server,** ein Eintrag der Liste und `objects` eines
  Teils als JSON. Es gibt nur ein Format, und derselbe Code liest es.
- **Die Ebene vom Server gilt** bei gleicher Kennung, die des Mods ist so
  lange nicht zu sehen. `heroicmap:` ist gesperrt.
- **Einbinden** über den Entrypoint `heroicmap` und `compileOnly` gegen das
  Jar aus dem Maven von Modrinth.
- **v1 ohne Banner und Symbole,** weil ihre Bilder nur vom Server kommen.

## Verworfene Alternativen

- **Java-Objekte statt JSON,** etwa `Pin`, `Circle`, `Line`: ein zweites
  Format neben dem vom Server, das mit ihm auseinanderliefe.
- **Die Ebene des Mods gewinnt:** Ein Mod könnte eine Ebene des Servers
  überdecken, etwa Grenzen fälschen.
- **Nur `isModLoaded` statt Entrypoint:** Jeder Mod müsste selbst prüfen und
  seine Klassen vorsichtig laden; der Entrypoint lädt sie nur, wenn Heroic
  Map da ist.
- **JitPack wie beim Plugin:** Das Jar liegt schon auf Modrinth, und dessen
  Maven liefert es mit fester Version.

## Folgen

- **Öffentlich:** `Ebenen` ist dafür eine öffentliche Klasse mit zwei
  öffentlichen Methoden, `vonMod` und `ohneMod`; alles andere bleibt
  im Paket. Sie sind nur für `HeroicMapClientApi` gedacht.
- **Pflege:** Ändert sich das Format der Ebenen, ändert es sich hier mit;
  steigt dann `VERSION`, steht es in [API für Client-Mods](../api.md).
