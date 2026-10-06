# Mirra Brand Refresh v1 — Inner Window

Approved brand concept: **静心之窗 / Inner Window**. “打开一扇门，进入专注，也进入自己。”

This is production adaptation of the user-approved reference, not a new concept or a screenshot used as an icon. The quiet arch, open door plane, interior depth and threshold light are retained. Photographic grain and subtle material shading are deliberately removed for small-size clarity.

## Assets and geometry

- `inner-window.svg` is the transparent, resolution-independent vector master; no embedded raster, font or network dependency.
- Runtime color artwork: `app/src/main/res/drawable/ic_mirra_foreground.xml`, matching the SVG paths and palette exactly.
- Adaptive layer: 108 × 108dp. The mark spans x=34–74, y=25–80 and stays within the central 66dp-diameter circle. Background is warm ivory `#F6F3EC`; charcoal `#343532`, quiet gray-beige planes and a narrow light path create the depth.
- API23–25: scalable layer-list launcher fallback. API26+: separate background and transparent foreground. API33+: separate single-ink monochrome arch/open-door glyph for system tinting.
- `drawable/ic_launcher.xml` keeps the existing notification smallIcon resource name but now contains only the transparent white arch/open-door silhouette. The two frozen notification callers do not change. Opposite contour winding preserves the aperture without requiring API24 `fillType` support.
- Splash: static system starting window, warm ivory plus centered vector only. API31+ uses framework SplashScreen attributes; legacy uses a layer-list. No animation, branding tagline, welcome Activity or extra dependency.

## Brand vs UI

Mirra Blue, color/shape/depth tokens, buttons, progress, navigation, fonts and page structure are unchanged. Existing Start typography “观已 Mirra” stays in place; there was no old logo image in Start or Mine to replace, so neither receives a new graphic. Generic profile/navigation person glyphs are not logos.

No green eye remains in production resources. Historical screenshots/checkpoints retain their original evidence and are not rewritten.

## Verification protocol

Build / lint; actual Android resource rendering; 32 / 64 / 128 / 256 / 512 / 1024px; circle / rounded-square / squircle / teardrop mask previews; pure monochrome and neutral light/dark tint previews; notification transparency; static Splash theme resolution. Mask previews are engineering render evidence, not OEM launcher compatibility claims.

On the dedicated API37 AVD only: compare installed DB/WAL/preferences/JPEG/preservation marker hashes immediately before and after `install -r`, then cold-start and open Start / Knowledge / Mine. No clear/uninstall/wipe, permissions, DND, monitoring or real-device operation. Restore the original system night mode and return to Start.

Actual outcomes and unrun environments are recorded in the brand checkpoint. API23–36, OEM, physical-device and release/TalkBack checks remain NOT RUN unless actually exercised. Phase4 is not entered.

Platform references: [Android adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive), [Android Splash screens](https://developer.android.com/develop/ui/views/launch/splash-screen).
