# ZLT Companion

An Android companion app for the **ZLT T10D MAX** 4G router. Shows signal
strength, connection state and the current network mode, and lets you switch
between network modes. Includes a Quick Settings tile for signal at a glance.

It is a port of the login and `reqproc` protocol handling from the
[`zltrouter`](https://github.com/exbyte-dev/zltrouter) Python project, which is
where the protocol was reverse-engineered and live-verified. That project's
`docs/protocol.md` is the reference for anything below.

## What it does

- One screen: signal strength as bars and a plain word, the dBm figure and the
  rest of the readings secondary
- Switch network mode between Automatic, 4G only, 4G and 3G, 3G only, 2G only
- A Quick Settings tile showing signal strength; tapping it opens the app
- Router address and password entered in the app and kept on the device

That is the whole of v1. No SMS, no USSD, no speed test — the Python project
does all of those.

## Network behaviour

The app talks to your router and to nothing else. There is no analytics, no
crash reporting and no telemetry, and the dependency list is AndroidX, Kotlin,
OkHttp and kotlinx-serialization — nothing that reports usage anywhere.
`INTERNET` is the only permission it declares.

The router speaks plain HTTP on the LAN, so cleartext has to be allowed. It is
allowed narrowly, in two layers:

- `network_security_config.xml` denies cleartext by default and re-permits it
  only for an enumerated list of private gateway addresses. Android's network
  security config matches hostnames and has no notion of a CIDR range, so
  "RFC1918 only" cannot be written directly — the addresses have to be listed.
  The list is produced by `tools/gen_network_security_config.py`, so what it
  covers is reviewable and reproducible rather than hand-maintained.
- `HostValidation` rejects anything outside RFC1918 and the CGNAT range at the
  point you type it, so the app cannot be aimed at a public address by hand.

If your router sits on an unusual address that the generated list misses, the
app will say so specifically rather than failing generically. Add the address
to `tools/gen_network_security_config.py`, re-run it, and rebuild.

Your router password is sealed with an AES-GCM key generated in the Android
Keystore, so what sits in DataStore is ciphertext that means nothing off the
device. Backups are excluded for the same reason.

## Building

Needs JDK 17+ and an Android SDK with platform 35.

```sh
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # unit tests
./gradlew assembleRelease      # release APK
```

`local.properties` needs `sdk.dir` pointing at your SDK, as usual.

### Release signing

Release builds are signed from a gitignored `keystore.properties`. Without it
the release build still succeeds, unsigned, so a fresh clone builds for anyone.

```sh
keytool -genkeypair -v \
  -keystore ~/zlt-companion-release.jks \
  -alias zlt-companion \
  -keyalg RSA -keysize 4096 -validity 10000

cp keystore.properties.example keystore.properties
# then fill in the paths and passwords
```

**Back the keystore up somewhere off this machine.** Losing it means never being
able to update the app on Play again.

## How it is put together

```
net/    The protocol port. ZltAuth (login encoding), ZltClient (proc_get,
        proc_post, session, lockout guard), ZltProtocol (every device-specific
        constant), HostValidation.
data/   SettingsStore and the Keystore cipher, RouterRepository, the signal
        quality thresholds, StatusCache for the tile.
ui/     Compose screens and the ViewModel.
tile/   The Quick Settings tile.
```

Anything device-specific lives in `net/ZltProtocol.kt`, mirroring how the Python
keeps its constants in one block: different firmware should need changes there
and nowhere else.

### Notes worth knowing before changing the protocol code

- **The password encoding is not what it looks like.** It is
  `Base64(ascii(lowercase_sha256_hex(nonce + password)))` — the digest becomes a
  64-character hex *string* first, and that string is what gets Base64-encoded.
  Encoding the raw digest bytes gives a plausible 44-character value that the
  router rejects. `ZltAuthTest` pins this against vectors generated from the
  Python.
- **The router locks out after five wrong passwords.** The client reads
  `psw_fail_num_str` (which counts attempts *remaining*, not failures) before
  every login and refuses to try below two. Do not remove this guard, and do not
  debug encoding problems against live hardware.
- **`net_select` is the key that holds the configured mode** on this firmware,
  not `net_select_mode` or `m_netselect_save`, both of which come back empty.
  The fallback order exists for other builds.
- **3G is `TD_W`, not `Only_WCDMA`.**
- **LOGIN answers `"0"` or `"4"`; every other write answers `"success"`.**
- **The status keys and the mode keys go out as one `proc_get`.** They are all
  plain scalar keys and `multi_data` joins as many as are asked for, so there is
  no reason to make two round trips — and making two meant the mode went stale.
  If firmware ever refuses a list that long, `snapshot()` notices the mode keys
  missing from the response and re-reads them separately.
- **With `network_type: No Service` the device still echoes a stale `rssi` and
  `signalbar`.** Those are ignored for the headline reading and shown only under
  Details.

## Testing

63 unit tests, all on the JVM — no device or emulator needed.

```sh
./gradlew testDebugUnitTest
```

`RouterMock` mirrors `tests/router_mock.py` from the Python project, and as
there it spells out the cmd names rather than importing the constants, so a
rename breaks a test rather than moving both sides at once.

## Known limits

- **The Quick Settings tile is not live.** Android only calls a tile's
  `onStartListening` while the shade is open, so keeping one updating on a timer
  would need a foreground service. The tile paints its last cached reading
  immediately and refreshes in the background, so it is current within about a
  second of opening the shade.
- **`ppp_status` is interpreted for three captured values.** `ppp_connected`,
  `ppp_connecting` and `ppp_disconnected` are named;
  anything else is shown verbatim, because there is no capture of the other
  states to work from.
- **IPv4 addresses only**, no hostnames and no port. The router is reached by
  address on the LAN.
