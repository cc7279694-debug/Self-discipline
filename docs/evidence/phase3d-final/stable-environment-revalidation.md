# Phase 3D-4 — Same-userdata revalidation and mandatory stop

Date: 2026-10-06. Branch: `codex/phase-3d-final-validation`.
Execution source HEAD: `b3b4c89d6ced6011babce93cd73bf49e2967115d`.
Frozen production parent: `80ece95cf24918627e57a57bb6a6d94c253528b0`.

**Not complete / not frozen.** The sole authorized unfiltered connected run completed, but the historical `finishingImmediatelyFlushesDraft` timeout recurred. Subsequent cover-install, v4 assertion and final gates were stopped. A SystemUI ANR record predating the run was discovered during cleanup; this run does not prove continuously stable UI conditions or a production root cause.

Only evidence documents and one dedicated-AVD screenshot are delivered. No source/test/Manifest/Gradle/Schema/Migration changes, timeout changes, targeted rerun or second full run. The accepted image isolation `9cfae55898ad6a1a21b0afb8a943a81599ad9ef8` and theme restore `5331661f24e0d1f01a89deeeaaeafbb3d6a3a891` remain intact. This internal evidence audit is not the user's independent acceptance.

## 1. Environment maintenance and evidence limits

- Explicitly selected `Mirra_API_37`, API37, qemu=1, boot_completed=1; no physical-device operations.
- One normal reboot of the same userdata. No wipe, clear, uninstall, snapshot rollback, system-clock change or second reboot. This is maintenance, not hardware reboot/power-loss validation.
- Four repeated health reads over 71.485 seconds found activity/package/window/power services, working shell, resolved MainActivity and installed debuggable `com.guanyi.mirra` 0.1.0/code1. The final round began at 04:10:36.128 UTC. Boot-time transient empty boot/resolve reads were retained locally and not counted as health PASS.
- Before seed: Active Session/Intent/Segment=0/0/0; no monitoring FGS, Overlay, intervention notification or unexpectedly active Mirra rule. Original Wi-Fi/mobile data=1/1.
- Original Usage/Overlay appops=`default`; DND access=false; POST_NOTIFICATIONS=false, original flags=`USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED`. All four temporary Mirra-only grants were actually read back before full.
- After the failed case and after full, service checks still succeeded and the crash buffer had no DeadSystem/FATAL record. That does **not** prove SystemUI stability: later `dumpsys activity lastanr` identified a SystemUI input-dispatch timeout at **04:11:10 UTC**, after the health samples but before full started. Device timezone was read as GMT, not inferred.

| Observation | UTC |
| --- | --- |
| Last health round began | 04:10:36.128 |
| Recorded SystemUI ANR | 04:11:10 |
| Sole full started | 04:12:58.368 |
| Historical test timeout | 04:25:14.062 |
| Full finished | 04:48:31.377 |
| System dialog visibly observed | During post-full cleanup |

The actual [system dialog screenshot](stable-run-system-anr.png) reads **“Process system isn't responding”** and contains only dedicated-AVD controlled test content. It was captured after full, **not** at the failing test. Screenshot SHA-256: `3B6CDADD6CF401924DD87375A0052D38A2BA949371D251FD8F0B2EC5D8CF0527`. The separate lastanr record identifies SystemUI; this does not equate the visible dialog's identity with that record. No “Wait” / “Close app” action or extra reboot was taken. The ANR and timeout are separate evidence; neither establishes that one caused the other. This is not the previous DeadSystem-interrupted partial suite.

## 2. Independent storage-isolation-v4 seed and snapshots

Explicit existing opt-in `data_seed`, key `storage-isolation-v4`, required AVD guard `Mirra_API_37`: **1 executed / 1 passed / 0 failure / 0 assumption**, 2.897 seconds. No reuse of v2/v3.

- Baseline frame: `5f64ecb5c28150c313911516623a30dfdd8e63bf1fddf52d5158afb87bc86e35`.
- Marker: SEEDED, SHA-256 `633681c91ffb61f1efff601bca7c64f55450d4f25d1afb3d1d7efc62a2184c85`.
- Preferences: `knowledge / night / true / true` (destination/theme/DND/cross-app).
- Original risk table: 1 row, SHA-256 `07b9ba7feafd9451048b6e0188abf6195c96549bf0431f5dee812a66bf869cc8`.
- Seeded risk table: 2 rows, SHA-256 `ce03da40460ffd56f7a611e8cf20df78d5c567c3b88822b5fd92eea5ca260015`.
- JPEG: `images/e817709f-1c48-4b63-aa02-055d35a62c41.jpg`, 818 bytes (metadata also 818), SHA-256 `df30c3e577a2ac4ef7d299ee08c4c78e0f5e6a28016e6c595c7c206920793fcc`.

| Stage | JPEG/path/size/SHA | Four preferences | Risk hash | Marker bytes |
| --- | --- | --- | --- | --- |
| Seed readback | Baseline | Baseline | Seeded | SEEDED baseline |
| Immediate pre-full | Identical | Identical | Identical | Identical |
| Automatic immediate post-full, before any install/UI cleanup | Identical | Identical | Identical | Identical |
| Final retained read-only snapshot | Identical | Identical | Identical | Identical |
| Candidate install-r / post-install | NOT RUN — full failed | NOT RUN | NOT RUN | NOT RUN |
| Same-key data_assert | NOT RUN — prohibited after stop | NOT RUN | NOT RUN | Unconsumed |

This proves snapshot retention across this **failed complete run**, not a successful end-to-end preservation chain. v4 remains byte-identical SEEDED, externally classified **STOPPED_FULL_FAILURE / UNCONSUMED**; that classification is not written into its marker. No assertion, reseed, expected-value edit, fixture deletion or JPEG repair.

## 3. Sole complete unfiltered connected run

Executed once:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --no-daemon
```

The CLI retention option protects existing userdata; no Gradle file/test behavior change, class filter, timeout or retry. Gradle exit=1, duration 35m32s (host ledger 2133.009 seconds). No later test execution was performed.

| Count | Actual |
| --- | --- |
| Discovered XML testcases | 286 |
| Unique identities / terminal outcomes | 286 / 286 |
| Actually executed and completed | 282 |
| Passed | 281 |
| Real test failures | 1 |
| Errors / incomplete nodes | 0 / 0 |
| Explicit opt-in assumptions | 4 |
| Other skipped nodes | 0 |
| Raw XML root tests/failures/errors/skipped | 286 / 5 / 0 / 0 |

The five raw failure nodes are **one real Compose timeout plus four AssumptionViolatedException nodes**. The assumptions are the existing pending_prepare/pending_assert/data_seed/data_assert opt-ins, not PASS. Declared counts match nodes; no duplicates, missing identities or multiple outcomes. XML suite.time=0.000, so elapsed duration comes from Gradle/host ledger, not that field.

Final raw XML: 64,683 bytes; SHA-256 `6D5BDBBC4A96D8F1CB4D1FF4EBAD043AA9ECFA86A59347804DD83BD61F845BE6`. Full XML/HTML, runner logs and private system snapshots remain local, not Git.

### Exact recurring failure

`com.guanyi.mirra.PhaseOneCorrectionTest#finishingImmediatelyFlushesDraft`, case duration 38.324 seconds:

```text
androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 5000 ms
    at androidx.compose.ui.test.AndroidComposeUiTestEnvironment$AndroidComposeUiTestImpl.waitUntil(ComposeUiTest.android.kt:901)
    at androidx.compose.ui.test.junit4.AndroidComposeTestRule.waitUntil(AndroidComposeTestRule.android.kt:500)
    at com.guanyi.mirra.PhaseOneCorrectionTest.finishingImmediatelyFlushesDraft(PhaseOneCorrectionTest.kt:130)
```

The test creates its isolated TestAppContainer, types the last Note, requests finish, waits for confirmation, confirms, then waits for **“1 条笔记”**. That last condition timed out at the same historical location. This does not by itself establish Note data loss, Room corruption or a production bug. The failing test's in-memory Room was closed by teardown before operator inspection; the retained real-storage v4 snapshot is not a substitute for that test's failure-time Room state.

The other historical case, `PhaseOneLearningLoopTest#completeLearningLoopCreatesBookNotesProgressAndSummary`, actually **passed**, 54.545 seconds. No targeted rerun was used to replace either result.

### Platform prerequisites genuinely satisfied

All exact named cases had no failure/error/assumption:

- DND **3/3 actual PASS**: `accessibleNonMirraRuleIsIgnored`, `controlledPolicyChangeIsRejected`, `ownedRuleIsReusableAndNeverChangesGlobalPolicy`.
- Intervention **5/5 actual PASS**: `independentChannelDoesNotBypassDndAndStartupCancelIsIdempotent`, `missingOverlayPermissionDoesNotAttachOrCrash`, `notificationDenialReturnsUnavailableNotPosted`, `grantedOverlayAttachUpdateAndRepeatedRemoveAreSafe`, `grantedNotificationIsPostedButNeverFullScreen`.
- Theme **3/3 actual PASS**: `initialNightThemeIsRestoredAfterLifecycleChecks`, `initialBlueThemeIsRestoredAfterLifecycleChecks`, `persistedThemeIsAppliedAfterActivityRecreate`. Immediate real DataStore readback remained NIGHT, not merely a display initial value.
- Six record width/font automation cases actually passed. They are controlled automation, not the unfinished installed-app manual visual gate. Notification POSTED still does not mean SHOWN.

User-authorized stop condition is met: **FULL_SUITE_INTERMITTENT_CONFIRMED / ARCHITECTURE_REVIEW_REQUIRED**. Full completed but was not clean; the separately discovered SystemUI ANR further limits environmental claims. No production diagnosis or fix is authorized by this evidence.

## 4. Safe restoration and explicit outstanding state

- Four system permissions restored/read back exactly: Usage/Overlay default, DND false, POST false with original flags. Wi-Fi/mobile data remained/restored 1/1. No global Notification Policy or other App permission/rule changes by the operator.
- Current `mConfig=ZenModeConfig` contains one Mirra-owned rule, STATE_FALSE; global Zen OFF. An initial broad dump scan counted historical STATE_TRUE lines. That local read-helper parsing issue was corrected to inspect **current configuration only**; no DND mutation was made in response to history.
- Active Session/Intent/Segment=0/0/0; monitoring ServiceRecord=0, Overlay=0, intervention notification=0.
- UI cleanup launch returned Status ok, but the system ANR dialog blocked interaction. No UI toggle was changed. This is **not** a final exact-APK cold-start/user-flow PASS.
- **Original preferences/risk selection are not fully restored.** Current four values remain knowledge/night/true/true, with the v4 synthetic risk row present. Excluding only that row from a read-only hash yields the exact original one-row hash; no other risk row changed.
- Current production UI has no BLUE setter and its risk picker only shows launchable Apps; the synthetic row is not launchable. Under the no-source/no-new-test boundary, no safe existing endpoint can finish these two operations. No raw DataStore/SQL rewrite, old cleanup helper reinstall or failed-chain data_assert was used.
- **Need separate cleanup authorization**, preferably marker-scoped existing-Repository restoration with exact original hashes, without altering marker or consumed facts. Permissions are restored, but full preference/risk restoration must not be claimed.

## 5. Retained history and NOT RUN

- Legacy missing JPEG remains missing; its ImageAsset row count remains 1. Legacy marker SHA `6bbd53c86d2cd35dd1b99e74af79e28925c9335cce45e9c73ffc1de54a7fd445` unchanged.
- v2 remains FAILED_PREFERENCES_RESTORED, marker SHA `b81af0e97b2a2d489928cf530084aa500b532fd3081243dedb04fd47eb3c437c`; image unchanged.
- v3 remains original SEEDED bytes, SHA `33b6d2db91000c1f24427337644aa54a0f359e78a0d67550028e6f29d4e4ae64`; image unchanged. It remains **INTERRUPTED / UNCONSUMED**, not failed or PASS. No v3 assertion/reseed/deletion/re-entry.
- Historical Recovery >120sec anomaly, Compose timeouts, RED, FileProvider command failures, and prior DeadSystem/ADB-aborted partial run remain in their original records. Earlier targeted/full successes are historical, not stitched into this run.
- Local setup path/patch-context failures and the first wrong read-only legacy-marker path were retained in the local execution ledger. They changed no device data/source and are not test failures; the actual path was confirmed before a read-only retry.
- NOT RUN after this stop: candidate/final exact-file install-r, v4 data_assert, fresh JVM/lint/assemble, final APK copy/hash delivery, final offline closed-loop and installed-app manual dimensions/PARTIAL screenshot. Earlier JVM318, lint/build and actual platform flows remain historical evidence only.
- API23–36 full matrix, full OEM/physical compatibility, TalkBack, release/Play, hardware power loss, manual real system-clock change and real 15min Deep Focus remain NOT RUN. OnePlus 13T daily feedback is not compatibility PASS; no physical device was operated.

## 6. Room / Git boundary

Room version=4; only schemas1–4 exist, all four hashes remain frozen:

| Schema | SHA-256 |
| --- | --- |
| 1 | `4528DCEDF74A1875D7A132F2E41F00049A252F82070B8DB1C25CBE5C46ACD1B1` |
| 2 | `C831ED5C8B3A0AA5C56E43859AB0AF4EA1343E4AB4AF76224EE86B70F3E79E0D` |
| 3 | `CCB563F899DD68ECBFBE3369A7939F6D202CD1393508C26E0B33EE6A91CE1205` |
| 4 | `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9` |

No production diff versus parent Freeze. No source/test/Manifest/Gradle/Schema/Migration diff versus this round's starting HEAD. Docs/evidence-only commit; no fake fix commit, main merge or publication. **Phase 3D-4 remains incomplete; Phase 3D not frozen; no next phase.**
