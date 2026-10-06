# Brand Refresh v1 — evidence index

2026-10-06. Dedicated API37 AVD only; no physical-device evidence or serial. Parent: `096af8e5e943b8efbca8aa47b10ab2b7d2f53e18`.

- `icon-masks-and-sizes.png`: actual Android resource rasterization. Four controlled mask previews, 32/64/128/256/512/1024px, single-ink day/night tint previews and notification transparency. Large renderings are clearly labelled thumbnails. Not an OEM mask/compatibility claim.
- `splash-api37.png`: actual 2000ms frame from a cold-start recording, warm ivory and static Inner Window. No generated image, forced delay or animation.
- `launcher-light-api37.png`, `launcher-dark-api37.png`: actual AVD launcher app drawer with updated icon, under system night `no` / `yes`. Real wallpaper-themed icon switching was not exercised.
- `start-api37.png`, `knowledge-api37.png`, `mine-api37.png`: actual app pages using only the inherited controlled preservation fixture. No brand UI redesign or new logo placement.

Final targeted instrumentation: BrandResourcesTest, 7 executed / 7 passed / 0 failed / 0 error / 0 skipped, runner `OK (7 tests)`, 15.850s. No assumptions or opt-in business fixtures. All mask/single-color/static-theme assertions execute against installed Android resources.

Build/lint, cover-install byte-preservation hashes, exact APK checksum, test-export failure history and NOT RUN boundaries: [checkpoint](../../checkpoints/2026-10-06-mirra-brand-refresh-v1.md).

No raw logcat, full dumpsys, device serial, credentials, private notes, video or APK is committed. Screenshots are evidence, not runtime icon assets. Original system night `no` was restored; permissions and networking were not changed. API37 results are not extrapolated to API23–36, OEM, physical device or release/Play.
