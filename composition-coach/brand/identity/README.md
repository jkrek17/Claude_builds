# SceneRight identity assets

Start with `brand-board.png` for the overall direction and `logo-primary.svg` for the master lockup.

## Files

| Asset | Use |
| --- | --- |
| `logo-primary.svg` | Ink lettering, Apricot catchlight, transparent background |
| `logo-reverse.svg` | Paper lettering, Apricot catchlight, transparent background; use on Ink |
| `logo-mono.svg`, `logo-white.svg` | Single-color lockups with a transparent catchlight cutout |
| `wordmark-*.svg` | Wordmark without the mark or descriptor |
| `mark-*.svg` | Standalone ring and catchlight eye |
| `app-icon.png` | 512 × 512 px RGBA square store icon; Apricot field, charcoal mark |
| `adaptive-foreground.svg` | Transparent 108 × 108 design layer |
| `adaptive-background.svg` | Full-bleed Apricot background layer |
| `adaptive-monochrome.svg` | Single-ink design layer with a catchlight cutout |
| `play-feature-graphic.png` | 1024 × 500 px opaque RGB graphic |
| `tokens.json` | Color, typography, spacing, radius, and motion values |
| `previews/launcher-masks.png` | Preview of icon under three launcher masks |
| `android/res/` | Android vector, adaptive, and legacy icon resources |

All SVG logo lettering is outlined. The SVG sources remain editable. PNGs are raster convenience exports.

## Android handoff

Merge `android/res/` into your Android module's `src/main/res/`, resolving any existing resource names deliberately. Add the attributes below to the existing application element; do not replace the app manifest:

```xml
android:icon="@mipmap/ic_launcher"
android:roundIcon="@mipmap/ic_launcher"
```

API 26+ uses the foreground and background adaptive layers. API 33+ adds the monochrome layer for themed launchers. Earlier devices use the provided legacy raster density resources. Use a compile SDK that recognizes the API 33 monochrome element. Rename resources and their references together if integrating into an existing icon system.

The adaptive design canvas is 108 dp. Essential art fits within a centered 53.28 dp circle, inside Android's 66 dp safe circle. Do not bake a launcher mask into the adaptive layers. Review the actual installed result under supported launcher shapes and themed icon settings when app code is available. No Android build has been run for this asset-only package.

## Export

From the project root:

```bash
python brand/source/build_identity.py
```

This recreates identity graphics and local site asset copies. It uses installed DejaVu fonts and Inkscape, with Python's Pillow and fontTools packages. Bundled font licensing is in `brand/site/assets/FONT-LICENSE.txt`.

The color foreground is charcoal with a transparent catchlight cutout. The Android `colors.xml` also includes the approved proposed camera surface and action colors. Existing production ready and warning tokens remain authoritative.
