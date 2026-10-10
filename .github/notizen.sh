#!/usr/bin/env bash
# Schreibt die englischen Notizen eines Releases: die Stichpunkte aus CHANGELOG.md, was der Mod braucht, Links,
# Herausgeber und Kontakt aus NOTICE. Für GitHub wie für Modrinth dieselben. Aufruf im Repo:
# bash .github/notizen.sh <version> [<vorige>]. Ohne <vorige> das letzte veröffentlichte Release, der höchste
# Tag v… unter <version> auf origin. Siehe docs/entwicklung.md, „Release“.
set -euo pipefail
version=$1
repo=https://github.com/VonNekyia/heroic-map-renderer-mod

abschnitt() {
  awk -v kopf="## $1" '$0 == kopf {an = 1; next} /^## / {an = 0} an && NF' CHANGELOG.md
}
# Ist $1 eine höhere Version als $2?
hoeher() {
  [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]
}

punkte=$(abschnitt "$version")
[ -n "$punkte" ] || { echo "::error::CHANGELOG.md hat keinen Abschnitt „## $version“ mit Stichpunkten" >&2; exit 1; }

# Jeder Release trägt einen Tag, auch wenn das Release auf GitHub später gelöscht ist; eine übersprungene Version
# hat keinen. Ohne Netz oder Tags bleibt es beim eigenen Abschnitt.
vorige=${2-$( { git ls-remote --tags --refs origin 'v*' 2>/dev/null || true; } \
  | sed -n 's#.*refs/tags/v\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)$#\1#p' \
  | while read -r v; do if hoeher "$version" "$v"; then echo "$v"; fi; done | sort -V | tail -1)}

# Die Abschnitte zwischen dem letzten Release und diesem, neueste zuerst, wie im CHANGELOG.
weitere=""
if [ -n "$vorige" ]; then
  for v in $(sed -n 's/^## \([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)$/\1/p' CHANGELOG.md); do
    if hoeher "$version" "$v" && hoeher "$v" "$vorige"; then
      weitere+=$'\n\n'"**Also in $v**, which was not released on its own:"$'\n\n'"$(abschnitt "$v")"
    fi
  done
fi

herausgeber=$(sed -n 's/^Herausgeber und verantwortlich: //p' NOTICE)
kontakt=$(sed -n 's/^Kontakt: //p' NOTICE)
hinweis=$(sed -n '/^NOT AN OFFICIAL/p' NOTICE | head -1)
[ -n "$herausgeber" ] && [ -n "$kontakt" ] && [ -n "$hinweis" ] \
  || { echo "::error::NOTICE nennt Herausgeber, Kontakt oder den Hinweis zu Mojang nicht" >&2; exit 1; }

cat <<EOF
**What's new in $version**

$punkte$weitere

For Minecraft 26.3 with Fabric Loader and Fabric API. The full map loads from servers with the [Heroic Map plugin](https://github.com/VonNekyia/heroic-map-renderer-plugin), or draws itself from the chunks you load.

[Documentation]($repo/blob/v$version/docs/index.md) · [All releases on GitHub]($repo/releases)

Publisher and responsible: $herausgeber. Contact: $kontakt

$hinweis
EOF
