#!/usr/bin/env bash
# Packs a built Remote Control.app into a drag-to-Applications disk image and
# writes its SHA-256 beside it.
#
#   scripts/make-dmg.sh "<path>/Remote Control.app" <output .dmg>
#
# The release workflow (.github/workflows/macos-release.yml) runs it on every
# version tag; it runs the same by hand on a local Release build.
set -euo pipefail

app="${1:?the path to Remote Control.app}"
out="${2:?the disk image to write}"
[[ -d "$app/Contents/MacOS" ]] || { echo "no app bundle at $app" >&2; exit 2; }

stage="$(mktemp -d "${TMPDIR:-/tmp}/rc-dmg.XXXXXX")"
trap '/bin/rm -rf "$stage"' EXIT
# mktemp makes the folder private; the image's root is everyone's to open.
chmod 755 "$stage"

# ditto keeps the bundle's signature, extended attributes and symlinks intact.
ditto "$app" "$stage/Remote Control.app"
ln -s /Applications "$stage/Applications"

mkdir -p "$(dirname "$out")"
hdiutil create -volname "Remote Control" -srcfolder "$stage" -fs HFS+ \
  -format UDZO -imagekey zlib-level=9 -ov "$out" >/dev/null
hdiutil verify "$out" >/dev/null
(cd "$(dirname "$out")" && shasum -a 256 "$(basename "$out")" > "$(basename "$out").sha256")
echo "$out"
