#!/bin/sh
set -eu
backup=/mnt/persist/.podroid/native-migration-36
mkdir -p "$backup"
[ -e "$backup/podroid-network" ] || cp -p /etc/init.d/podroid-network "$backup/podroid-network"
cp -p /mnt/lower/etc/init.d/podroid-network /etc/init.d/podroid-network
