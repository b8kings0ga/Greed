#!/bin/sh
set -eu
backup=/mnt/persist/.podroid/native-migration-37
mkdir -p "$backup"
[ -e "$backup/podroid-ratatoskr-run" ] || cp -p /usr/local/bin/podroid-ratatoskr-run "$backup/podroid-ratatoskr-run"
cp -p /mnt/lower/usr/local/bin/podroid-ratatoskr-run /usr/local/bin/podroid-ratatoskr-run
