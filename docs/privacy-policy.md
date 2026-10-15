# Privacy Policy — Router Signal

_Last updated: 15 October 2026_

Router Signal is an app for viewing and changing settings on your own 4G router,
and for using its SIM to read and send text messages and dial USSD codes.

## What is collected

Nothing. The app has no analytics, no crash reporting and no telemetry. No
account is required and none can be created. No personal data is collected,
transmitted, sold or shared with anyone, including the developer.

## What is stored on your device

- The address of your router.
- The username for your router, if you changed it from the default.
- The password for your router.
- How often you would like readings refreshed.
- Any USSD codes you save, with the names you give them.

Text messages are read from the router each time you open Messages and are not
kept by the app. Messages you send, and USSD codes you dial, go to the router,
which passes them to your mobile network as any phone would.

The password is encrypted with an AES-GCM key generated in the Android Keystore,
so what is written to storage is meaningless off your device. These settings are
excluded from Android backups for the same reason. All of it is deleted when you
uninstall the app.

## Network access

The app connects to the router at the address you enter, over your local
network. It cannot be pointed at a public address — addresses outside the
private ranges are rejected — and it contacts no server belonging to the
developer.

The one exception is the speed test, which runs only when you press Run. It
downloads test data from and uploads test data to Cloudflare's public speed
test service (speed.cloudflare.com), because measuring a connection means
sending traffic over it. The test data is meaningless filler. Cloudflare sees
your connection's public IP address, as any website you visit would; its own
privacy policy covers what it does with that. Nothing about you or your router
is sent.

Demo mode makes no network connections at all; its readings, messages and USSD
replies are generated inside the app, and the speed test is switched off.

## Permissions

`INTERNET` is the only permission the app declares. It is used to reach your
router on your local network, and for the speed test when you run one. The app
does not use your phone's own SMS or phone permissions: messages and USSD go
through the router's SIM.

## Children

The app is a network utility with no user-generated content and no social
features. It is not directed at children.

## Contact

ismailaabubakar112@gmail.com

## Affiliation

Router Signal is an independent app. It is not affiliated with, endorsed by, or
supported by ZTE, MTN, or any carrier or manufacturer. It works with hardware it
was reverse-engineered against; it may stop working after a firmware update.
