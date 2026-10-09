# V1 trends / First Action — evidence index

This is execution evidence for branch `codex/v1-trends-first-action`, based on `b005ed1d85afd8f7abef15d6be65d39898f637fc`. It is not an acceptance/freeze, release or OEM compatibility declaration. See the [single checkpoint](../../checkpoints/2026-10-09-v1-trends-first-action.md).

Implementation + tests: `b1035becfdeca6ac9b99928375e1e032f779ab79`; this validation document commit is separate from the production SHA.

## Evidence boundary

- New source and test assertions protect real-ended-date reading totals, frozen trend rules, unknown versus zero, explicit action admission, legacy continuation and startup races.
- Full JVM on final source: **637 actual PASS / 0 failure / error / skipped**. Lint: **0 errors / 18 warnings / 1 hint**. Debug and test APK builds passed. The final single complete Android run and exact original baseline restoration passed separately below.
- Local raw logs/XML, preserved original baseline archive/digests and device identifiers stay in ignored local storage. No private database, actual backup package, full logcat or identifier is committed here.
- API37 Android screenshots use controlled synthetic data / in-memory Room and shipping Compose UI. They are actual pixels, not a design mockup, private history or proof of physical-device/OEM compatibility.
- Full connected assumptions are not business PASS. Specialized test counts, if executed, are separate from the unfiltered run, not added to inflate its total.
- Existing APIs 23–36, physical/OEM/TalkBack/release/system-transport/power-loss/manual-clock limits remain NOT RUN. Logo correction remains independent.

## Actual final results

Final fifth fresh unfiltered Android gate: **470 discovered / 456 actual PASS / 14 unmet assumptions / 0 actual failures / 0 errors / 0 native skipped / 0 unfinished**, **17m34s**, Gradle exit 0. XML was checked directly; not 470/470 PASS. The assumptions are nine explicit opt-in fixture methods and five permission prerequisites (three DND, two granted channels). The installed baseline fixture separately ran prepare and restore_baseline, **one actual PASS each**, not extra full-suite passes. The remaining eight opt-in methods and all five prerequisite-blocked platform methods remain NOT RUN this delivery.

Four prior failed full attempts and their investigation are retained in the checkpoint, not stitched into this full PASS. Three established test-premise corrections address the original Profile expansion expectation, mismatched real/test clock, and unsynchronized durable-page prerequisite. The fourth Summary timeout had no original state/UI capture; a separate controlled experiment established the late-PENDING navigation defect, not retrospective proof of that original occurrence. The original failed closeout dialog value was also not captured.

Final closeout-boundary targeted execution: **9/9 actual PASS**, 70.432s (one complete learning-loop case and all eight closeout UI cases). The new real-Room delayed-write case proves that a stale end-page confirmation is refused while the Session remains ACTIVE, then succeeds only after the user corrects the end page. The original five-second waits and final data assertions remain intact. The final Android-test-only build passed in 2m16s; unchanged JVM/production tasks were UP-TO-DATE, not a newly executed JVM result.

Controlled late-PENDING navigation RED: **5 cases / 4 actual PASS / 1 expected assertion failure**, 40.738s. After the minimal UI guard, all five navigation cases, all eight Closeout UI cases and the original complete learning loop passed: **14/14 actual PASS**, 101.407s, including genuine PENDING recovery. Complete JVM reran **637/637 actual PASS** and static/debug/test APK checks passed in **4m56s** after that production guard. These targeted results do not substitute for the final full Android run.

Final local Debug APK: `build/deliveries/v1-experience/Mirra-0.2.0-trends-first-action-debug.apk`, **16,853,161 bytes**, SHA-256 `76CA0BA8D03D44A22BAE03D49131D7A97F3A55E9274E8402A06DBE4AF7CA14C9`; version `0.2.0`, code `2`. Final cover installation preserved the three measured current private-file hashes. The earlier installed visual captures have identical visual code/resources, before the nonvisual navigation guard.

Actual installed synthetic UI covered explicit creation, Preparation, Force Stop/cold-start retaining the same Intent, explicit confirmation creating exactly one NORMAL-ended Session, and offline Start / Knowledge / Mine / detailed trends / Data Management. Offline cold launch was 1,827 ms; network restored. The earlier BA2DA… build owns the 2,053ms launch and installed screenshots. The final 76CA… cover installation succeeded and three measured current-file hashes matched; after exact baseline recovery, its cold launch took **3,073 ms**, Mirra held window focus and all three main navigation controls were visible.

Same-run formal original-baseline restore: **1 actual PASS**, **1.068s**. All 12 authoritative tables/raw facts, JPEG bytes and the four portable preference values/presence matched the original digest and row counts. Only verified fixture-owned cache was removed. Journal/cache were absent; permissions/network/display matched the original state, no active learning/monitoring/overlay/intervention/unexpected active owned DND was observed. No post-restore navigation changes the original preferences. No physical device was operated.

Existing full-suite backup, journal and export cases actually passed; the baseline restore used the production service. A new manual SAF backup/export picker round-trip is **NOT RUN this delivery**, rather than being inferred from service or historical 4E results. No data/schema or frozen business rule is changed. Complete delivery awaits independent review; it is not accepted/frozen.

## Screenshot index

Five actual Android captures using synthetic/in-memory Room and Mirra Blue:

- [Mine overview](screenshots/mine.png)
- [Trend hierarchy](screenshots/trends.png)
- [Real daily trend](screenshots/trends-daily.png)
- [Create book / explicit action](screenshots/create-book.png)
- [Preparation / explicit completion](screenshots/preparation.png)

Three captures of the actual installed synthetic journey under its previously retained monochrome preference (not a palette redesign):

- [Installed creation](screenshots/create-book-installed.png)
- [Installed Preparation](screenshots/preparation-installed.png)
- [Installed Mine](screenshots/mine-installed.png), including the corrected positive sub-minute label; captured before the nonvisual navigation guard, with identical visual code/resources.

All are controlled synthetic evidence, not original private data, real-device compatibility or a complete six-viewport pixel matrix.
