#!/bin/sh
set -eu
backup=/mnt/persist/.podroid/native-migration-33
mkdir -p "$backup"
# Only replace product-owned service definitions. Preserve previous versions.
for path in etc/init.d/podroid-ratatoskr etc/init.d/podroid-ready etc/ratatoskr/env; do
    mkdir -p "$backup/$(dirname "$path")"
    [ -e "$backup/$path" ] || cp -p "/$path" "$backup/$path"
    cp -p "/mnt/lower/$path" "/$path"
done
# Legacy container remains on disk for rollback; never delete Docker volumes.
rc-update del docker default 2>/dev/null || true
mkdir -p /mnt/persist/ratatoskr/yggdrasil
if [ ! -s /mnt/persist/ratatoskr/yggdrasil/yggdrasil.conf ] && [ -s /mnt/persist/ratatoskr/legacy-ygg-config/yggdrasil.conf ]; then
    cp -p /mnt/persist/ratatoskr/legacy-ygg-config/yggdrasil.conf /mnt/persist/ratatoskr/yggdrasil/yggdrasil.conf
fi
