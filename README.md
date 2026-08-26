# OpenWrt Manager (Android)

Native Android admin console for OpenWrt routers. Talks to the router over
**ubus-over-HTTP JSON-RPC** (the same bus LuCI itself uses, via
`uhttpd-mod-ubus`) for everything ubus can do, and an **SSH exec/SFTP**
transport for the things it can't (package management, service control,
config backup/restore) — no HTML scraping, no WebView, no LuCI dependency.

## Status

Bottom navigation once a router is selected: **Dashboard / Devices /
Firewall / System / More**. Everything below works end-to-end against the
ubus/UCI/SSH surface as documented; anything not verified against a live
router is flagged explicitly, either here or in a code comment at the call
site.

**Connect**
- Add, edit, or delete a router profile; address field prefilled from the
  phone's current gateway.
- Password in `EncryptedSharedPreferences` (Android Keystore AES-256), never
  plaintext.
- `session.login` auth, with silent re-login on session expiry — a cached
  client is reused for the app's lifetime.
- Capability-gated UI via ubus `list` — a wired-only router just doesn't get
  a Wi-Fi card, no error, no dead UI.
- SSH connections are host-key pinned on first use (TOFU): the fingerprint is
  stored per profile and any later mismatch is a hard failure surfaced to the
  user, not a silent re-pin.

**Dashboard**
- Real `system.info`/`system.board`/`network.interface.dump`.
- Wi-Fi radios from `network.wireless status` enriched with `iwinfo.info`
  (live signal/bitrate).
- Reboot, per-interface up/down, per-radio enable/disable — each behind a
  confirmation dialog naming the actual consequence.

**Devices**
- DHCP leases (`/tmp/dhcp.leases` via ubus `file.read`) merged with
  `iwinfo.assoclist` Wi-Fi associations by MAC, grouped into Wireless/Wired
  sections with a plain-language signal-quality label.

**Firewall** (Port Forwards / Traffic Rules / Zones tabs)
- Full CRUD on `config redirect` (port forwards), `config rule` (traffic
  rules), and `config zone` (zones) via the `uci` ubus object — the actual
  UCI representation OpenWrt has for all three, there's no separate API.
- A partial save/add failure cleans up its orphan UCI section instead of
  leaving one behind.
- "Apply changes" reloads the live firewall over SSH
  (`/etc/init.d/firewall reload`) — edits persist to UCI immediately but
  don't take effect until this (or a reboot) runs, which the in-app banner
  states plainly.
- VLAN devices (`config device`, 802.1q/ad) are listed read-only under
  Zones — full VLAN authoring is a larger, separate effort.

**System**
- Services: real start/stop/restart via `/etc/init.d/<name> <verb>` over
  SSH, each behind a confirmation dialog.
- System log via ubus `log read` (procd's syslog ring buffer), monospace,
  severity-colored using theme-aware colors (not fixed hex).
- Packages via `apk` over SSH (OpenWrt replaced `opkg` with Alpine's `apk`
  as of the 24.10 release line — this targets `apk` only). Install/remove/
  `apk update`, each confirmed first, output shown after, never silent.
  ⚠️ The `apk list --installed`/`--upgradable` output parser
  (`parseApkNameVersion` etc. in `UbusHttpClient.kt`) matches Alpine
  apk-tools' documented format and is unit-tested against it, but **not
  verified against a live 24.10+/apk-migrated router** — check it against
  real output before relying on it.

**More**
- **Network Diagnostics**: ping and DNS lookup run *from the phone itself*
  (traceroute attempted if the device happens to ship a binary for it, with
  a clear fallback message since most Android builds don't).
- **DNS Management**: static dnsmasq hostname → IP records (`config domain`
  sections in the `dhcp` UCI config).
- **Backup & Restore**: `sysupgrade -b`/`-r` over SSH+SFTP, with the user
  picking the file location via the system file picker (Storage Access
  Framework) — no app-private storage to manage.
- **UCI Raw Editor**: generic get/set/add/delete on any UCI config section,
  for anything the purpose-built screens don't cover. Clearly framed as
  advanced/dangerous — no schema validation, no undo beyond re-editing.

## Not built yet

- **SSH terminal**: a persistent interactive shell + emulator is a
  meaningfully bigger piece of work than the single-shot `SshExecClient`
  calls everything above uses; intentionally out of scope for now.
- **Firmware flashing**: `sysupgrade` image flashing can brick a router; it
  deserves a dedicated pass with real safeguards (verify-before-flash,
  explicit multi-step confirmation), not a same-shape add-on to Backup.
- **Full VLAN authoring**: only read-only listing exists today (see above).
- Accessibility pass, tablet/foldable layouts, offline mock backend for
  dev, full instrumented UI test suite.
- mDNS router discovery (stock OpenWrt doesn't run avahi, so it'd rarely
  find anything — gateway auto-detect covers the same case for near-zero
  cost).

## Requirements on the router

- OpenWrt with LuCI installed (brings `uhttpd-mod-ubus` by default), or
  `uhttpd-mod-ubus` installed standalone.
- A user with full `ubus` ACL rights (the default `root` login has these) —
  specifically `session`, `system`, `network.interface`, `network.wireless`,
  `iwinfo`, `file` (read `/tmp/dhcp.leases`), `uci`, `service`, and `log`.
- dnsmasq as the DHCP server for the Devices screen to show leases (an
  odhcpd-only setup will just show Wi-Fi-associated clients, no IP/hostname).
- dropbear or openssh-server reachable on the profile's SSH port (default 22,
  same username/password as the ubus login) for Packages, service control,
  and Backup/Restore.
- `apk` as the package manager (OpenWrt 24.10+). An `opkg`-only router will
  have the Packages screen fail every call — not handled/detected yet.

## Architecture

```
UI (Compose) → ViewModel → RouterRepository → OpenWrtClient (interface)
                                                    └── UbusHttpClient (ubus-over-HTTP)
                                                          └── SshExecClient (SSH exec + SFTP)
```

`OpenWrtClient` is the only thing the UI/domain layer knows about. Right now
`UbusHttpClient` is the sole implementation, and it reaches for SSH
internally only for the operations ubus has no object for at all — package
management, service start/stop/restart, firewall reload, and backup/restore.
`RouterCapabilities` comes from ubus `list` (the objects actually visible to
the session) — features get hidden per router, not per app version.

No Hilt: the DI graph is three objects (`AppDatabase`, `CredentialStore`,
`RouterRepository`), wired by hand in `OpenWrtManagerApp.onCreate`.

**Known gaps, stated rather than hidden:**
- A `saveTrafficRule` update that clears a previously-set `dest` zone
  (switching a rule to target "this router") doesn't unset the old UCI
  option — `uci set` only adds/updates given keys, it doesn't remove ones
  left out. Low-severity; delete-and-recreate works around it.
- No live-router verification this pass (the apk parser, UCI section
  option formatting for list-type values, and the backup/restore SFTP path
  are all written to documented OpenWrt/sshj behavior, not confirmed against
  real hardware).

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
