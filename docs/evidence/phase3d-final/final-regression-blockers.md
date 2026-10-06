# Final regression stopping evidence — 2026-10-06

Status: **STOP / review required; Phase 3D-4 is not complete.** Only the explicitly selected Mirra_API_37 AOSP AVD was used. No physical device, production repair, schema change, data reset, uninstall or system-clock change.

Resume baseline: `d0da1061a7b706e25784e43fc700189564bbe980`. Actual remaining user-flow milestone: `50d9a0bdf339c623169cb2f1e6f3924a7bc2631c`; see [actual platform flow](final-remaining-platform-flow.md). The historical Recovery anomaly remains inconclusive, not fixed.

## Fresh gates: report semantics, not just task exit

| Actual execution | Result |
| --- | --- |
| Unfiltered JVM, actual test task rerun | 318 discovered / 318 executed / 318 passed / 0 failure / error / skipped |
| First fresh unfiltered connected, 10m15s | 275 discovered / 271 executed / 269 passed / 2 assertion failures / 0 errors / 4 opt-in assumptions; Gradle exit1 |
| Original AGP/UTP XML for that connected run | 275 tests / 6 failure nodes / 0 errors / 0 skipped; four failure nodes are AssumptionViolatedException and two are ComposeTimeoutException |
| One two-test diagnostic rerun, no code changes | 2 actually executed / 2 passed / 0 failure / assumption; runner OK, 41.268sec. This does **not** replace full-suite PASS |
| Attempted second full connected command | Gradle task-selection failure before tests: the PowerShell invocation split the unquoted dotted `-P` property. No new connected execution or XML; not a Mirra assertion failure |
| Fresh final lint / assemble / exact-file cover-install / offline preservation gate | NOT RUN after the data-preservation stop; previous passing records remain historical, not substituted |

The first fresh full run actually executed all five formerly permission-gated cases with passing assertions: AndroidDndSystemTest's three methods, grantedOverlayAttachUpdateAndRepeatedRemoveAreSafe and grantedNotificationIsPostedButNeverFullScreen. All six ReadingRecordUiTest width/font cases (320/360/411dp × fontScale1/2) also passed. The four opt-in fixtures remain default assumptions; their original explicit prepare/assert/seed/assert executions were rechecked as 4/4 PASS, not rerun or added to the 269 full-suite passes.

### Two intermittent result-display failures

- `PhaseOneCorrectionTest.finishingImmediatelyFlushesDraft`, line130: after clicking final confirmation, the test did not find “1 条笔记” within5000ms.
- `PhaseOneLearningLoopTest.completeLearningLoopCreatesBookNotesProgressAndSummary`, line70: after clicking final confirmation, it did not find “本次阅读已保存” within5000ms.

Both passed the single unchanged targeted rerun. Per-test logs contain no Mirra FATAL EXCEPTION, ANR, SQLite/Room or SecurityException explaining the failure. Framework IME/FrameTracker warnings also occur in passing tests and are not a confirmed cause. CloseoutRepository43/43, PendingGuards11/11, SessionCloseoutUi6/6 and ReadingRecordNavigation4/4 passed in the same full run. The failure-time semantic tree and Stage A/B state were not captured; root cause remains unknown, not “AVD issue” or “resolved”. Original XML, test logs and runner output are preserved locally, not edited or uploaded wholesale.

## Blocking preservation fact: test storage is not isolated

After the full run and diagnostic rerun, a read-only check found:

- Persistent ImageAsset `bbd73522-cf32-404c-819f-10a6d53526d4` still references `images/8cdecdc5-70c7-4e3e-86e5-910ac5acc5d8.jpg`, fileSize818.
- The referenced JPEG does not exist; sha256sum reports “No such file or directory”. The App-owned images directory is empty.
- The original preservation marker remains `VERIFIED_PREFERENCES_RESTORED`; its historical expected JPEG hash is `df30c3e577a2ac4ef7d299ee08c4c78e0f5e6a28016e6c595c7c206920793fcc`. The marker's earlier success is **not** proof that the current file survives a subsequent full suite.
- Persistent learning facts remain16 Sessions /16 Intents, active Session/Segment0/0; the latest real FULL record is still NORMAL at1791249203077/page44. This is a confirmed missing file, not observed loss of those database rows.

Source inspection identifies an existing unsafe test boundary: `app/src/androidTest/java/com/guanyi/mirra/TestAppContainer.kt:112–113` deletes `context.filesDir/images` and `image-work` recursively in close(). PhaseOneCorrectionTest and PhaseOneLearningLoopTest construct it using ApplicationProvider's target application context. DefaultImageStorageService uses the same application filesDir for its production image root. In-memory Room does not isolate these files. The cleanup is existing test code, not a production repair or a newly introduced 3D-4 change.

No fresh JPEG checksum was taken immediately before this turn's first full run, so the exact deletion time cannot be assigned to one particular run. Nonetheless the current missing-file condition and shared destructive cleanup are confirmed. They trigger the data-loss stop rule. This is not attributed to the two Compose timeouts or to production image compensation. The only affected data inspected here is dedicated-AVD controlled fixture data; no physical or personal device was operated.

No image was recreated, row deleted, marker reset, test expectation weakened or cleanup code patched. A future separately authorized test-isolation correction must address every test storage owner, preserve current facts, then re-establish actual image preservation and obtain a new complete clean connected run. Internal inspection is not user independent acceptance.

## Safe stop and restoration: actual readback

Further instrumentation was stopped before continuing data verification. The attempted second full run had already failed task selection and never executed tests. Only Mirra's target/test processes on the dedicated AVD were stopped; no other App or physical device was changed.

- Usage Access=default; Overlay=default; POST_NOTIFICATIONS=false with original flags; actual resumed application Diagnostics DND policy access=false, notifications visible=false, Usage/DND NEEDS_USER_ACTION.
- DND preference OFF; cross-app preference OFF; original single controlled preservation risk selection retained, temporary Chrome selection removed.
- Wi-Fi/mobile data1/1; original consolidated notification policy equals restored policy. Current Mirra-owned rule STATE_FALSE and system Zen mode OFF; historical Zen-log STATE_TRUE entries are not current activation.
- Active Session/Segment0/0; monitoring ServiceRecord0; Mirra overlay windows0; intervention notification ID3002 records0. Returned to Knowledge. No background monitoring restart.

The missing controlled JPEG has **not** been restored. Restoring permissions does not repair that data condition. No final completion or Phase3D freeze is claimed.

## Frozen artifacts and unmeasured limits

Room remains version4; schemas1–4, production sources, Manifest, Gradle and migrations are unchanged from exact parent `80ece95cf24918627e57a57bb6a6d94c253528b0`. v4 SHA-256 remains `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`.

The connected build produced `app/build/outputs/apk/debug/app-debug.apk`:16,488,770bytes, SHA-256 `9C9B33337A8FB9A3969089308C28E8302AB285939DFBD47A4103CC7D48C68CD9`, appId com.guanyi.mirra/0.1.0/code1. Its163 uncompressed entries, inventory and public signer certificate match the saved frozen APK, but whole archive SHA differs. It is **not** a completed final delivery artifact; the existing deliverables copy was not replaced, cover-installed or newly verified after the stop.

API23–36 full matrix, OEM/full physical compatibility, TalkBack, release/Play, hardware power loss, actual system-clock changes, actual OS query-gap manipulation and real15min Deep Focus remain NOT RUN. Existing fake-clock/Room and rule automation are separate evidence. OnePlus13T daily use is not compatibility PASS.

**Stopping point: final connected is not clean and controlled image preservation fails. Review/authorization required; no further production or test change performed.**
