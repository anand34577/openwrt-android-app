# OpenWrt Manager (Android)

Native Android admin console for OpenWrt routers. Talks to the router over
**ubus-over-HTTP JSON-RPC** (the same bus LuCI itself uses, via
`uhttpd-mod-ubus`) for everything ubus can do, and a minimal **SSH exec**
transport for the handful of things it can't (package management, later
backup/firmware) — no HTML scraping, no WebView, no LuCI dependency.

## Status: Phases 1–6 done + UI/UX polish pass (of 8)

**Polish pass:** every list screen (Dashboard, Devices, Port Forwarding,
Services, Logs, Packages, the router list) now shares one set of
loading/error/empty presentations instead of five near-identical copies —
see `ui/components/StateComposables.kt` and `Indicators.kt`. Concretely:
- **Skeleton loading** (pulsing placeholder cards) replaces bare spinners
  (section 56) everywhere except the router-connect step, which is a single
  network round trip where "Connecting…" reads better than a fake list.
- **Crossfade** between loading/error/loaded states, and `AnimatedVisibility`
  for banners/snackbars, so screens don't just snap between states.
- **`StatusDot`**, one color-independent dot+label component (section 33),
  used for every Up/Down, Running/Stopped, Active/Disabled status in the app
  instead of each screen inventing its own.
- Router list: shows "Last connected" per profile, a proper empty state with
  an action button, and the HTTPS toggle now flips the default port (80↔443)
  along with it.

Bottom navigation (Dashboard / Devices / Port Forwarding / System) once a
router is selected. What works end-to-end against a real router:

**Connect (Phase 1–2)**
- Add a router profile, address field prefilled from the phone's current
  gateway (section 7)
- Password in `EncryptedSharedPreferences` (Android Keystore AES-256), never plaintext
- `session.login` auth, with **silent re-login on session expiry** — a cached
  client is reused for the app's lifetime (section 36), so without this any
  router idle >~5 minutes would fail every call until app restart
- Capability-gated UI via ubus `list` (section 43) — a wired-only router
  just doesn't get a Wi-Fi card, no error, no dead UI

**Dashboard (Phase 3)**
- Real `system.info`/`system.board`/`network.interface.dump`
- Wi-Fi radios from `network.wireless status` (uci device names + admin
  enabled/disabled state) enriched with `iwinfo.info` (live signal/bitrate)
- Reboot, per-interface up/down, per-radio enable/disable — each behind a
  confirmation dialog naming the actual consequence (sections 11/12/26/33)

**Devices (Phase 4)**
- DHCP leases parsed from `/tmp/dhcp.leases` (via ubus `file.read`, the same
  source LuCI's own leases page uses) merged with `iwinfo.assoclist`
  Wi-Fi associations by MAC — wired vs. wireless, signal strength, IP, hostname

**Port Forwarding (Phase 5, partial)**
- Full CRUD on `config redirect` sections via the `uci` ubus object
  (`get_all`/`add`/`set`/`delete`/`commit`) — the actual representation
  OpenWrt has for port forwards, there's no separate API for it
- Honest limitation, stated in-app: saved rules persist to
  `/etc/config/firewall` but **aren't pushed to the live ruleset** yet — see
  Phase 7 below

Firewall zones/traffic rules, routing, VLAN — the rest of Phase 5 — aren't built.

**System (Phase 6)**
- Services list, read-only, via ubus `service list` (name, running/stopped,
  pid, instance count) — real start/stop still needs shell (Phase 7)
- System log via ubus `log read` (procd's syslog ring buffer), monospace,
  color-coded by severity
- **Packages, via `apk` over SSH** — OpenWrt replaced `opkg` with Alpine's
  `apk` as of the 24.10 release line, so this targets `apk` only. Install/
  remove/`apk update`, each confirmed first, output shown after, never
  silent (section 20). ⚠️ The `apk list --installed`/`--upgradable` output
  parser (`parseApkNameVersion` etc. in `UbusHttpClient.kt`) is written from
  Alpine apk-tools' documented format, **not verified against a live
  24.10+/apk-migrated router** — check it against real output before relying
  on it, and file the fix in the same parsing functions if it's off.
- Backup/restore and firmware flashing still not built — see below.

## Not built yet (rest of Phase 6, 7–8)

- **Backup/restore, firmware**: both are `sysupgrade` operations — now
  technically reachable via the same SSH exec transport packages use, but
  I'm intentionally not rushing them in. Firmware flashing can brick the
  router (section 25/33); it deserves a dedicated pass with real safeguards
  (verify-before-flash, explicit multi-step confirmation), not a same-shape
  add-on to the packages screen.
- **7 — SSH *terminal*, UCI raw editor, diagnostics (ping/traceroute/DNS
  lookup), audit log**: `SshExecClient` (new this pass) runs one command and
  returns; a terminal is a persistent interactive shell + emulator, a
  different and bigger piece of work. Diagnostics need to run *from the
  phone* (ping/traceroute aren't ubus calls), a different code path again.
  This phase also unlocks real service start/stop/restart.
- **8 — Polish**: animations, accessibility pass, tablet/foldable layouts,
  mock backend for offline dev, full instrumented UI test suite.

Also intentionally skipped: mDNS router discovery (stock OpenWrt doesn't run
avahi, so it'd rarely find anything — gateway auto-detect covers the same
case for near-zero cost) and DNS management (not yet touched at all).

## Requirements on the router

- OpenWrt with LuCI installed (brings `uhttpd-mod-ubus` by default), or
  `uhttpd-mod-ubus` installed standalone.
- A user with full `ubus` ACL rights (the default `root` login has these) —
  specifically `session`, `system`, `network.interface`, `network.wireless`,
  `iwinfo`, `file` (read `/tmp/dhcp.leases`), `uci`, `service`, and `log`.
- dnsmasq as the DHCP server for the Devices screen to show leases (an
  odhcpd-only setup will just show Wi-Fi-associated clients, no IP/hostname).
- dropbear or openssh-server reachable on the profile's SSH port (default 22,
  same username/password as the ubus login) for the Packages screen.
- `apk` as the package manager (OpenWrt 24.10+). An `opkg`-only router will
  have the Packages screen fail every call — not handled/detected yet.

## Architecture

```
UI (Compose) → ViewModel → RouterRepository → OpenWrtClient (interface)
                                                    └── UbusHttpClient (ubus-over-HTTP)
                                                          └── SshExecClient (SSH, packages only)
```

`OpenWrtClient` is the only thing the UI/domain layer knows about. Right now
`UbusHttpClient` is the sole implementation, and it reaches for SSH
internally only for the operations ubus has no object for at all — OpenWrt
genuinely doesn't expose package management, backup, or firmware over ubus,
so a single implementation composing two transports is less code than a
second full `OpenWrtClient` implementation for three methods. `RouterCapabilities`
comes from ubus `list` (the objects actually visible to the session) —
features get hidden per router, not per app version.

No Hilt: the DI graph is three objects (`AppDatabase`, `CredentialStore`,
`RouterRepository`), wired by hand in `OpenWrtManagerApp.onCreate`.

**Known gaps, stated rather than hidden:**
- SSH host key verification is off (`PromiscuousVerifier`) — same
  LAN-convenience tradeoff as cleartext HTTP, but a bigger prize for a MITM.
  TOFU key pinning before this ships for real (see `SshExecClient.kt`).
- No Room migration yet — schema changes destructively wipe local data
  (pre-release, no real users; see `AppDatabase.kt`).
- A `savePortForward` that fails between `uci add` and `uci set` can leave
  an empty orphan `redirect` section — low-severity, not handled.

## Building

Gradle wrapper jar isn't checked in (binary, can't be authored here). Either:
1. Open the project folder in Android Studio — it regenerates the wrapper automatically, or
2. Run `gradle wrapper` once with a local Gradle 8.7+ install.

Then:
```bash
./gradlew assembleDebug
./gradlew test        # UbusParsingTest + ClientMergeTest
```

Release builds (`minifyEnabled true`) have R8 keep rules for sshj's
reflection-based crypto provider lookup in `proguard-rules.pro` — not yet
run through an actual `assembleRelease` to confirm they're sufficient.
