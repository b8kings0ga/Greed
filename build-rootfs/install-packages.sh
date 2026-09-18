#!/bin/sh
set -eu
ROOTFS=/work/rootfs

# ALPINE_VERSION comes from the Dockerfile ENV (full release like 3.23.4).
# Strip the patch component to get the major branch (e.g. 3.23) used in repo URLs.
: "${ALPINE_VERSION:?ALPINE_VERSION must be set (e.g. 3.23.4)}"
ALPINE_BRANCH="${ALPINE_VERSION%.*}"

mkdir -p "$ROOTFS/etc/apk"
cat > "$ROOTFS/etc/apk/repositories" <<EOF
https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/main
https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/community
EOF

apk -X "https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/main" \
    -X "https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/community" \
    -U --allow-untrusted --root "$ROOTFS" --initdb add \
    alpine-base \
    openrc \
    busybox-openrc \
    bash \
    podman \
    docker docker-openrc docker-cli-compose \
    lxc lxc-templates lxc-download lxc-openrc lxc-bridge \
    crun \
    fuse-overlayfs \
    iptables \
    ip6tables \
    nftables \
    bridge-utils \
    iproute2 \
    dropbear dropbear-openrc \
    curl python3 libstdc++ \
    ca-certificates \
    shadow shadow-uidmap \
    slirp4netns \
    aardvark-dns netavark \
    libcap-utils \
    doas sudo \
    gcompat \
    gzip \
    zstd \
    xz \
    tigervnc \
    pulseaudio \
    pulseaudio-utils \
    font-misc-misc \
    font-cursor-misc \
    ttf-dejavu
