#!/bin/sh
set -eu
# Install the independent OpenRC scheduler service on existing overlays.
# The service is started by Ratatoskr after it writes nomad.hcl, not by the
# default runlevel, so the migration cannot start a second Nomad process.
root=${PODROID_MIGRATION_ROOT:-}
source="$root/mnt/lower/etc/init.d/nomad"
target="$root/etc/init.d/nomad"
backup="$root/mnt/persist/.podroid/native-migration-40"
test -s "$source"
mkdir -p "$backup" "$root/etc/init.d"
if [ -e "$target" ] && [ ! -e "$backup/nomad" ]; then
    cp -p "$target" "$backup/nomad"
fi
cp -p "$source" "$target"
chmod 0755 "$target"
worker_source="$root/mnt/lower/usr/local/bin/mim-maintenance-worker"
worker_target="$root/usr/local/bin/mim-maintenance-worker"
service_source="$root/mnt/lower/etc/init.d/mim-maintenance"
service_target="$root/etc/init.d/mim-maintenance"
test -s "$worker_source" && test -s "$service_source"
mkdir -p "$root/usr/local/bin" "$root/etc/runlevels/default"
if [ -e "$worker_target" ] && [ ! -e "$backup/mim-maintenance-worker" ]; then
    cp -p "$worker_target" "$backup/mim-maintenance-worker"
fi
if [ -e "$service_target" ] && [ ! -e "$backup/mim-maintenance" ]; then
    cp -p "$service_target" "$backup/mim-maintenance"
fi
cp -p "$worker_source" "$worker_target"
cp -p "$service_source" "$service_target"
chmod 0755 "$worker_target" "$service_target"
ln -sf /etc/init.d/mim-maintenance "$root/etc/runlevels/default/mim-maintenance"
