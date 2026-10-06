# Mirra Brand Refresh v1 — Inner Window

Date: 2026-10-06. Branch: `codex/mirra-brand-refresh-v1`.

Exact parent: Phase 3D Formal Freeze `096af8e5e943b8efbca8aa47b10ab2b7d2f53e18`. This independent task does not reopen Phase3 or enter Phase4. Implementation commit SHA is reported at delivery; no Phase3 history is squashed.

## Goal / scope

Produce the approved “静心之窗 / Inner Window” identity for Android Launcher / Adaptive / Monochrome / static Splash and replace existing old-logo references. Preserve the arch, open door plane, quiet interior depth and narrow threshold light; simplify material texture for small icons. Brand palette is separate from frozen Mirra Blue.

No Kotlin production logic, database, Migration, permissions, dependency, navigation, UI layout, typography or business changes. No new welcome page, logo placement, animation or artificial launch delay.

## Audit and resources

The baseline has one green-eye drawable, `drawable/ic_launcher.xml`, used by Manifest icon/roundIcon and two notification smallIcon callers. There are no mipmap/adaptive/monochrome assets or explicit branded Splash configuration. Start uses an existing text wordmark; Mine has no logo image. Generic person/navigation glyphs are not logos.

Production changes:

- `app/src/main/AndroidManifest.xml`: select mipmap icon/roundIcon and MainActivity starting theme only. All other declarations unchanged.
- `app/src/main/res/drawable/ic_mirra_foreground.xml`: five solid-color vector paths, transparent foreground, 108dp layer. Artwork x=34–74 / y=25–80 fits the central 66dp-diameter circle; maximum outline radius approximately 32.802dp.
- `app/src/main/res/drawable/ic_mirra_monochrome.xml`: single white ink, transparent arch aperture and abstract open door, no gradient/shadow.
- `app/src/main/res/drawable/ic_launcher.xml`: retain frozen notification resource name, replace green eye with transparent white brand silhouette. Notification callers untouched.
- `app/src/main/res/mipmap-anydpi/ic_launcher.xml`: API23–25 scalable layer-list fallback.
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`: separate warm-ivory background/transparent foreground, monochrome declaration ignored by pre33 platforms.
- `app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml`: explicit API33+ themed-icon layer.
- `app/src/main/res/values/brand_colors.xml`: brand-only palette.
- `app/src/main/res/values/themes.xml`, `values-v31/themes.xml`, `drawable/mirra_starting_window.xml`: static framework Splash on API31+, legacy centered layer-list; original Compose/Material mapping unchanged.
- `docs/brand/inner-window.svg`, `docs/brand/README.md`: matching scalable master and resource usage contract. No raster icon or design-board screenshot in runtime resources.

Verification-only addition: `app/src/androidTest/java/com/guanyi/mirra/BrandResourcesTest.kt`. Evidence export uses isolated cache directories only, no DB, preference, repository or system-setting writes. Optional actual video frames use an open file descriptor and canonical path guards.

## Actual verification

Only the dedicated API37 AVD was operated. Device identity/API37/qemu/boot complete were read before installation; no physical device or OnePlus 13T commands. Device serial is not retained here or in Git.

| Check | Actual result |
| --- | --- |
| Clean assembleDebug + assembleDebugAndroidTest + lintDebug | PASS; clean run 5m34s |
| Final test APK build and lint after evidence-export changes | PASS; last run 1m58s |
| Lint | 0 errors / 9 pre-existing warnings / 1 hint; no added monochrome warning |
| BrandResourcesTest — single final run | 7 executed / 7 passed / 0 failed / 0 error / 0 skipped; runner `OK (7 tests)`, 15.850s |
| Actual Manifest adaptive/monochrome resource resolution | PASS |
| Foreground alpha safe zone / transparent edges | PASS at 32/64/128/256/512/1024px |
| Circle / rounded square / squircle / teardrop | PASS controlled mask previews, exact foreground alpha preserved |
| Single-ink monochrome + neutral day/night tint | PASS; controlled previews, alpha unchanged, contrast >=4.5:1 |
| Notification silhouette transparency | PASS; no opaque tile |
| Static Splash / actual cold start | PASS; real video frame shows warm ivory + centered Inner Window, not a mockup |
| Actual launcher on system light/dark backgrounds | PASS; artwork visible in API37 app drawer |
| Start / Knowledge / Mine | PASS; existing fixture content remains, Mirra Blue and navigation unchanged |
| Cover install | PASS; five preserved-file hashes identical immediately before/after, before first launch |
| System night mode | Original `no`, temporary `yes`, restored and read back `no` |
| Production Kotlin / JVM tests / dependencies / permissions | No changes |

Mask and tint previews are actual Android Drawable rasterization but not four OEM launcher tests. Actual monochrome resource resolution is tested; real wallpaper-themed launcher switching is NOT RUN. User-data screenshots show only the pre-existing controlled preservation fixture, not private notes. Launcher captures include other standard AVD icons; their colors are not Mirra brand assets.

## Cover-install preservation

No clear/uninstall/wipe, fresh AVD, seed/reseed, database edit or preference injection. `install -r` succeeds with the existing signature. Before opening the updated app, all five exact SHA-256 values match:

| Preserved file category | Before = after SHA-256 |
| --- | --- |
| Room database | `5FB29CAE11B2EF901D99A3E27D2F5CD65029BAF522E833C445F136DACD6B9AD7` |
| Room WAL | `669DF3BED29382E93453187C9B703AC34D48CA6C73B0AB3A8D4DF1170854904C` |
| DataStore preferences | `88B7954CF1D3BCEE266A4225E98746CFAA236236C0B16F391F2ADEB8237FE61C` |
| Existing JPEG | `DF30C3E577A2AC4EF7D299EE08C4C78E0F5E6A28016E6C595C7C206920793FCC` |
| Existing preservation marker | `891C7150E54754851162569090C5F81AA36771C7454FED3A48036153746416CD` |

This proves installation byte preservation, not byte immutability after legitimate app startup/navigation. Navigation checks return to Start. No Session is created by this task.

## Database freeze

Room remains v4. No Entity / Table / Column / Index / Migration changes. All four schema files match the exact parent:

- 1.json: `4528DCEDF74A1875D7A132F2E41F00049A252F82070B8DB1C25CBE5C46ACD1B1`
- 2.json: `C831ED5C8B3A0AA5C56E43859AB0AF4EA1343E4AB4AF76224EE86B70F3E79E0D`
- 3.json: `CCB563F899DD68ECBFBE3369A7939F6D202CD1393508C26E0B33EE6A91CE1205`
- 4.json: `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`

## Retained build/test history

- Initial assemble/lint succeeded; the subsequent incremental resource merge failed with `no data file for changedFile`, naming the edited notification drawable. A scoped `:app:clean` removed only generated app build outputs; clean rebuild passed. No user data, accepted deliverable APK, dependency or business fix involved.
- Early lint reported missing monochrome on v26 adaptive XML even though v33 had it. Added the same monochrome declaration to v26, without suppressing the warning. Final warning count returned to the unchanged baseline.
- First brand run: 6 passed / 1 evidence-export error because instrumentation test-package external directory was unavailable. Export moved to an isolated target cache with canonical path checks; icon/mask assertions were not removed or relaxed.
- Second run: 6 passed / 1 evidence-video access error. Media service could not resolve the shell-created app-scoped path. Video input moved to isolated internal cache and uses an open descriptor.
- Two further runs: 6 passed / 1 duration assertion each. Inspection showed an incomplete binary transfer; an earlier recording also had only 5.608s of encoded timeline. No icon assertion failed. New real capture has a 13.3758s encoded timeline, transferred with a native copy and exact local/device SHA match `42199E3E62AF42BDE83B2FD6D1CA674C18905AD7D6D7A0E3EB08A8FD24C7A6D4`. The >=8s prerequisite was retained, not weakened.
- Final single brand run: all 7 tests PASS, including actual-video extraction. Earlier results are not added together as a clean full-suite PASS.
- An initial UI hierarchy read preceded Compose readiness and returned null; later real hierarchy/screenshots confirmed Start/Knowledge/Mine. No production startup change or artificial delay added.

## Evidence and delivery

[Evidence index](../evidence/brand-refresh-v1/README.md). PNGs are verification evidence only; production assets are vectors. Actual Splash frame is extracted from a real AVD recording, not generated artwork.

APK: `build/deliverables/Mirra-BrandRefresh-v1-debug.apk` (ignored, not committed), 15,993,492 bytes; SHA-256 `36E1B051AA705E8FD25C64143FC8186E360A4CF900C511CBE1026CACA2347F53`, version 0.1.0/code1. Exact production APK was installed with `-r` and used for the startup/navigation checks.

Full JVM / full connected / full Phase3 business matrix: NOT RUN, deliberately outside this visual-only task's required verification. API23–36 real executions, OEM/physical compatibility, TalkBack and release/Play remain NOT RUN. No permissions, DND/global Policy, monitoring, network settings, clock or real-device changes; no Session/Intent/Note created.

Commit is local only, as no Push authority was included in this request. No merge/release. Delivery awaits user visual acceptance; do not label the new brand independently accepted or begin Phase4.
