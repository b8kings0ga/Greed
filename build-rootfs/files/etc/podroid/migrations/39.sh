#!/bin/sh
set -eu
# Persist the gateway listener for existing overlays as well as new rootfs builds.
# Keep unrelated settings and operator-selected non-loopback listeners intact.
root=${PODROID_MIGRATION_ROOT:-}
config="$root/etc/ratatoskr/env"
backup="$root/mnt/persist/.podroid/native-migration-39"
[ -f "$config" ] || exit 1
if grep -Eq '^CONF_bark([.]qlite[.]|__qlite__)listen=' "$config" && ! grep -Eq '^CONF_bark([.]qlite[.]|__qlite__)listen=(127[.]0[.]0[.]1|localhost|\[::1\]):14227$' "$config"; then exit 0; fi
mkdir -p "$backup"
[ -e "$backup/env" ] || cp -p "$config" "$backup/env"
tmp="$config.migration-39.$$"
trap 'rm -f "$tmp"' EXIT HUP INT TERM
awk '!/^CONF_bark([.]qlite[.]|__qlite__)listen=/' "$config" > "$tmp"
printf '%s\n' 'CONF_bark.qlite.listen=0.0.0.0:14227' >> "$tmp"
chmod 600 "$tmp"
mv "$tmp" "$config"
