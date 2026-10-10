#!/usr/bin/env bash
# Schreibt die englischen Notizen eines Releases: die Stichpunkte aus CHANGELOG.md, was der Mod braucht, Links,
# Herausgeber und Kontakt aus NOTICE. Für GitHub wie für Modrinth dieselben. Aufruf im Repo:
# bash .github/notizen.sh <version>. Siehe docs/entwicklung.md, „Release“.
set -euo pipefail
version=$1
repo=https://github.com/VonNekyia/heroic-map-renderer-mod

punkte=$(awk -v kopf="## $version" '$0 == kopf {an = 1; next} /^## / {an = 0} an && NF' CHANGELOG.md)
[ -n "$punkte" ] || { echo "::error::CHANGELOG.md hat keinen Abschnitt „## $version“ mit Stichpunkten" >&2; exit 1; }

herausgeber=$(sed -n 's/^Herausgeber und verantwortlich: //p' NOTICE)
kontakt=$(sed -n 's/^Kontakt: //p' NOTICE)
hinweis=$(sed -n '/^NOT AN OFFICIAL/p' NOTICE | head -1)
[ -n "$herausgeber" ] && [ -n "$kontakt" ] && [ -n "$hinweis" ] \
  || { echo "::error::NOTICE nennt Herausgeber, Kontakt oder den Hinweis zu Mojang nicht" >&2; exit 1; }

cat <<EOF
**What's new in $version**

$punkte

For Minecraft 26.3 with Fabric Loader and Fabric API. The full map loads from servers with the [Heroic Map plugin](https://github.com/VonNekyia/heroic-map-renderer-plugin), or draws itself from the chunks you load.

[Documentation]($repo/blob/v$version/docs/index.md) · [All releases on GitHub]($repo/releases)

Publisher and responsible: $herausgeber. Contact: $kontakt

$hinweis
EOF
