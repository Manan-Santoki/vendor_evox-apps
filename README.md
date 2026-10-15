# vendor_evox-apps

Bundled apps for my unofficial Evolution X builds (Xiaomi Pad 6, `pipa`):
Morphe-patched YouTube, YouTube Music and Reddit, plus MicroG-RE, and
**Bundled apps**, a small system app that installs and updates them.

The APKs are built and approved through
[evox-apps](https://github.com/Manan-Santoki/evox-apps)
([dashboard](https://evox.msantoki.com)).

## How it works on the device

- **Bundled apps** lives in `/system_ext/priv-app`. The ROM carries the approved APKs and a copy of the index in
  `/product/etc/evox-apps/`.
- On first boot **Bundled apps** installs them as normal apps (no network
  needed), so users can uninstall any of them like any other app.
- Every 6 hours it reads the published `index.json` and silently installs newer
  approved builds. Each APK is checked against its SHA-256 and the pinned
  signing certificates before installing.
- An app the user uninstalled is not reinstalled. It can be installed again
  from **Settings → System → Bundled apps**, which also has a switch for
  automatic updates and a **Check for updates** button.
- A package that was installed from somewhere else (for example the official
  Reddit from the Play Store) is left alone.

## Ad blocking

`adblock/` builds in [AdAway](https://github.com/Manan-Santoki/AdAway/tree/evox)
with a **system** blocking mode that needs neither root nor a VPN:

- AdAway writes its blocklist to `/data/misc/adaway/blocklist`.
- `adaway.rc` copies it into `/data/misc/adaway/hosts` at boot and whenever
  AdAway applies new lists, and bind mounts that file over
  `/system/etc/hosts`, which Android's DNS resolver checks before every lookup.
- The SELinux rules in `adblock/sepolicy` only let platform-signed apps write
  it; AdAway is re-signed with the platform key at build time.

It blocks out of the box: on first start AdAway picks the system mode,
downloads its default lists and keeps them updated daily. It opens from its
app icon, its Quick Settings tile, or **Settings → Network & internet → Ad
blocking**. A real VPN keeps working alongside it.

The device's `BoardConfig.mk` must include `vendor/evox-apps/BoardConfig.mk`
for the SELinux rules.

## Using it in a build

1. Add the repo to your local manifest at `vendor/evox-apps`.
2. Inherit it from the device tree:

   ```make
   $(call inherit-product-if-exists, vendor/evox-apps/config.mk)
   ```

3. Before building, download the approved APKs:

   ```sh
   python3 vendor/evox-apps/scripts/fetch-apks.py
   ```

   They are not in git (YouTube alone is over GitHub's 100 MB file limit). If
   you skip this step the ROM still builds, and the updater downloads the apps
   on first boot instead.

## License

Apache-2.0
