# Phase 4B — sanitized final validation evidence

Execution date: 2026-10-07. Classification **VALIDATION_RECOVERED** remains historical execution evidence. Independent acceptance date: 2026-10-08; Phase4B **accepted / frozen — PASS WITH NOTES**. See the [complete checkpoint](../../checkpoints/2026-10-07-phase-4b-descriptive-insights.md) for original failures, forensics, test-only stabilization and retained evidence boundaries. Independent review and this documentation Freeze did not rerun Gradle / AVD.

## Exact source boundaries

- Accepted production HEAD: `5cbff38853c069b02772b6062d5a7e96a1f9fb13`.
- Accepted test-only stabilization HEAD: `f000ca03d5eb03646d76f199fe2cb899979dfe68`.
- Accepted validation HEAD: `2f1b271e6b958e0cc9b21153e168c91a970ac500`. Test-only, validation and Freeze SHAs do not replace the production SHA.
- Validation publication and this Acceptance Freeze: no production/test/Manifest/Gradle/schema/migration edits. Documentation/evidence only; this Freeze performs no new test/device execution.
- Room4, Schema1–4 unchanged; v4 **file SHA-256**, not Room identityHash: `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9`.

## Environment and actual gates

Fresh `Mirra_API_37_Phase4B_Final`; Android17/API37 Google APIs x86_64 image revision6, build `CE2A.260420.019`, Emulator37.1.11.0. No clone/snapshot/data/marker import; old AVD retained. Four initial health rounds span72.102s. Full pre/post boot, system services, SystemUI and MainActivity normal, system_server unchanged, no ANR/DeadSystem/transport abort observed.

| Gate | Actual result |
| --- | --- |
| ModuleTwoA exact lifecycle method | 3 independent invocations,3/3PASS |
| External stale-request exact method | 3 independent invocations,3/3PASS |
| Unchanged Closeout exact lifecycle method | 3 independent invocations,3/3PASS including cleanup |
| 4B focused JVM / Room / Compose | 57/57,8/8,10/10PASS |
| Fresh unfiltered JVM | 431/431PASS,0failure/error/skip |
| Lint | 0errors,9existingwarnings,1hint |
| Debug / androidTest APK build | PASS |
| Unique actual full connected | 351discovered,342actualPASS,9assumptions,0businessfailure/error/unfinished |
| Full runner reconciliation | 351unique starts/finishes,1run-finished summary, fresh XML agrees |
| Full duration / exit | 1046.447hostseconds, Gradleexit0 |

Raw connected XML has9failure nodes,0errors/0skipped; all nine are unmet assumptions. Four opt-in and five permission-prerequisite cases are **NOT RUN**, never counted as PASS. This is not351/351PASS.

One earlier host invocation failed before task execution because the dotted Gradle property was unquoted;0Android tests started. Its stale copied XML was excluded. Corrected quoting launched the sole actual full suite above, not repeated failed-business-test runs.

## Local canonical evidence and privacy

Ignored `.gradle/phase4b-validation/fresh-avd-20261007/` holds real instrumentation summaries, health/identity records, full JVM XML, lint XML, actual connected XML, time-filtered runner capture and classification. `final-full-execution-1-results/` is canonical fresh connected evidence; `final-full-results/` from the failed host selection is stale and not counted. Earlier Run A/B, Forensics Fresh Full and stabilization originals remain separately retained. This public index uploads no raw logcat/dumpsys, device serial or private user data.

No permission/network/clock change, wipe/clear/uninstall or physical-device operation in the recorded validation. Fresh installs used `install -r`; full used quoted `leaveApksInstalledAfterRun=true`. No old-installed private-file preservation hash chain or manual/pixel insight journey was performed. API23–36/OEM/physical/TalkBack/release-Play/real power loss/manual clock modification remain NOT RUN. AVD evidence is not extrapolated; OnePlus daily feedback is not compatibility PASS. This user-authorized Freeze is not a new validation run; Phase4C remains unstarted.

PASS WITH NOTES retains O(N) window materialization (not constant-space or13rows), controlled10,001-sample timing rather than an arbitrary-scale/OEM/production SLA, and all historical failures with their unresolved attribution. A fresh green Gate does not prove those old root causes fully solved.
