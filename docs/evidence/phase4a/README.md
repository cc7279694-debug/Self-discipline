# Phase 4A evidence index

Parent: `cc5180762d9f735754ce3a5b0759089aefe6dcc9`. Scope and final execution record: [checkpoint](../../checkpoints/2026-10-07-phase-4a-trends-foundation.md).

Status: complete / awaiting independent review. No independent acceptance/freeze claimed; no 4B.

## Final execution summary

| Gate | Actual result |
| --- | --- |
| Unfiltered JVM | 374 discovered / 374 PASS / 0 failure,error,skip |
| One unfiltered connected run | 333 discovered / 324 actual PASS / 0 business failure / 9 unmet assumptions; Gradle exit0, 23m05s |
| New 4A connected cases | 39/39 actual PASS (Room8, standalone Compose29, Room navigation2) |
| Raw connected XML | failures9 / errors0 / skipped0, all nine explicit assumption bodies; runner0failed/0ignored |
| Opt-in / missing platform prerequisites | Opt-in4 NOT RUN; DND3/overlay1/notification1 permission cases NOT RUN, not PASS |
| lintDebug | 0 errors / 9 existing warnings / 1 hint |
| assembleDebug / assembleDebugAndroidTest | PASS |
| Frozen scope | Room4, schemas1–4, migrations, Manifest, permissions, dependencies and frozen Phase2/3/theme/brand/Start unchanged |
| Cover install | Success; 18 current files identical hashes before first launch |
| Actual cold start / offline | Ordinary COLD/ok5,249ms; offline COLD/ok6,274ms; active default network=none before/after offline route checks |
| Actual installed navigation | Start EmptyLibrary / Knowledge / Mine / Trends / empty global History; system Back from both new routes returns Mine |
| Controlled nonempty navigation | Real in-memory Room fixture → global history → existing ReadingRecord detail → history → Mine; learning facts unchanged |

Permission state was not altered. Network was restored to its read original state: airplane0/Wi-Fi1/mobile-data1. No physical device was touched.

## Performance measurements

Final full-run QueryCallback counts business SELECTs only; all are asserted off the main Looper. Sizes are controlled Intent and ended-Session counts, not private user histories.

| Facts per cohort | Business SELECT | Read elapsed ms | Sampled heap delta bytes |
| --- | --- | --- | --- |
| 0 | 2 | 5 | +131,072 |
| 1 | 5 | 6 | +65,536 |
| 800 | 5 | 130 | -5,619,712 |
| 801 | 10 | 93 | -1,507,328 |
| 1,601 | 15 | 181 | +667,648 |
| 10,001, run1 | 65 | 1,770 | +2,772,992 |
| 10,001, run2 | 65 | 1,737 | +2,174,976 |
| 10,001, run3 | 65 | 1,746 | -2,289,664 |

10k fixture seed cost25,721ms is not a read measurement. EXPLAIN: cohort scans/temporary sorts, existing intentId JOIN index, context PK, existing Segment/Event session/time indexes and partial temporary ordering. No N+1 and no necessary new index demonstrated at this measured size; larger histories need their own evidence/review. Exact medians retain O(N) scalar samples; raw Session histories are released per batch of <=800 Session IDs. Segment/Event row counts depend on those Sessions, not a hard800-row limit.

Cumulative installed gfxinfo across cold start/Mine/Trends:18frames/17janky. This is not an isolated Trends-entry test or proof of a database bottleneck; off-main SQL does not mean zero UI jank. AVD rendering/bridge observations and unconfirmed causes remain in the checkpoint.

## Inspected screenshots

| File | Source / meaning |
| --- | --- |
| [Mine](phase4a-mine.png) | Actual installed dedicated AVD, empty recent summary and two new entries; no private titles/notes |
| [7d Start](phase4a-trends-normal.png) | Controlled projection fixture;13Intents/9starts are fixture values, not user performance |
| [Empty trends](phase4a-trends-empty.png) | Actual installed empty 7d, offline; no manufactured sample |
| [Unavailable](phase4a-trends-unavailable.png) | Controlled NONE/PARTIAL projection, unknown is not zero |
| [ALL](phase4a-trends-all.png) | Real in-memory Room navigation fixture, no previous-period comparison |
| [Global history empty](phase4a-global-history-empty.png) | Actual installed empty list, offline; does not prove a manual nonempty journey |
| [Existing detail](phase4a-history-detail.png) | Controlled real Room fixture using the unchanged ReadingRecord content |

All seven pixels were inspected. No clean320dp/font2 screenshot is claimed: the full-run capture was blank. Six viewport combinations and48dp reachability passed semantic Compose assertions. Full-run Mine wrong-frame/History loading/blank captures and initial ANR-obscured captures remain in ignored local logs; they are not relabeled as visual PASS.

## Manual limitations retained

- An immediate offline UI dump returned a null bridge; its stale/missing XML was not used. The network was restored; a later check explicitly confirmed no default network and actually navigated the empty routes.
- Optional additional ADB fixture creation did not produce the requested text values. The form was cancelled; Start EmptyLibrary was confirmed. No DB fact was hand-edited or fixture completion invented; that extra manual nonempty path is NOT RUN, with cause unconfirmed.
- The complete automated run had no business assertion failures. These manual/pixel limitations are recorded separately, not used to inflate its totals or erase earlier failures.

## Local delivery APK

`build/deliverables/Mirra-Phase4A-debug.apk`,16,488,839bytes,version0.1.0/code1,`com.guanyi.mirra`.

SHA-256:`CA6D97546D1D5A0012B5DF8C51C820EA5E944EF8CCF47091009B47EA18E21CC5`.

Production build HEAD:`e799997002d7c8d4dbb833c7452d194e8bad5d9f`; Task5 does not modify production. APK remains local, not a release/Git artifact.

## Evidence boundaries

- Dedicated existing API37 AVD only. No physical devices, permissions grants, wipe, clear or uninstall. Keep-installed parameter supplied for the full connected run.
- Projection/detail Compose screenshots use controlled projections or an in-memory Room navigation fixture, not private installed notes or real reading performance. Three actual installed screenshots show only empty states/Mine entries. Standalone Compose viewport screenshots do not certify production system-bar insets or OEM behavior.
- Semantic Compose actions and physical screen pixels are different evidence. Initial screenshots were obscured by a Pixel Launcher ANR; those raw images remain local and are not clean UI evidence. Only the seven inspected screenshots above are retained as correctly classified images.
- Performance uses10,001 controlled Intents/ended Sessions. Seed cost is separate from read cost. Heap deltas are before/after Java heap samples, GC-sensitive, not peak RSS or a constant-space proof. Exact medians retain scalar samples; raw Session facts are page-bounded.
- Global history's fixed time cutoff is not a long-lived cross-page SQLite snapshot. Later completed closeout facts can be inserted with an older endedAt; displayed IDs are deduplicated.
- Current-file hashes across a cover install can prove that specific installation's preservation, not the older installation previously removed by a historical test-framework run.
- API23–36/OEM/full physical matrix/TalkBack/release-Play/real power loss/manual clock changes remain NOT RUN. OnePlus13T daily-use feedback is not compatibility PASS.

## Frozen schema hashes

Room version4; no schema/migration/index changes from parent.

| Schema | SHA-256 |
| --- | --- |
| 1 | `4528DCEDF74A1875D7A132F2E41F00049A252F82070B8DB1C25CBE5C46ACD1B1` |
| 2 | `C831ED5C8B3A0AA5C56E43859AB0AF4EA1343E4AB4AF76224EE86B70F3E79E0D` |
| 3 | `CCB563F899DD68ECBFBE3369A7939F6D202CD1393508C26E0B33EE6A91CE1205` |
| 4 | `EDCD0867D643CFE12CDB8906FDE859C8B1929B5BCDFA4AC11B5BDCD31A4C11B9` |

Raw local logs, device identifier, complete XML device properties, complete dumpsys/logcat, user data and APK binaries are excluded from Git. Only minimal redacted results and controlled screen pixels belong here.
