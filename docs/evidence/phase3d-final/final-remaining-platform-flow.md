# Remaining actual API37 validation — 2026-10-06

Execution baseline `d0da1061a7b706e25784e43fc700189564bbe980`, branch `codex/phase-3d-final-validation`. Only dedicated `Mirra_API_37`, Android17/API37, qemu1/boot1; device-local timezone GMT. No physical-phone operations. No production/test code repair, clock change, wipe, clear or uninstall.

The previously accepted **RECOVERY_DIAGNOSTIC_INCONCLUSIVE** is inherited. **Historical intermittent Recovery anomaly — Observed once; Not reproduced in targeted diagnostic** remains a known risk. Preserve [the original anomaly](recovery-after-overlay-not-completed.png), [authorized execution](authorized-permissions-and-recovery-review.md) and [targeted diagnostic](recovery-evidence-chain-diagnostic.md). This run does not establish a root cause or fix.

## Prerequisites

After a non-wiping cold boot of the dedicated AVD, read original Usage/Overlay app-ops as default, actual application DND policy access=false, POST permission=false, DND/cross-app preferences OFF/OFF, original controlled preservation risk selection, network1/1, active Session/Segment0/0. Snapshot logs remain local only.

Temporarily granted only Mirra's four authorized capabilities. App resume refreshed actual Diagnostics to Usage/DND AVAILABLE, policy access=true, notification-visible=true; app-op and runtime permission readback confirmed. An immediately opened, not-resumed Diagnostics initially displayed cached false; it was not used as permission-success evidence. No global Notification Policy or other App permissions were changed.

## Near-closeout: loss before normal finish

| Actual path | Durable facts before confirmation | Final facts |
| --- | --- | --- |
| Usage Access revoked in healthy Session | `901ca1d7-301e-4ad1-a496-09e2226f0cfe`: FULL/FOCUS/DND ACTIVE → PARTIAL, lostAt1791248715982, ACTIVE/UNMONITORED | NORMAL/COMPLETED/PARTIAL, endedAt1791248733170, DND RELEASED |
| Existing Diagnostics “停止监测测试” user action | `6b1bd78a-4387-4e82-9417-9f778c8a6757`: FULL/FOCUS → PARTIAL, lostAt1791248813339, ACTIVE/UNMONITORED; ServiceRecord0 before finish | NORMAL/COMPLETED/PARTIAL, endedAt1791248834192, DND RELEASED |

Both timelines close the preceding FOCUS exactly at loss and close UNMONITORED exactly at the final boundary. Actual Summary explicitly says incomplete monitoring and supplies no effective focus. No FULL restoration or background FGS restart. [Actual revoked-Usage UI](final-usage-revoked.png) is before closeout, not a seeded PARTIAL fixture.

Real query deadline/gap was not artificially created by freezing Android. Existing `ModuleThreeDCloseoutRepositoryTest#realSixSecondGapSettlesBeforeCloseout` and `ModuleThreeDFinalBoundaryTest#lossAtFinalConfirmationNeverProducesTrustedFocus` are real Controller→Room→validator automation, not an actual OS-delay experiment; their fresh final-suite results are recorded separately.

## Final Start → monitored Session → notification → Allowance → reading

Used existing controlled Mainline `3D1 Closeout Gate`; Start → Preparation → explicit “我已拿起书，开始阅读”. New Session `be72e416-9aea-4618-a935-ca98555cef5f`, initially page42 / FULL / FOCUS. DND preference OFF for this Session; cross-app snapshot ON; Overlay unavailable(default), notifications available. Earlier near-closeout Sessions exercised actual DND ACTIVE→RELEASED; do not claim this notification-visible Session had DND active.

Page42→44 persisted, one nonblank Note persisted via existing500ms autosave and remained through every panel, external route and closeout. The short Chrome visit (~3sec) recorded RISK_APP_BRIEF_VISIT and retained FOCUS; the next visit (~14sec) generated durable confirmation at1791248970543 with candidate boundary1791248960521.

### Session-level notification fallback — actual execution

- No Mirra Overlay window attached. Mirra notification ID3002 existed while Chrome was foreground.
- `fullScreenIntent=null`, notification channel `mBypassDnd=false`; no full-screen entry or DND bypass.
- [Actual drawer](final-notification-drawer.png) contained the correct controlled-book title and “查看”. The actual user-facing PendingIntent was clicked, returning the existing Session's safe in-app prompt, not creating a Session or granting Allowance.
- No NOTIFICATION `INTERVENTION_SHOWN` was written. POSTED is not SHOWN; actual drawer visibility is a separate manual observation. The later in-app prompt has a separate IN_APP receipt.
- Risk exit was observed normally by the monitor and produced RECOVERY; do not misdescribe that legitimate monitoring transition as a Segment directly authored by notification navigation. At the click, no TEMPORARY_ALLOWANCE existed.

User subsequently chose RESEARCH/5min and explicitly granted Allowance. Original deadline1791249317046; one confirmed +120000ms extension →1791249437046, extensionCount1. Earlier DISTRACTION remained historical. “提前结束” entered RECOVERY at1791249051794.

### Actual Recovery result

Quiet real observation, no fake clock: RECOVERY_SUCCEEDED and new FOCUS at1791249143455, **91,661ms** after Recovery start; FULL persisted, lostAt=null. No new ≥110sec anomaly in this scene. The earlier21sec Recovery was intentionally superseded by Allowance, not a failed90sec completion window.

This supplements—not replaces—the original91.566sec success and targeted90.827/90.858/91.006sec successes. **Production fix: NO.** No root-cause-resolution or AVD-cause claim.

## Precise closeout and unified reading record

Actual final confirmation saved NORMAL/COMPLETED/FULL at1791249203077. Last Segment ended at the same boundary. Timeline:

| Segment | startedAt | endedAt |
| --- | ---: | ---: |
| FOCUS | 1791248881913 | 1791248960521 |
| DISTRACTION | 1791248960521 | 1791248996135 |
| RECOVERY | 1791248996135 | 1791249017046 |
| TEMPORARY_ALLOWANCE | 1791249017046 | 1791249051794 |
| RECOVERY | 1791249051794 | 1791249143455 |
| FOCUS | 1791249143455 | 1791249203077 |

Summary showed42→44/2pages,5min Session time,1Note,2min effective focus,0Break/1Allowance/1Distraction. [Actual FULL Summary](final-full-summary.png) and [inline timeline](final-full-inline-timeline.png) are ordinary real monitored execution, not controlled FULL fixtures.

Result remained open for at least32sec. Before/after: endedAt1791249203077, duration321164ms, last Segment end identical,6Segments,16Intent/16Session rows, no active Session/Segment, no monitoring ServiceRecord, no active intervention notification. No cleanup or result-view time entered the learning duration.

Actual History entry and Search SESSION result(query44) both displayed the same42→44/5min/1Note/2min effective/0–1–1 counts. History Back returned to book detail; Search Back retained the search result. Inline timeline stayed in the existing Summary route. No new learning facts from any read entrance.

## Completed external request replay

Replayed the saved real notification episode's explicit VIEW URI/request after COMPLETED, into the existing Activity. This is an equivalent saved request replay, **not a second send of the original system PendingIntent**. Readback retained endedAt1791249203077,6Segments,16Session/16Intent rows, active0/0; no Allowance, Recovery, Segment or FGS resurrection.

PENDING-stage stale-action guards remain separately proven by `ModuleThreeDPendingGuardsTest`; no dangerous manual A/B interval was fabricated. Existing crash fixtures, Stage B trigger/retry, backward-clock and layout tests are separate automation evidence, not this manual scene.

## Final gate outcome and safe stop

This milestone alone is not 3D-4 completion. Subsequent fresh JVM318/318 passed, but full connected269 passed/2 real timeouts/4 opt-in assumptions was not clean; then the actual preservation check found the controlled JPEG missing and identified shared test file cleanup. Final gates stopped without production/test repairs. Temporary permissions/preferences/risk/network were restored and actually read back, but the missing JPEG was not fabricated or reseeded. Full counts, targeted2/2 diagnostic results, current artifact distinction and remaining gates are recorded in [final regression blockers](final-regression-blockers.md) and the final checkpoint. Ordinary full suite intentionally does not execute four opt-in fixtures; their original explicit actual executions remain separately classified.

API23–36 full matrix, OEM/full physical compatibility, TalkBack, release/Play, hardware power loss, actual system clock changes and real15min Deep Focus remain NOT RUN. OnePlus13T was not operated.
