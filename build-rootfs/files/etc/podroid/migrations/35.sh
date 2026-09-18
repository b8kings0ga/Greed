#!/bin/sh
set -eu
backup=/mnt/persist/.podroid/native-migration-35
mkdir -p "$backup"
[ -e "$backup/podroid-ratatoskr" ] || cp -p /etc/init.d/podroid-ratatoskr "$backup/podroid-ratatoskr"
cp -p /mnt/lower/etc/init.d/podroid-ratatoskr /etc/init.d/podroid-ratatoskr
