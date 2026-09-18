#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
mimir="$(cd "${MIMIR_DIR:-$root/../Mimir}" && pwd)"
(cd "$mimir" && CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags '-s -w' -o "$root/build-rootfs/files/usr/local/bin/podroid-node-name" "$root/tools/node-name/main.go")

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
cp "$mimir/go.mod" "$stage/mobile.mod"
cp "$mimir/go.sum" "$stage/mobile.sum"
# Keep helper-only transitive checksums out of the user's Mimir worktree.
python3 - "$stage/mobile.mod" "$mimir" <<'PYMOD'
from pathlib import Path
import sys
p=Path(sys.argv[1]);p.write_text(p.read_text().replace('=> ./', '=> '+sys.argv[2]+'/'))
PYMOD
(cd "$mimir" && CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -mod=mod -modfile "$stage/mobile.mod" -trimpath -ldflags '-s -w' -o "$root/build-rootfs/files/usr/local/bin/podroid-node-offline" "$root/tools/node-offline/main.go")
