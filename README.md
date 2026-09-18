<div align="center">

<img src="docs/logo.png" alt="Podroid logo" width="120" />

# Podroid

**Run Linux containers and a full Linux desktop on your Android phone. No root.**

A real Alpine Linux VM with its own kernel - not a chroot or proot trick - so **Podman, Docker and LXC** behave exactly like they do on a server.

[![Release](https://img.shields.io/github/v/release/ExTV/Podroid?include_prereleases&style=flat-square&label=release&color=blue)](https://github.com/ExTV/Podroid/releases)
[![Downloads](https://img.shields.io/github/downloads/ExTV/Podroid/total?style=flat-square&color=brightgreen)](https://github.com/ExTV/Podroid/releases)
[![Stars](https://img.shields.io/github/stars/ExTV/Podroid?style=flat-square&color=yellow)](https://github.com/ExTV/Podroid/stargazers)
[![License](https://img.shields.io/github/license/ExTV/Podroid?style=flat-square)](LICENSE)
![Android 9+](https://img.shields.io/badge/Android-9%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![arm64](https://img.shields.io/badge/arch-arm64-orange?style=flat-square)

[**Website**](https://extv.github.io/Podroid/) · [**Documentation**](https://extv.github.io/Podroid/guide/) · [**Download APK**](https://github.com/ExTV/Podroid/releases/latest)

<table>
  <tr>
    <td align="center" width="25%"><img src="docs/screenshots/01-home-idle.png" alt="Home screen before the VM starts" width="190" /><br /><sub><b>Home</b></sub></td>
    <td align="center" width="25%"><img src="docs/screenshots/02-home-running.png" alt="Home screen with the VM running and network info" width="190" /><br /><sub><b>Running</b></sub></td>
    <td align="center" width="25%"><img src="docs/screenshots/03-terminal-fastfetch.png" alt="Built-in terminal showing Alpine system info" width="190" /><br /><sub><b>Terminal</b></sub></td>
    <td align="center" width="25%"><img src="docs/screenshots/04-quick-settings.png" alt="Terminal Quick Settings with themes and fonts" width="190" /><br /><sub><b>Themes &amp; fonts</b></sub></td>
  </tr>
</table>

</div>

## What you get

- **Podman, Docker and LXC** - pre-installed, ready the moment it boots
- **A real VM** - Alpine Linux on a custom kernel via QEMU, or hardware-accelerated AVF on supported pKVM devices
- **In-app terminal** - full xterm-256color, 122 color themes, 13 fonts, live resize
- **X11 desktop** - run GUI Linux apps in a built-in viewer with touch, keyboard, mouse and audio
- **USB passthrough**, **NFC tag gateway**, **SSH**, **port forwarding** and a **guest-to-Android bridge**
- **English and 中文**, no root, any arm64 device on Android 9+

## Quick start

1. [Download the APK](https://github.com/ExTV/Podroid/releases/latest) and install it.
2. Tap **Start VM**, wait for **Ready!**, open the terminal.

```sh
# rootless containers, straight away
podman run --rm alpine echo "hello from a container"
rc-service docker start
docker run -d -p 8080:80 nginx

# expose that container to your phone and LAN, right from the VM shell
podroid-forward add 8080 8080 tcp     # TCP or UDP, on both the QEMU and AVF backends
curl http://<phone-ip>:8080

# SSH in from your laptop (enable SSH in the setup wizard or Settings)
ssh root@<phone-ip> -p 9922        # password: podroid
```

Setup, the two backends, networking, the X11 viewer and troubleshooting all live in the **[documentation](https://extv.github.io/Podroid/guide/)**.

## NFC gateway

Podroid can expose the phone's NFC reader to Alpine services without passing the
hardware into the VM. Keep the VM running and Podroid in the foreground with the
phone unlocked, then use the machine-readable guest CLI:

```sh
sense nfc status            # hardware, NFC switch, reader and queue state
sense nfc current           # most recently retained tag, or null
sense nfc next -            # oldest retained event + an opaque cursor
sense nfc next <cursor>     # next event after that cursor, or null
```

`next` is non-blocking; gateway services should poll every 250–500 ms. Each
service keeps its own cursor and persists it only after its IoT action succeeds,
so consumers do not steal events from one another. Events contain the tag ID,
technology list and a size-bounded NDEF snapshot. Tag IDs can be cloned or
randomized, so security-sensitive actions should verify signed tag content and
must not treat the ID alone as authentication.

These commands are available on the Alpine host. Containers do not receive the
general host-bridge socket by default; expose NFC through a narrow read-only
proxy instead of mounting that socket into an untrusted container.

## Build

```sh
git clone https://github.com/ExTV/Podroid.git
cd Podroid
./build-all.sh all     # kernel, rootfs, QEMU and APK (needs Docker + Android SDK/NDK)
```

Per-component builds and toolchain details: [CONTRIBUTING.md](CONTRIBUTING.md).

## Contributing

Contributions of every size are welcome: bug reports, kernel-config tweaks, new themes, UI polish, X11 input fixes, anything.

- **Pull requests:** read [CONTRIBUTING.md](CONTRIBUTING.md) first. Keep changes scoped, run `./build-all.sh test` before pushing, and explain *why* in the PR description.
- **Bug reports:** [open an issue](https://github.com/ExTV/Podroid/issues/new) with your device and Android version, a short repro, and the diagnostic log (**Settings → Export Diagnostic Log** in the app).

## Credits

| | |
|---|---|
| [QEMU](https://www.qemu.org)                   | Machine emulation |
| [Termux](https://github.com/termux/termux-app) | Terminal emulator engine |
| [Alpine Linux](https://alpinelinux.org)        | The guest distribution |

Full list in [CREDITS.md](CREDITS.md).

## License

[GPLv2](LICENSE). If Podroid is useful to you, a [star](https://github.com/ExTV/Podroid/stargazers) helps other people find it.

## Greed / Mimir node

Greed starts Mimir directly under Alpine OpenRC. Ratatoskr, Nomad-min, mim-net,
and Bun/uv are bundled as native arm64 programs. Docker remains available for
manual container use (`rc-service docker start`), but node startup does not load
or run a Docker image.

```sh
task vegvisir:update          # current ~/.mim/vegvisir.json; override VEGVISIR_FILE
./build-all.sh ratatoskr-native
./build-all.sh rootfs
./build-all.sh apk
task sideload
```

Before migrating an existing Docker node, preserve its Yggdrasil configuration:

```sh
docker update --restart=no ratatoskr
docker stop -t 120 ratatoskr
docker cp ratatoskr:/opt/yggdrasil/config /mnt/persist/ratatoskr/legacy-ygg-config
```

Upgrade in place; never reset storage. Migration 33 backs up old service/env
files, retains `/mnt/persist/ratatoskr/nomad`, and imports the preserved Yggdrasil
identity. Check `rc-service podroid-ratatoskr status`, `/var/log/ratatoskr.log`,
and Nomad readiness after boot. The phone needs working Internet and a correct
clock for TLS and cluster discovery.

For an authenticated Mimir cluster, provision its internal **client** credential
into the guest at `/var/lib/ratatoskr/nats-client/internal.creds` with directory
mode 0700 and file mode 0600, then restart `podroid-ratatoskr`. Credentials are
persistent device state and are never included in the APK; do not copy operator
signing keys or system-account credentials. The node name is generated once from
the retained Nomad client ID. Native restarts restore bootstrap DNS before Mimir
starts its local resolver, so an ordinary service restart does not break lookup.

Disk size changes grow the existing image and never shrink or recreate it, on
both VM backends. A failed filesystem repair preserves the disk for recovery.
The launcher reports running only after the guest emits its Ready marker;
slow boots remain visibly in the starting state.

Native QEMU networking keeps Android/SLIRP port forwards as the ingress authority; it does not apply the server public-interface firewall to the private guest NIC. Mimir allocation isolation remains enabled, AVF keeps its normal firewall default, and explicit operator overrides are respected.

Mobile lifecycle defaults (version 39): names use Mimir's shared region/alias
algorithm. Automatic location accepts a Cloudflare POP only when its country
matches the client IP country; otherwise it uses a same-country latency landmark
or an unprefixed stable alias when location is uncertain. Already cached names
remain unchanged. Maximum resources use all host processors and RAM after the Android
reserve. Settings can switch to manual limits. Back from the launcher or removal
from recents requests guest poweroff; OpenRC reports this node down to Nomad before shutting down the overlay,
while the foreground service retains its wake lock. Home/backgrounding remains
active. Android force-stop or process death cannot guarantee a shutdown callback.
