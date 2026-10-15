# Router Signal

An Android companion app for the **ZLT T10D MAX** 4G router. Shows signal
strength, connection state and the current network mode, and lets you switch
between network modes. Reads and sends SMS on the router's SIM, dials USSD
codes, and runs a speed test. Includes a Quick Settings tile for signal at a
glance.

> **Disclaimer:** This is an independent, reverse-engineered client. It is **not
> affiliated with, endorsed by, or supported by ZTE, MTN, or any carrier**. The
> API it talks to is undocumented and can break on a firmware update with no
> notice.

It is a port of the login and `reqproc` protocol handling from the
[`zltrouter`](https://github.com/exbyte-dev/zltrouter) Python project, which is
where the protocol was reverse-engineered and live-verified. That project's
`docs/protocol.md` is the reference for anything below.

## What it does

Four tabs, mirroring the Python project's web dashboard:

- **Signal:** strength as bars and a plain word, the dBm figure and the rest of
  the readings secondary. Switch network mode between Automatic, 4G only, 4G and 3G, 3G only, 2G only.
- **Messages:** the router's SMS inbox, with unread marked and counted on the
  tab. Compose (with a part count, since special characters cut a part from
  160 characters to 70), reply, and a Select mode to mark read or delete in
  one go. Deleting asks first.
- **USSD:** send a code, read the reply, answer menus, end the session. Saved
  codes are one-tap buttons; Manage adds and removes them. They are stored in
  the same `[{"label", "code"}]` shape as zltrouter's `ussd.json`.
- **Speed:** ping, download and upload, run from the phone, against
  Cloudflare's public speed test. Time-boxed both ways, as the dashboard's is.

The signal readout stays in the top bar on every tab, and the poll keeps
running whichever tab is open.

Also:

- A Quick Settings tile showing signal strength; tapping it opens the app
- Router address and password entered in the app and kept on the device
- A demo mode, so the app can be seen working without a router at all. It
  covers messages and USSD menus too.

What the Python project still has that this does not: the dashboard's walk-test
graph, the raw `get` / `post` passthrough, and running itself as a service.

## Network behaviour

The app talks to your router, and to Cloudflare's speed test server only while
a speed test runs. There is no analytics, no crash reporting and no telemetry,
and the dependency list is AndroidX, Kotlin, OkHttp and kotlinx-serialization —
nothing that reports usage anywhere. `INTERNET` is the only permission it
declares. SMS and USSD go through the router's SIM, so the app needs none of
Android's SMS or phone permissions.

The speed test is the one exception to "router only", and it is narrow: it runs
when you press Run and at no other time, over HTTPS, exchanging filler bytes
with `speed.cloudflare.com`. That is the dashboard's default target and the
reason is the same: a speed test has to measure *something* on the internet.
The cleartext rules below do not loosen for it.

Demo mode makes no network connections at all, and switches the speed test off. `DemoTransport` is an OkHttp
interceptor that answers the `reqproc` calls in-process, plugged into the
`baseClient` parameter `ZltClient` already takes — so the login handshake, the
parsing, the repository and the whole UI run exactly as they do against real
hardware, which is the point. It exists so the app can be evaluated, and
reviewed for the Play Store, without a ZLT T10D MAX on the desk.

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

Needs JDK 17+ and an Android SDK with platform 36.

```sh
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # unit tests
./gradlew assembleRelease      # release APK
./gradlew bundleRelease        # release AAB, which is what Play takes
```

`local.properties` needs `sdk.dir` pointing at your SDK, as usual. The SDK needs
`platforms;android-36` and `build-tools;36.0.0`; the emulator and a system image
are only needed for capturing store screenshots.

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
able to ship an update that existing installs will accept.

### Verifying a downloaded APK

Releases are signed with the key below. Android refuses to install an update
signed by a different key, so this fingerprint is also what proves a download
came from here:

```
SHA-256  9a:ff:2a:6f:b0:f9:6a:a1:7b:30:a3:1e:a6:21:80:a9:fd:6b:32:e7:60:ec:c0:ef:44:55:17:57:0e:8b:cd:a8
```

Check a download against it before installing:

```sh
apksigner verify --print-certs app-release.apk
```

### If this ever goes to Google Play

Play App Signing normally has Google generate its own app signing key. Do **not**
accept that here. Anyone who sideloaded a release from GitHub would then be
unable to update to the Play build — the signatures would not match, and Android
would make them uninstall first and lose their settings.

Enrol by uploading `zlt-companion-release.jks` as the app signing key instead, so
both channels ship the same signature. That choice is made once, at enrolment,
and cannot be changed afterwards.

## How it is put together

```
net/    The protocol port. ZltAuth (login encoding), ZltClient (proc_get,
        proc_post, session, lockout guard), ZltSms and ZltUssd (extensions
        over ZltClient), DeviceText (UCS2 / GSM7 / msg_id encodings),
        ZltProtocol (every device-specific constant), HostValidation,
        DemoTransport (the canned router).
data/   SettingsStore and the Keystore cipher, RouterRepository, the signal
        quality thresholds, StatusCache for the tile, saved USSD codes,
        AppContainer (the one shared repository).
speed/  The speed test. Not router protocol, so kept out of net/.
ui/     Compose screens and their ViewModels, one per tab.
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
- **Every screen and the tile share one `RouterRepository`** (`AppContainer`),
  so one `ZltClient` and one session cookie. Separate clients would each log in
  on their own, against a device that counts logins.
- **SMS and USSD hold the client's mutex per request, not per exchange.** A
  send that waits thirty seconds on the network does not freeze the signal
  poll. Their polls count waits rather than reading a clock, so the bounds are
  the Python's and the tests run under virtual time.
- **`*` goes out as `%2A`.** `URLEncoder` leaves it bare; Python's
  `quote_plus`, which is what was verified against the device, does not. Every
  USSD code starts with one.
- **The SMS inbox is the one nested response.** `get` flattens; `getObject`
  does not.
- **The unread count comes from the rows**, never `sms_unread_num`, which was
  seen reporting 0 with unread rows in the inbox.

## Testing

163 unit tests, all on the JVM — no device or emulator needed. The SMS and
USSD tests port `test_client_sms.py` and `test_client_ussd.py` case for case,
using the same captured vectors.

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
- **SMS and USSD are ported, not yet verified from the app on hardware.** The
  wire format is the Python's, which is live-verified, and the tests pin it to
  the Python's own vectors, but the first real send from a phone is still a
  first. The outgoing-message tags (2, 3, 4) are the stock UI's folder mapping
  and were never exercised on the Python side either.
- **The speed test measures the phone, not the router.** Join the router's
  Wi-Fi first; on mobile data it measures the phone's own connection.
