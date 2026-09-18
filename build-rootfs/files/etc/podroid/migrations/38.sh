#!/bin/sh
set -eu
backup=/mnt/persist/.podroid/native-migration-38
mkdir -p "$backup"
[ -e "$backup/podroid-ratatoskr-run" ] || cp -p /usr/local/bin/podroid-ratatoskr-run "$backup/podroid-ratatoskr-run"
cp -p /mnt/lower/usr/local/bin/podroid-ratatoskr-run /usr/local/bin/podroid-ratatoskr-run
name=/mnt/persist/ratatoskr/node-name
if [ -f "$name" ] && grep -q '^greed-' "$name"; then
    mv "$name" "$backup/legacy-node-name"
fi
for key in leave_on_terminate leave_on_interrupt; do
    grep -q "^CONF_nomad.$key=" /etc/ratatoskr/env || printf 'CONF_nomad.%s=true\n' "$key" >> /etc/ratatoskr/env
done

ln -sf /etc/init.d/acpid /etc/runlevels/default/acpid

[ -e "$backup/podroid-ratatoskr" ] || cp -p /etc/init.d/podroid-ratatoskr "$backup/podroid-ratatoskr"
cp -p /mnt/lower/etc/init.d/podroid-ratatoskr /etc/init.d/podroid-ratatoskr
