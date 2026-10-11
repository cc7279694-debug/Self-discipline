# Mirra V1.1 — execution evidence

This directory contains only controlled synthetic Android pixels and sanitized results. It contains no installed database, backup archive, device serial, private Note, permission dump or full logcat.

## Before / after boundary

`screenshots/before/` was captured during this delivery from the retained `0.2.0` shipping/test APK, using the existing isolated Room/Compose fixtures. The Mine before image captures a loading frame, not a fully rendered nonempty chart; numerical chart assertions were separately executed. Preparation and creation depict the previous mandatory-action experience. These are not screenshots of the user's private installed history.

After images were captured after the new APK executed. `mine.png`, `trends*.png`, `create-book.png`, `preparation.png`, `mine-protection-entry.png` and `learning-protection-*.png` are controlled synthetic/in-memory Room/Compose rendering. The Mine after frame visibly contains the real fixture's 95-minute/three-of-four chart; its numerical/unknown qualifications are separately asserted. These are not private installed history, OEM or TalkBack proof.

`installed-*.png` are actual retained-AVD shipping-UI captures: selected-action preparation, FULL+READY, permission loss/PARTIAL, denied/NONE, synthetic Note/normal result and the actual launcher. The learning/Note captures contain only this run's synthetic data. The installed checks did not use fake monitoring capability or a hand-edited Segment. The baseline was restored afterwards. The saved result does not provide an effective value for incomplete monitoring.

`mirra-brand-contact-sheet.png` is actual Android resource rasterization across masks/sizes/tints; it is not a screenshot of every launcher. The monochrome-theme installed captures reflect the controlled fixture's preference, not a redesign of Mirra Blue.

## Final executed gate

One final unfiltered combined invocation: JVM **664/664 PASS**; Android **495 discovered / 486 actual PASS / 9 opt-in assumptions / 0 actual failures/errors/unfinished**; lint **0 errors / 18 existing warnings / 1 existing hint**; both APK builds passed. All three DND and five intervention platform methods entered their bodies and passed. Nine ordinary opt-in assumptions are not PASS. The baseline fixture's separate prepare/restore results are not added to the full count.

Actual installed checks covered explicit action/continuation/edit/abandon/reuse, FULL→PARTIAL, NONE repair, Note/save, offline three main pages/Data Management and real SAF cancellation. They are not a new manual saved-content export/full SAF round-trip claim. See the [single checkpoint](../../checkpoints/2026-10-11-v1-1-core-experience.md) for RED/environment history and precise boundaries.

## Original artwork

The directly user-confirmed original is preserved in [the source provenance record](../../brand/APPROVED_ORIGINAL.md). Foreground RGB is not redrawn; monochrome/notification alpha is derived from that same source. Android mask/tint previews are controlled resource renderings, not claims about every launcher.

## Data and permission protection

The retained API37 AVD was not wiped, cleared or uninstalled. Same-run original baseline restoration through the formal service passed, comparing all 12 authoritative tables, JPEG bytes and four preference values/presence; its private archive was removed. Four temporary permissions and network/font state were restored/read back. No active learning/PENDING/FGS/overlay/notification/current owned DND rule or unresolved Journal remained. The AVD disk was retained on normal closure. Raw IDs, canonical digests and device logs remain local/private or ignored and are not committed.

The pre-grant DND legacy setting was null; it did not itself prove actual policy access. Denied baseline had retained prior evidence, while final denied state was freshly verified in the current service's allowed-provider section. This limitation remains explicit rather than inventing pre-grant proof.

Final APK: `build/deliveries/v1-1/Mirra-1.1.0-core-experience-debug.apk`, **17,210,307 bytes**, version **1.1.0 / code 3**, SHA-256 **6FA9873CFFB160A6FF4855D0CA10994C9BC0E16D2D3CD7A9CA0085D209BFD8A1**. Room v4/schema 1–4/migrations unchanged.

## Unchanged limits

API23–36, full physical/OEM compatibility, TalkBack, release/Play, Android cloud/D2D transports, real power loss and manual clock changes remain NOT RUN. Existing historical failures remain in the checkpoint; they are not erased by later green results.
