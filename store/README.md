# Play Store assets

Everything Google Play needs for the listing, kept in the repo so it is
reproducible rather than living in someone's downloads folder.

| File | Play requirement |
|---|---|
| `icon-512.png` | 512×512, 32-bit PNG, no alpha, under 1 MB |
| `feature-graphic-1024x500.png` | 1024×500, PNG or JPEG, no alpha |
| `screenshots/` | ≥2 phone shots, 320–3840px per side, longest side ≤2× shortest |
| `listing.md` | Store title, descriptions, and the App access / Data safety answers |

## Regenerating the graphics

Both are rendered from the SVG sources beside them, which reuse the launcher
icon's geometry and palette (`#0E1B2A` background, the four bar colours from
`app/src/main/res/drawable/ic_launcher_foreground.xml`) so the store art and the
installed icon cannot drift apart.

```sh
rsvg-convert -w 512 -h 512 icon.svg -o icon-512.png
rsvg-convert -w 1024 -h 500 feature.svg -o feature-graphic-1024x500.png

# Play rejects a store icon with an alpha channel, so flatten both.
magick icon-512.png -background "#0E1B2A" -alpha remove -alpha off PNG24:icon-512.png
magick feature-graphic-1024x500.png -background "#0E1B2A" -alpha remove -alpha off PNG24:feature-graphic-1024x500.png
```

The feature graphic keeps all text inside the middle 924×400, because Play crops
the edges on some surfaces.

## Screenshots

Taken from the app running in demo mode, so they show the real UI without
needing the router. The demo banner is visible in them deliberately — the
listing must not imply sample readings are live ones.

## Why the screenshots are 1200×2400

Captured at the emulator's native 1080×2400, which is 20:9 — outside Play's
stated phone bounds (longest side no more than twice the shortest). They are
padded to an exact 2:1 on the app's own background rather than cropped, so no
content is lost and the padding is invisible.

```sh
magick shot.png -background "#F7F7FC" -gravity center -extent 1200x2400 shot.png
```
