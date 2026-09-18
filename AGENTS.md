# AGENTS.md

Guidance for Codex (and any AI assistant or new contributor) working in this repository. This is the deep map: read it before touching anything. Keep it accurate when you change the code.

## What Is This

Podroid is an Android app that runs a real **Alpine 3.23** Linux VM on stock Android 9+ (arm64) to provide rootless **Podman / Docker / LXC** containers and an in-app **X11 desktop** - no root, no custom recovery.

- **Two interchangeable VM backends** behind one interface (`VmEngine`):
  - **QEMU (TCG)** - software emulation, the default, needs no special permission.
  - **AVF (pKVM)** - hardware-accelerated via the Android Virtualization Framework on Pixel-class devices, after a one-time `pm grant`.
- The guest is a standard Alpine root with **OpenRC as PID 1**. System services live in `/etc/init.d/podroid-*` on a read-only squashfs; a persistent ext4 overlay captures user changes (`apk add`, `rc-update add`).
- An embedded **Termux-based terminal**, an **X11/VNC viewer** (Xvnc + PulseAudio), and a **guest-to-Android bridge** (`podroid-notify` / `podroid-forward`) round it out.

## Key Facts

| | |
|---|---|
| Package | `com.excp.podroid` (debug: `com.excp.podroid.debug`) |
| Version | `versionName` / `versionCode` in `app/build.gradle.kts` |
| Min / target SDK | 28 (Android 9) / 36 |
| Architecture | arm64 (`aarch64`) only |
| Guest | Alpine 3.23 squashfs + persistent ext4 overlay, OpenRC PID 1 |
| Kernel | custom Linux, version pinned by `podroidKernelVersion` in `gradle.properties` |
| QEMU | version pinned by `podroidQemuVersion` in `gradle.properties` |
| UI | Jetpack Compose + Material 3, single Activity |
| DI | Hilt (constructor injection) |
| Async | Coroutines + StateFlow |
| Persistence | Jetpack DataStore (no database; Room is declared but unused) |
| Terminal | vendored Termux fork as local Gradle modules (`terminal-view`, `terminal-emulator`) |
| Languages | English + 中文 (Chinese); see `data/repository/LanguageManager.kt` + `res/values-zh/` |

## Build Commands

All native/VM components are coordinated by `build-all.sh` (Docker-cached):

```bash
./build-all.sh kernel      # custom kernel only (podroid_kernel.config)
./build-all.sh initramfs   # kernel + minimal initramfs
./build-all.sh rootfs      # Alpine squashfs -> app/src/main/assets/alpine-rootfs.squashfs
./build-all.sh qemu        # QEMU + native helpers via Docker (slow first time)
./build-all.sh termux      # terminal-emulator JNI for 16KB pages
./build-all.sh apk         # Android APK via Gradle
./build-all.sh all         # everything
./build-all.sh deploy      # all + install + launch
./build-all.sh test        # boot validation: installs, polls console.log for "Ready!"
```

**APK only:** `./gradlew assembleDebug` / `./gradlew installDebug`.

**Unit tests:** `./gradlew :app:testDebugUnitTest` (use the `:app:` form, not the bare task).

**Monitor VM boot:**
```bash
adb logcat -s PodroidQemu
adb shell run-as com.excp.podroid.debug cat files/console.log   # debug build
```

**Release builds** are signed via `signingConfigs.release` (keystore `podroid-release.jks`), fed by the `PODROID_RELEASE_STORE_FILE` / `_PASSWORD` / `_KEY_ALIAS` / `_KEY_PASSWORD` Gradle properties. Release `applicationId = com.excp.podroid`; debug gets `applicationIdSuffix = ".debug"` + `versionNameSuffix = "-debug"`. Any code comparing the local version against an upstream release tag must strip an optional `-debug` suffix (see `UpdateRepository.checkForUpdate`).

Native binaries require **16KB page alignment** (`-Wl,-z,max-page-size=16384`) - mandatory on Android 13+; verified by an ELF parser in `build-all.sh`.

## Architecture

### Engine abstraction (the most important thing to understand)

Everything VM-related goes through `engine/VmEngine.kt`, a single interface implemented by two backends and one router:

- **`QemuEngine.kt`** - QEMU/TCG. Software emulation, SLIRP user-mode networking, control via QMP and virtio-console Unix sockets. No special permission.
- **`engine/avf/AvfEngine.kt`** - AVF/pKVM. Uses the Android Virtualization Framework (reached by reflection in `AvfReflect.kt`); networking and control ride **vsock**. Hardware-accelerated; needs `MANAGE_VIRTUAL_MACHINE` + `USE_CUSTOM_VIRTUAL_MACHINE` granted via `pm grant`.
- **`EngineHolder.kt`** - the single Hilt binding for `VmEngine`. Picks the concrete engine at startup based on `EngineSelection` (Auto / AVF / QEMU), watches Settings for backend changes, and routes imperative calls + flow access to whichever is current. Also owns the live diff that applies `PortForwardRepository` changes to the running VM.

When you touch VM behavior, decide whether it is backend-neutral (put it behind `VmEngine` / `EngineHolder`) or backend-specific (inside `QemuEngine` or `AvfEngine`).

### Data flow

```
Terminal UI (Compose)
    | TerminalSession (vendored Termux JNI)
libpodroid-bridge.so  <->  terminal.sock + ctrl.sock  <->  VM  <->  hvc0 + hvc1
boot monitor          <->  serial.sock (QEMU) / console stream (AVF)  ->  console.log + boot-stage detector
QmpClient             <->  qmp.sock (QEMU only)        -> runtime port forwards (netdev_add/remove)
HostRequestServer     <->  host.sock/hvc2 (QEMU) | vsock:9101 (AVF)    <- podroid-notify / podroid-forward
NfcReaderManager      -> 64-event cursor ring -> HostRequestServer      <- sense nfc status/current/next
```

QEMU exposes these Unix sockets under `context.filesDir`, each with one role:

- **`terminal.sock`** ↔ virtio-console `/dev/hvc0` - primary terminal I/O. getty runs on hvc0; `libpodroid-bridge.so` relays it to a Termux PTY.
- **`ctrl.sock`** ↔ virtio-console `/dev/hvc1` - resize channel. The bridge debounces SIGWINCH bursts and writes one `RESIZE rows cols\n`; a guest resize daemon `stty`s hvc0.
- **`serial.sock`** ↔ PL011 `/dev/ttyAMA0` - boot-log sink only. The boot monitor streams kernel + init output into `console.log` and the boot-stage detector.
- **`qmp.sock`** - QEMU Machine Protocol for runtime port forwarding and USB hot-plug.
- **`host.sock`** ↔ virtio-console `/dev/hvc2` - the guest→Android host bridge (see below).

On **AVF** there is no QMP and no PL011: the console is captured via `ConsoleFanout.kt`, control/resize go over `VsockControlChannel.kt`, port forwards over `VsockPortForwarder.kt`, and the host bridge over vsock port 9101.

### Boot pipeline

1. The engine launches the VM and starts the boot monitor (`QemuBootMonitor.kt` for QEMU; the console stream for AVF). `BootStageDetector.kt` scans the rolling console buffer (last ~1KB, not per-`read()` chunk - fast devices split markers like `Ready!` across reads).
2. **`init-podroid`** (initramfs, ~45 lines): mounts the persistent ext4 (`/dev/vda` → upper) and the read-only squashfs (`/dev/vdb` → lower), stacks an overlayfs, moves the mounts into the new root, and `switch_root`s into `/sbin/init` (busybox).
3. Busybox `/sbin/init` reads `/etc/inittab` and starts **OpenRC** (runlevels are pre-symlinked at build time - chroot-into-aarch64 doesn't work on an x86_64 builder).
4. OpenRC services on the squashfs do all system bringup:
   - `podroid-bootstrap` - kernel modules, cgroup v2, devpts/shm/mqueue, sysctl, ZRAM swap, `mount --make-rshared /`, container dirs.
   - `podroid-network` - eth0 up, addressing, default route, `/etc/resolv.conf`.
   - `podroid-resize` - reads `RESIZE rows cols` from `/dev/hvc1`, `stty`s `/dev/hvc0`.
   - `podroid-hostd` - the host-bridge daemon (both backends).
   - `podroid-ratatoskr` - native Mimir runtime; writes Nomad config before starting the independent `nomad` OpenRC service.
   - `mim-maintenance` - boot-owned rollout worker; survives Nomad and Ratatoskr restarts and records upgrade receipts.
   - `podroid-vsock` - the AVF control/forward agent (AVF boots only).
   - `dropbear` - SSH, when enabled.
   - `podroid-ready` - emits `Starting SSH...` / `Almost ready...` / `Ready!`, the markers `BootStageDetector` matches; `Ready!` flips state to `Running` and auto-starts the terminal bridge.

**Why `switch_root`, not `chroot`:** an earlier version `chroot`ed into the overlay, which broke `podman exec -it` - `setns(MNT)` in `crun exec` resets `fs->root`, so the exec'd process saw raw kernel paths (`/mnt/overlay/proc`) instead of `/proc`. `switch_root` reorganizes the kernel mount tree itself, so namespace forks see a clean `/`.

### Guest → Android host bridge

Lets guest processes call back to Android. Guest side: `podroid-hostd` (multi-call C binary, `build-rootfs/host-bridge/podroid-hostd.c`) owns a guest-local `AF_UNIX` socket `/run/podroid-host.sock`; `argv[0]` dispatch also exposes it as `podroid-notify`, `podroid-forward`, and the `sense` capability CLI (symlinks). The daemon relays one request line / one response line to Android over a backend transport: `/dev/hvc2` on QEMU, `AF_VSOCK:9101` on AVF (chosen by the `podroid.backend=avf` cmdline marker). Android side: `engine/hostbridge/` - `HostRequestServer` reads requests over a `HostTransport` (`QemuHostTransport` via `LocalSocket` to `host.sock`, or `AvfHostTransport` via `connectVsock`), `HostRequestDispatcher` parses them, and routes to notifications, port forwards, camera/location/microphone shims, or NFC. `PodroidService` starts/stops the server over the VM lifecycle. Free-text and JSON fields are base64 (`HostProtocol.kt`) so UTF-8 survives.

NFC is a high-level Android shim, not hardware passthrough. `NfcReaderManager` enables `NfcAdapter` Reader Mode only while a VM session is active and `MainActivity` is resumed, re-arms it after the Android NFC switch turns back on, snapshots tag ID/technologies/cached NDEF, de-duplicates rapid rediscovery, and retains 64 immutable events per VM session. Guest services call `sense nfc status`, `current`, or non-blocking `next <cursor>`; cursors make the ring non-destructive so multiple consumers can act independently. `NfcBridgeResponse` hard-limits complete encoded responses and hostd rejects overlong lines instead of accepting truncated JSON. Never add a blocking NFC wait to the existing bridge: it is serialized and would stall every other host request. Phone NFC must be enabled and the Activity foreground/device unlocked; a foreground service or WakeLock cannot make Reader Mode work behind the lock screen. Tag IDs are not authentication credentials.

> **Gotcha:** `/dev/hvc2` is a virtio-console **TTY** that defaults to echo on. The daemon must `cfmakeraw()` it, or the TTY echoes Android's responses back and the protocol desyncs after the first request. AVF (a raw vsock socket) is unaffected.

### USB passthrough (`engine/usb/UsbPassthroughManager.kt`)

QEMU backend only. An unprivileged app can't open `/dev/bus/usb`, so it takes the already-open fd from `UsbManager`/`UsbDeviceConnection` and streams it to QEMU over `qmp.sock` as SCM_RIGHTS (`add-fd`), then hot-plugs with `device_add usb-host,hostdevice=/dev/fdset/N`. A code-registered `BroadcastReceiver` (no manifest `device_filter.xml`) is live only while the VM is `Running`. Gated by the `usb_passthrough_enabled` setting, which also makes `buildCommand()` emit `-device qemu-xhci`. Needs a libusb-enabled QEMU build.

### X11 viewer (`x11/` + `ui/screens/x11/`)

In-app viewer that talks RFB to `Xvnc` in the guest, with touch→mouse, soft-keyboard, external mouse/keyboard, fullscreen, rotation lock, resolution presets, and PCM audio over PulseAudio loopback. Backed by always-on implicit VNC/audio port forwards.

### Internationalization

`data/repository/LanguageManager.kt` + `res/values/` / `res/values-zh/`. `MainActivity.attachBaseContext` wraps the context for the saved locale (read synchronously from a cache file before Hilt is available); changing the language in Settings persists it and recreates the Activity. Don't reintroduce hardcoded user-facing strings - use `stringResource` and keep `values` / `values-zh` in sync.

### Navigation

Single-activity Compose app: `ui/navigation/NavGraph.kt` routes `setup → home → terminal / settings / x11`. `TerminalViewModel` is scoped outside the `NavHost` so the session survives screen changes. The setup wizard shows until setup is marked complete in DataStore.

## Project Structure

```
/
├── app/                              # Android module
│   └── src/main/
│       ├── java/com/excp/podroid/
│       │   ├── MainActivity.kt           # single Activity, locale wrap, WindowSizeClass
│       │   ├── PodroidApplication.kt     # Hilt app, asset extraction on first run
│       │   ├── engine/
│       │   │   ├── VmEngine.kt           # backend interface
│       │   │   ├── EngineHolder.kt       # Hilt binding; routes to current engine; port-forward diff
│       │   │   ├── EngineModule.kt, EngineSelection.kt
│       │   │   ├── QemuEngine.kt         # QEMU/TCG backend, buildCommand()
│       │   │   ├── QemuBootMonitor.kt, BootStageDetector.kt
│       │   │   ├── QmpClient.kt          # QMP: port forwards + USB add-fd
│       │   │   ├── ResizeNotifyingSession.kt, VmState.kt
│       │   │   ├── avf/                  # AVF/pKVM backend (engine, reflection, vsock, console)
│       │   │   ├── hostbridge/           # guest->Android bridge (transport, dispatcher, hardware shims)
│       │   │   │   └── nfc/              # foreground NFC reader + cursor event ring
│       │   │   └── usb/                  # UsbPassthroughManager
│       │   ├── service/PodroidService.kt # foreground service; owns VM lifecycle, wakelock, notification
│       │   ├── data/repository/          # Settings, PortForward, Update, Language (all DataStore)
│       │   ├── di/                       # Hilt module
│       │   ├── util/                     # NetworkUtils etc.
│       │   ├── x11/                      # X11/VNC viewer engine
│       │   └── ui/                       # Compose: navigation, theme, screens/{setup,home,terminal,settings,x11}, components
│       ├── res/values, res/values-zh/    # strings (EN + Chinese)
│       ├── assets/                       # vmlinuz-virt, initrd.img, alpine-rootfs.squashfs (all gitignored, built locally),
│       │                                 # qemu/, colors/ (122), fonts/ (13), ui-fonts/
│       └── jniLibs/arm64-v8a/            # native executables (see below)
├── terminal-view/, terminal-emulator/   # vendored Termux fork (local Gradle modules)
├── init-podroid                         # initramfs bootstrap: overlay + switch_root
├── podroid-bridge.c                     # PTY <-> virtio-console relay -> libpodroid-bridge.so
├── podroid-launcher.c                   # exec wrapper that ties QEMU's lifetime to the app -> libpodroid-launcher.so
├── Dockerfile                           # kernel + initramfs + QEMU build
├── build-rootfs/
│   ├── Dockerfile.rootfs, build-rootfs.sh
│   ├── vsock-agent/                     # podroid-vsock-agent.c (AVF control/forward agent)
│   ├── host-bridge/                     # podroid-hostd.c (guest->Android bridge daemon + CLIs)
│   └── files/etc/                       # OpenRC services + configs baked into the squashfs
├── build-all.sh, gradle.properties, build.gradle.kts, settings.gradle.kts
└── README.md, CONTRIBUTING.md, CREDITS.md
```

## Native Binaries (`app/src/main/jniLibs/arm64-v8a/`)

ELF executables renamed `.so` for APK packaging; run via `ProcessBuilder` / `TerminalSession`, not loaded as JNI libraries. All 16KB-aligned.

| File | What it is |
|---|---|
| `libqemu-system-aarch64.so` | QEMU TCG emulator |
| `libpodroid-bridge.so` | PTY ↔ virtio-console relay (from `podroid-bridge.c`) |
| `libpodroid-launcher.so` | exec wrapper for QEMU process lifetime (from `podroid-launcher.c`) |
| `libslirp.so` | SLIRP user-mode networking (soname patched `.so.0`→`.so`) |

The terminal emulator JNI is built from the vendored `terminal-emulator` module (rebuilt for 16KB pages), not shipped as a prebuilt here.

## QEMU command construction (`QemuEngine.buildCommand()`)

- `-serial unix:serial.sock` (boot log) + a `virtio-serial-pci` bus carrying three virtconsoles: `terminal.sock` (hvc0), `ctrl.sock` (hvc1), `host.sock` (hvc2, the host bridge - **order matters**, the guest expects host bridge on hvc2).
- `-qmp unix:qmp.sock` for runtime port forwards + USB.
- RAM, CPU count, and user-editable extras (`-cpu`, `-accel`, RNG, etc.) from `SettingsRepository`.
- Two virtio block devices: `/dev/vda` ← `storage.img` (writable ext4 overlay upper, resized on first boot), `/dev/vdb` ← `alpine-rootfs.squashfs` (read-only zstd lower).
- SLIRP networking; `-device qemu-xhci` when USB passthrough is enabled.

## Build pipelines

- **`Dockerfile`** - custom Linux kernel (arm64 defconfig + `podroid_kernel.config` modules + `forced_builtin.config` forcing overlayfs / netfilter / bridge / veth / tun / FUSE / IPv6 etc. to `=y`) and QEMU cross-compiled against the NDK. A build-time check greps the resolved `.config` and **fails the build** if any critical option isn't `=y` (guards against silent Kconfig demotion from unmet tristate deps). QEMU needs `--enable-libusb` (for passthrough) and a few Android/Bionic patches; `build-all.sh qemu` may need `docker build --network=host`.
- **`build-rootfs/Dockerfile.rootfs`** - fetches the Alpine minirootfs, runs `build-rootfs.sh` (apk-installs alpine-base + openrc + podman + crun + fuse-overlayfs + docker + lxc + dropbear + iptables/nftables + bridge-utils, sets root password `podroid`, seals file caps on `newuidmap`/`newgidmap`, copies the OpenRC services and the cross-compiled `podroid-vsock-agent` + `podroid-hostd`, wires runlevels via direct symlinks), then `mksquashfs -comp zstd` (kernel ships `CONFIG_SQUASHFS_ZSTD=y`).

## Performance tuning (TCG path; KVM is impossible without root)

In `QemuEngine.buildCommand()`: `tcg,thread=multi`, larger `tb-size` for ≥2GB RAM, dedicated `iothread` on `virtio-blk-pci`, `-cpu max,sve=off`. Guest cmdline: `mitigations=off` (safe inside TCG), per-device `mq-deadline`. `init-podroid`/bootstrap: ZRAM lz4 swap at half RAM. `CONFIG_EXT4_FS_SECURITY=y` + `CONFIG_SQUASHFS_XATTR=y` keep `security.capability` xattrs across the overlay (rootless podman's `newuidmap` needs them). What won't work without root: `io_uring` (seccomp), CPU affinity, KSM, TAP networking, host hugepages.

## Quirks & gotchas

- **Backend asymmetry is the #1 source of bugs.** QEMU = SLIRP + QMP + virtio-console (TTY); AVF = DHCP + vsock (raw sockets). Test both. The host bridge's `cfmakeraw` on hvc2 (above) is a concrete example.
- **NFC Reader Mode is foreground-only.** `MainActivity.onResume/onPause` owns it. Do not move it into `PodroidService`, assume the partial WakeLock keeps it alive, or expose arbitrary tag `transceive` through the read-only IoT event API.
- **Boot detection** scans a rolling buffer, not per-read chunks (fast devices split markers).
- **Bridge stderr is silenced** (`dup2(/dev/null, STDERR)`): it runs as a `TerminalSession` subprocess whose stderr IS the PTY. Never add `fprintf(stderr, ...)` to `podroid-bridge.c`.
- **`forceUpdateSizeFromView` must not multiply by scaledDensity** - `TerminalView` passes the raw int textSize to `Paint`; mismatched math renders TUI apps in the wrong grid.
- **SLIRP has no ICMP** (`ping` fails in the guest) and its DNS forwarder is unreliable on Android (resolv.conf uses public DNS).
- **Privileged ports**: the app can't bind host ports < 1024 (no `CAP_NET_BIND_SERVICE`), so SSH is on 9922, not 22. Forward to a high host port.
- **Reflection into the vendored Termux fork** (`mTermSession`, `mEmulator`, `mCurrentDecSetFlags`) is kept by `app/proguard-rules.pro`.
- **Persistence is sacred**: DataStore keys and `filesDir` paths survive every release; renames need migration. Updates must install in place (same `applicationId` + signing key, monotonic `versionCode`).

## VM migration / upgrades (how the guest updates without a reset)

The guest system layer updates across app versions with **no VM reset and no data loss**, on both backends. The machinery:

- **Plain overlay (never re-add metacopy/index/redirect).** `init-podroid` mounts the rootfs overlay as `lowerdir=/mnt/lower,upperdir=/mnt/persist/upper,workdir=/mnt/persist/work` only. Plain overlayfs tolerates a swapped lower, so a new squashfs (re-extracted by `PodroidApplication` on every update) goes live on the next boot while the persistent upper is preserved. **Do not re-add `metacopy=on`/`index=on`/`redirect_dir=on`** - they bind the upper to a specific lower and reintroduce the corruption-on-update bug (the whole reason resets used to be needed).
- **Version anchor.** The squashfs ships `/etc/podroid/system-version` (baked from `versionCode` by `build-all.sh` -> `Dockerfile.rootfs` ARG -> `build-rootfs.sh`). The last-applied version persists at `/mnt/persist/.podroid/applied-version`.
- **One-time legacy normalization.** `init-podroid` runs `podroid-overlay-normalize` (shipped in the squashfs, invoked via `/mnt/lower`, before the overlay is stacked) once per device (guarded by `/mnt/persist/.podroid/normalized`) to strip pre-existing `metacopy`/`redirect`/`index` state from uppers created by the old metacopy overlay. No-op on fresh/normalized uppers.
- **Imperative hooks.** `podroid-migrate` (OpenRC, runs `before podroid-bootstrap`) executes `/etc/podroid/migrations/<v>.sh` for `applied < v <= system-version` in order, then advances `applied-version` atomically. **To ship a fixup in a new release** (e.g. enable a newly-added service): add `build-rootfs/files/etc/podroid/migrations/<versionCode>.sh`, idempotent, install it in `build-rootfs.sh`. Pure file additions/changes need NO script - the overlay union surfaces them.
- **Reliability:** the marker advances only after migration completes (crash -> idempotent re-run); nothing auto-wipes `/mnt/persist`; `init-podroid` keeps its `FATAL -> exec sh` recovery shell.

## Common tasks

- **Kotlin/UI change** → `./gradlew assembleDebug && ./gradlew installDebug`.
- **OpenRC service / package list / guest CLI change** → edit under `build-rootfs/`, `./build-all.sh rootfs`, rebuild APK. New/changed system files go live on the **next VM boot** with no reset (the plain-overlay union surfaces them; see "VM migration / upgrades"). The exception is a path the user already modified - it lives in the persistent upper and keeps the user's version until removed, so use a `/etc/podroid/migrations/<v>.sh` script for that case.
- **`init-podroid` change** → `./build-all.sh initramfs`, rebuild APK.
- **New boot stage** → emit the marker in the right OpenRC service + match it in `BootStageDetector`.
- **New setting** → add a DataStore key + Flow in `SettingsRepository`, UI in `SettingsScreen`, setter in `SettingsViewModel`, and plumb into `VmConfig`/`buildCommand()` if it affects the VM.
- **New user-facing string** → add to `res/values` AND `res/values-zh`, use `stringResource`.
- **Verify before "done"**: compile, run `:app:testDebugUnitTest`, and for behavior changes install and walk the flow on a device (unit tests don't catch DI/graph or backend-specific issues).

## Greed native Mimir runtime (2026-09)

`./build-all.sh ratatoskr-native` builds current local Mimir arm64 Ratatoskr and
packages the imported Nomad-min release, mim-net and Bun/uv into the squashfs.
`podroid-ratatoskr` is an OpenRC supervised native service. Mimir's no-systemd
path supervises Nomad/mim-net itself; do not add a competing Nomad service.
Docker remains installed for user containers, but is not started at boot and is
not required for the node runtime. Docker is still used as a build tool.

`task vegvisir:update` consumes `~/.mim/vegvisir.json` (or `VEGVISIR_FILE`), not
the removed `mim tree` command. Verify the document against current Mimir state.
The native data bind remains `/mnt/persist/ratatoskr` -> `/var/lib/ratatoskr`.
Before upgrading a legacy container, disable its restart policy, stop it, and
copy `/opt/yggdrasil/config` to `/mnt/persist/ratatoskr/legacy-ygg-config` using
`docker cp`; migration 33 preserves that identity and the Nomad client directory.
Do not delete the old container or its volumes. Migration failures must not
advance the applied-version marker. Android installation must use `adb install -r`.

VM resource settings are respected; do not restore the old all-host-cores/all-RAM
override. Validate SSH, DNS/HTTPS, native service, Nomad node/driver readiness,
and a real workload separately; `Ready!` alone is not cluster acceptance.

For an initramfs-only update with an unchanged kernel, use
`./build-all.sh initramfs --fast`; it retains the existing matching modules and
checks their kernel version. Full kernel changes still require the normal build.
`build-rootfs/install-packages.sh` is cached separately from native payload and
service configuration changes. Bun/uv must come from Mimir's checksum-pinned
musl runtime-tools stage; a glibc uv archive fails on Alpine with `__res_init`.

Native startup must not depend on a live Ratatoskr DNS daemon: `podroid-network`
saves its resolver to `/run/podroid-resolv.conf`; the runner restores it before
respawning when `/etc/resolv.conf` still points at `::1`. Keep node names stable
in `/var/lib/ratatoskr/node-name`, derived from the preserved Nomad client ID.
`/opt/nomad/data` aliases the persistent Nomad directory for Mimir helpers.
Provision only the NATS internal client credentials separately at
`/var/lib/ratatoskr/nats-client/internal.creds` (0700 directory, 0600 file).
Never package credentials or authority/signing material in the APK. The runner
uses current Mimir's credential-only leaf mode without a system-account leaf.
The guest kernel must include Landlock in CONFIG_LSM, CHECKSUM, clsact/ingress,
BPF classifiers/actions and HTB shaping; driver fingerprints alone do not prove
that an allocation can attach eBPF or start the Membership sandbox.
Guest-only SLIRP/tethering addresses must not be selected as cross-node LAN paths;
Greed sets `bark.network_paths.disable_non_loopback_lan=true`. This does not
change Android's SSH port forwarding. Rootfs packaging uses zstd level 15 with
four workers to keep native builds responsive while preserving zstd support.
Maximum-resource mode is the default from version 38; manual mode retains saved
RAM/CPU values. More emulated cores are capacity, not a guarantee of faster TCG
execution; validate actual performance separately.

`PersistentStorage` is shared by QEMU/AVF: grow in place and refuse implicit
shrinks. Only a newly created disk receives `storage.needs-format`; its kernel
parameter authorizes first-boot formatting, and a genuine Ready marker clears
it. Existing disk repair failures enter recovery without invoking mkfs. Run
`tools/test-storage-init.py` and the PersistentStorage unit tests for changes.
A timeout must never promote Starting to Running. Both backends wait for the
actual guest Ready marker, retaining boot logs and progress during slow boots.

### Native networking selection

`podroid-network` selects a device-backed Ethernet interface through `/sys/class/net/*/device` and `type=1`. Never select the first non-bridge name: the built-in IFB driver creates `ifb0` before `eth0`, and assigning SLIRP addressing to IFB makes SSH and node bootstrap unreachable. Migration 36 refreshes previously shadowed network scripts.

QEMU ingress is owned by Android/SLIRP explicit forwards. The native runner defaults `bark.control_plane_firewall.enabled=false` only for QEMU unless explicitly configured; Mimir allocation/CNI isolation stays on. Applying the server public-interface filter to SLIRP eth0 blocks VNC/audio and arbitrary user forwards. AVF keeps the normal Mimir firewall default. Migration 37 refreshes shadowed runners.

### Mobile identity, resources and exit (version 39)

Default naming invokes `podroid-node-name`, built against Mimir's actual `pkg/nodename`, using verified trace loc+colo and persistent Nomad client ID. The generated region/alias is cached, explicit names are respected, and migration 38 backs up the legacy greed-ID name. `tools/build-node-name.sh` runs during rootfs builds.

Maximum-resource mode defaults on: all Android-reported processors and physical memory minus max(1536 MiB, one eighth) reserved for Android. `VmResourcePolicy` is shared by launch and labels; Settings can select manual limits. Disk sizing remains independent.

Back from the launcher finishes/removes the task; task removal and Stop keep the foreground service/wakelock alive until VM termination. QEMU requests ACPI poweroff (BusyBox acpid is enabled); AVF requests SHUTDOWN over its existing vsock control channel. OpenRC `stop_pre` runs `podroid-node-offline` before Yggdrasil teardown. It authenticates as this node with its local Nomad secret, suspends its final ready heartbeat, and sends the native Node.UpdateStatus down RPC over private Yggdrasil. No node record is purged; failed notification resumes the agent and leaves heartbeat expiry as fallback. Nomad leave flags alone do not mark a client down. QEMU has a bounded forced-stop fallback. Home/backgrounding and configuration changes are not exits; Android force-stop/process death cannot promise callback delivery and require Nomad heartbeat expiry.

Membership QLite must accept allocation-network access, not only loopback. Native env sets `bark.qlite.listen=0.0.0.0:14227`; migration 39 upgrades missing/legacy loopback settings while retaining other operator listeners. Runtime tenant capability checks still apply.
