# Play Store release for ZLT Companion v1

**Date:** 2026-07-29
**Status:** approved

## Goal

Get the existing v1 app — signal, connection state, network mode switching, Quick
Settings tile — onto the Google Play Store. Porting the remaining `zltrouter`
features (SMS, USSD, walk test, speed test) is explicitly *out of scope* and
happens after this ships.

## Constraints

- Google Play developer account exists, registered as an individual. If it was
  created after 13 Nov 2023, production access is gated behind 12 testers opted
  into a closed test for 14 continuous days.
- The app talks to a specific 4G router. Nobody reviewing it will have one.
- The hardware is someone else's brand (ZTE/MTN ZLT T10D MAX). The app is an
  independent reverse-engineered client, as the `zltrouter` README already states.
- The developer controls `exbyte.dev`.
- Play requires `targetSdk` 36 for new submissions from 31 Aug 2026.

## Decisions

### 1. Identity

| | now | after |
|---|---|---|
| `applicationId` | `dev.zlt.companion` | `dev.exbyte.zltcompanion` |
| `namespace` | `dev.zlt.companion` | unchanged |
| store title | ZLT Companion | Router Signal — for ZLT T10D |
| `app_name` | ZLT Companion | Router Signal |

`applicationId` is permanent once published and must live on a domain the
developer controls. `namespace` is compile-time only and never leaves the binary,
so it stays as-is rather than churning every import for no external benefit.

The store title names the model descriptively instead of leading with the bare
brand, which is what Play's impersonation policy cares about. The listing carries
the same disclaimer the Python project uses: not affiliated with, endorsed by, or
supported by ZTE, MTN, or any carrier.

The debug build keeps its `.debug` suffix, so both can be installed side by side.

### 2. Demo mode

**Why:** a reviewer with no router currently sees a setup screen, types a guess,
gets a connection failure, and has no way to reach any other screen. That is the
textbook trigger for a minimum-functionality rejection.

**Where it plugs in:** `ZltClient` already takes
`baseClient: OkHttpClient = OkHttpClient()`. Demo mode passes a client carrying a
canned-response `Interceptor`. Nothing above that parameter changes — the login
handshake, the flat-JSON parsing, `RouterRepository`, `RouterViewModel`, the
screens and the tile all run exactly as they do against real hardware.

Two alternatives were rejected: faking at the `clientFactory` seam requires
extracting an interface from `ZltClient` purely for the demo and skips the
protocol layer, so the demo would drift from reality; a `demo` product flavour
keeps the code out of the release binary, which is the only binary a reviewer
ever installs.

**Shape:**

- `net/DemoTransport.kt` — an `Interceptor` answering the `proc_get` and
  `proc_post` shapes from an in-memory snapshot. Fixture values are lifted from
  what `RouterMock` already uses in the test suite.
- The snapshot starts at good signal, connected, 4G only. A mode switch mutates
  it, so the switcher visibly does something.
- Entered from the setup screen ("Try it without a router"). A flag in
  `SettingsStore` persists it.
- The status screen shows a persistent banner while demo mode is on, so demo data
  is never mistaken for a real reading.
- Demo readings never touch `StatusCache`, so the Quick Settings tile does not
  start reporting fictional signal on the real device.

### 3. Build and release configuration

- `compileSdk` and `targetSdk` to 36. `minSdk` stays 26.
- Release artifact is an AAB (`bundleRelease`), not an APK.
- `versionCode` 1, `versionName` "1.0" for the first submission. Subsequent
  uploads increment `versionCode` by 1 every time — Play rejects a reupload of a
  used code — and `versionName` follows semver independently of it.
- **Verify the minified release build actually runs.** `isMinifyEnabled` and
  `isShrinkResources` have been true since the first commit but the app has never
  been through R8. `kotlinx-serialization` is used reflectively as a parser and is
  a plausible casualty. This is a real risk, not a formality.

### 4. Store assets

Generated to match the app rather than sourced separately:

- 512×512 icon, from `ic_launcher_foreground` on the existing theme background.
- 1024×500 feature graphic, same visual language.
- Phone screenshots (minimum 2, aiming for 4) captured from an emulator running
  demo mode: status screen, details expanded, mode switcher, setup.

### 5. Play Console paperwork

- **Privacy policy** hosted at a public URL on `exbyte.dev`. Required even though
  the app collects nothing. States: no collection, no analytics, no crash
  reporting, no telemetry; router address and password stay on the device;
  password sealed with an Android Keystore AES-GCM key; the only network traffic
  is to the user's own router on the LAN.
- **Data safety form** declaring no data collected and no data shared.
- **App access** declaration explaining the hardware dependency and pointing the
  reviewer at demo mode.
- **Content rating** questionnaire — a utility with no user content, so this is
  straightforward.

## Environment

The system SDK at `/opt/android-sdk` is root-owned, not writable, has only
`android-35`, and has no emulator package. `sudo` requires a password that a
non-interactive session cannot supply.

A user-local SDK at `~/Android/Sdk` is therefore installed without elevation and
`local.properties` points at it. It needs `platform-tools`, `platforms;android-36`,
`build-tools;36.0.0`, `emulator`, and an x86_64 system image. `/dev/kvm` is
present and world-writable, so the emulator runs hardware-accelerated.

`local.properties` is already gitignored.

## Testing

- The existing 63 JVM unit tests must stay green throughout.
- `DemoTransport` gets its own tests: it should satisfy the same
  `RouterRepository.snapshot()` contract the real client does, so demo mode
  breaking is a test failure rather than a review rejection.
- The minified release build must be installed and manually exercised on a device
  or emulator. Passing unit tests says nothing about R8 stripping a reflective
  parser.

## Out of scope

SMS, USSD, walk test, speed test, a live Quick Settings tile, IPv6, hostnames, and
non-default ports. All of it waits until v1 is live.
