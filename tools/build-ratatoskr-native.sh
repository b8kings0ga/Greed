#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
mimir="$(cd "${MIMIR_DIR:-$root/../Mimir}" && pwd)"
out="$root/build-rootfs/files/opt/podroid/assets/ratatoskr-native.tar.zst"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
chmod 0755 "$stage"
mkdir -p "$stage/usr/local/bin" "$stage/opt/cni/bin" "$stage/opt/cni/config"
(cd "$mimir" && CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -tags netgo,osusergo -ldflags '-s -w' -o "$stage/usr/local/bin/ratatoskr" ./cmd/ratatoskr)
# Use the scheduler release already imported and validated by Mimir.
tar --zstd -xf "$mimir/dist/runtime-tools/nomad-min-linux-arm64.tar.zst" -C "$stage"
# Mimir's Dockerfile pins and verifies musl Bun/uv; older imported archives may
# contain glibc uv, which cannot run correctly under Alpine/gcompat.
docker build --platform linux/arm64 --target runtime-tools --build-arg TARGETARCH=arm64 -t podroid-runtime-tools "$mimir"
runtime_container=$(docker create podroid-runtime-tools /bin/true)
docker cp "$runtime_container:/runtime/." "$stage/"
docker rm "$runtime_container" >/dev/null
install -m 755 "$mimir/dist/bin/arm64/mim-net" "$stage/usr/local/bin/mim-net"
install -m 644 "$mimir/mim-net/dist/10-mim-net.conflist" "$stage/opt/cni/config/10-mim-net.conflist"
sh "$mimir/scripts/mim-net-version.sh" > "$stage/opt/cni/bin/.mim-version"
ln -sf /usr/local/bin/mim-net "$stage/opt/cni/bin/mim-net"
# Verify the imported binaries execute on Alpine and match the coupled CNI version.
docker run --rm --platform linux/arm64 -v "$stage:/payload:ro" alpine:3.23 sh -ec '
    apk add --no-cache libstdc++ >/dev/null
    test "$(/payload/usr/local/bin/mim-net)" = "$(cat /payload/opt/cni/bin/.mim-version)"
    /payload/usr/local/bin/nomad version
    /payload/usr/local/bin/ratatoskr mimc-version
    /payload/opt/mim/runtime/bin/uv --version
    /payload/opt/mim/runtime/bin/bun --version
'
mkdir -p "$(dirname "$out")"
COPYFILE_DISABLE=1 tar --no-xattrs --zstd -C "$stage" -cf "$out.tmp" .
mv "$out.tmp" "$out"
