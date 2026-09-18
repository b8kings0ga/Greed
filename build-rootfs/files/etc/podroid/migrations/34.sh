#!/bin/sh
set -eu
backup=/mnt/persist/.podroid/native-migration-34
mkdir -p "$backup"
# Update the product runner even when a previous upgrade copied it to the upper.
path=usr/local/bin/podroid-ratatoskr-run
[ -e "$backup/runner" ] || cp -p "/$path" "$backup/runner"
cp -p "/mnt/lower/$path" "/$path"
cp -p /mnt/lower/etc/init.d/podroid-network /etc/init.d/podroid-network
# Preserve custom environment settings, adding the portable default only if absent.
if ! grep -q '^CONF_bark.identity.trace_urls=' /etc/ratatoskr/env; then
    printf '\n%s\n' 'CONF_bark.identity.trace_urls=["https://cloudflare.com/cdn-cgi/trace","https://1.1.1.1/cdn-cgi/trace"]' >> /etc/ratatoskr/env
fi

cp -p /mnt/lower/etc/init.d/podroid-ratatoskr /etc/init.d/podroid-ratatoskr
if ! grep -q '^CONF_nomad.mimc_resource_enforcement=' /etc/ratatoskr/env; then
    printf '%s\n' 'CONF_nomad.mimc_resource_enforcement=true' >> /etc/ratatoskr/env
fi
if ! grep -q '^CONF_bark.network_paths.disable_non_loopback_lan=' /etc/ratatoskr/env; then
    printf '%s\n' 'CONF_bark.network_paths.disable_non_loopback_lan=true' >> /etc/ratatoskr/env
fi
