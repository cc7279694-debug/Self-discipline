# Mirra approved original artwork

On 2026-10-11 the user explicitly confirmed the recovered 1254 x 1254 PNG as the original approved Logo to restore. The image contains no text to remove. This original supersedes the later flattened `inner-window.svg` as the production artwork source; the existing SVG and prior evidence remain historical records.

## Source

- Preserved source bytes: `mirra-approved-original.png`.
- SHA-256: `71F4B8277F1F1A54215AAB9EADB9CC113EB14C2541176B93295EDF861A4759BE`.
- Recovered clipboard attachment identifier: `10561272-77dd-4144-93c5-29ee00bab2ac`.
- Source background sample at (200, 200): RGB `#FDFDF8`.
- The original charcoal arch, opened door, texture, interior shading and complete bottom light are preserved. Mirra Blue is a separate UI palette.

## Deterministic adaptation

`tools/brand/generate-approved-icons.ps1` uses Windows System.Drawing with no package dependency or image generation. Its input fingerprint is locked to the confirmed source. The default invocation writes only the two masters and the manifest here; it does not write Android runtime resources. Runtime output requires the explicit `-RuntimeOutputDirectory` argument.

The exterior-only mask follows the source's dark row edges, with a one-pixel fringe. Below the widest arch, the original straight sides extend to the source's complete bottom edge, retaining the bright light path. Every nontransparent RGB pixel in the unscaled foreground master is checked against its exact source pixel, both before and after PNG encoding. The bright inner door remains opaque in the color master; a color-key or brightness flood-fill must not erase it.

The monochrome master is derived from the same retained source pixels, with an exclusive mean-RGB threshold of 200 and white ink. The original light door and threshold provide its negative space. No new arch or door geometry is drawn.

Generator v2 additionally normalizes the **resampled monochrome/notification RGB only** to white, retaining each alpha unchanged. Actual Android v1 resource tests exposed GDI+ bicubic straight-RGB artifacts at translucent edges (three single-ink assertion failures); host PNG inspection reproduced them. The corrected ten density PNGs contain zero visible non-white RGB pixels. The color foreground and its master are not subjected to this normalization. Android GREEN must still be recorded separately; the assertions were not relaxed.

Generator v3 names the two single-ink density sources separately from their native BitmapDrawable XML wrappers. Actual Android diagnosis after v2 found zero second-ink opaque pixels in both raw and density-decoded bitmaps, but Canvas filtering introduced second-ink rounding (for example four notification pixels at 64px). The same raw artwork rendered with filtering disabled had zero at 64/128/256px. The wrappers disable this second filtering pass; the source already has antialiased alpha. Color foreground rendering is unchanged. The temporary diagnostic class is removed from the deliverable. The original eight platform resource assertions remain intact and all eight actually passed in the final resource/new-page invocation (15 actual PASS / 38.589s).

Runtime artwork uses a common aspect-preserving dp scale across five densities. The generator measures the actual filtered alpha footprint of every output and requires a radius at most 31.0dp around (54, 54) on the 108dp adaptive layer. Notification layers use the same source monochrome at 24dp. Padding is transparent; the adaptive background is separate. Scaling interpolation is expected in density outputs; exact original RGB conservation applies to the unscaled master, not interpolated pixels.

The generated manifest contains source/master/output hashes, thresholds, crop, source anchors and measured geometry, without private absolute paths or timestamps. PNGs are encoded twice and compared in each run. Repeating the entire generator must also produce identical file hashes.

## Verification boundary

Actual generator-only verification on 2026-10-11: the original fingerprint and background sample matched; all 171,271 retained master RGB pixels matched the source before and after PNG encoding; the bottom-light and monochrome-negative-space source anchors passed. The actual filtered alpha radius across all five densities was at most 29.381648dp for adaptive artwork and 9.976535dp for notification artwork. Two complete successful generator invocations produced identical hashes for both masters and the manifest; per-output double PNG encoding also matched. Runtime resources were not written by these invocations.

An initial generator invocation stopped at an unsupported System.Drawing RectangleF/attributes overload after creating the masters. It wrote no Android resources. The renderer now uses the supported PointF-array overload, and the two complete successful runs above include density rendering and hashing.

Source conservation and deterministic generation do not establish Android rendering or device acceptance. After runtime integration, the existing BrandResourcesTest, icon mask contact sheet, static Splash and actual launcher/startup checks must be rerun. Historical vector results must not be reported as checks of these restored raster assets. No business data, Room schema, Migration, preferences, permissions or Mirra Blue changes are part of this adaptation.
