#!/bin/sh
# Install a verified native network release; invoke from the guest console,
# not a Nomad task whose supervisor is being recovered.
set -eu
release=${1:?release directory required}
(cd "$release" && sha256sum -c SHA256SUMS)
[ "$("$release/mim-net")" = "$(cat "$release/VERSION")" ]
target=/usr/local/bin/mim-net
backup=/mnt/persist/.podroid/native-releases
mkdir -p "$backup"
old=$(sha256sum "$target" | awk '{print $1}')
new=$(sha256sum "$release/mim-net" | awk '{print $1}')
[ "$old" != "$new" ] || exit 0
[ -e "$backup/mim-net.$old" ] || cp -p "$target" "$backup/mim-net.$old"
cp "$release/mim-net" "$target.new"
chmod 755 "$target.new"
[ "$(sha256sum "$target.new" | awk '{print $1}')" = "$new" ]
mv "$target.new" "$target"
printf '%s\n' "$new" > "$backup/mim-net.current.sha256"
echo "Installed native mim-net release $new"
