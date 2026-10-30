# Mirra 1.4.1 focus integration — bounded execution evidence

Status: **MIRRA_1_4_1_INTEGRATED_ANDROID_GATE_BLOCKED**. This is an integration trial artifact, not approval for a phone containing irreplaceable data, OEM compatibility, a Release, or 05E-1 implementation.

Implementation: `04c7fb1cd6d5cb522704b4ee07d1a40f0f01be73`, directly based on V1.4 `14d428481a5671f1d0cc4c41eec4ad38350a0710`. Protected V1.1.1 local WIP: `27676027464eca0689447ac9e9cc9ace99c54416` (local protection commit, not claimed as an independently pushed branch).

## Artifact

- `E:\CODEX\Mirra-deliverables\Mirra-1.4.1-focus-integrated-debug.apk`
- `com.guanyi.mirra`, versionName **1.4.1**, versionCode **10**; **17,079,758 bytes**.
- SHA-256: `AC283690CC5C4BB38F09905513A8323D2F06FB0E01963BAB98AFBF23FB9F2E93`.
- Public certificate SHA-256: `29DED26BEA44F1AFE4FE383A302E4B507423D16223469D7DA3FE578D9A7F83B9`; verified equal to original V1.4 APK, existing signing identity and read-only installed-phone APK metadata. Phone was1.4.0/code9 at that query; no phone launch/install/data/settings operation.
- Room **5**, schemas1–5 byte hashes unchanged from A; migration1→5 unchanged. Full Backup **2/schema5**, legal format1/schema4 input retained. JSON/CSV remain12phone tables, explicitly excluding wrist originals. No new permission/dependency or resource/Logo change.

## Actual gates, not combined into a fictional full PASS

| Execution | Actual result |
| --- | --- |
| Targeted JVM |321/321 PASS,0 failure/error/skipped|
| Final fresh unfiltered JVM after final test correction |879/879 PASS,0 failure/error/skipped|
| lintDebug |PASS:0 errors,13 warnings,1 hint|
| assembleDebug / assembleDebugAndroidTest |PASS; official SHA-pinned wearable AAR verified by existing preBuild task|
| Installed original code9 preparation |1/1 PASS,20.102s; formal baseline saved/readback/fsynced before synthetic mutation|
| Actual install-r code9→code10 |PASS; no uninstall,clear,downgrade or data wipe|
| Installed v5 retained-facts assertion |1/1 PASS,0.815s; exact all13table columns/IDs/counts, original wrist bytes/digest/identity/receivedAt, JPEG, four preference values/presence and FTS|
| Installed formal format2 roundtrip |1/1 PASS,2.809s; post-restore owner reacquired and exact retained frame verified|
| Original installed baseline restoration |1/1 PASS,1.192s|
| Native Room migration / receiver / backup compatibility / export group |25/25 PASS,0 failures/errors/assumptions,32.639s after test-only correction|
| Legacy12-seed/full13-snapshot UI protection prepare |1/1 PASS,0.780s|
| Actual offline installed reading journey |**1 FAIL / ENVIRONMENT_BLOCKED** at Espresso keyboard action; SystemUI ANR is visible in actual screenshots. Not a business-assertion or clean UI PASS|
| Post-journey original baseline restoration |1/1 PASS,1.152s; exact baseline/table/media/preference frame verified|
| Complete unfiltered connected gate |**NOT RUN / ENVIRONMENT_BLOCKED** after visible SystemUI ANR; targeted results are not a substitute|

The installed cover test is **v5→v5**. The old **v4→v5** chain and format1 compatibility were exercised by native migration/compatibility fixtures, not an installed old-v4 APK downgrade/upgrade journey. No real radio, wrist upload or physical-phone install was performed. Formal backup roundtrip used the installed production service and private file URI, not a new claim of SAF picker UI coverage.

## Failure history retained

1. Untouched A JVM849 total:848 PASS/1 FAIL. Windows CRLF made LF-based negative golden-fixture mutations no-ops. Corrected only that test's local input normalization plus a mutation-must-change assertion; actual protocol/golden bytes unchanged.
2. Closeout observer RED before the B implementation:5 total/2 expected assertion failures/3 control PASS. Both stale-PENDING cases then pass in final879.
3. First lint failed on ignored local SDK-property colon escaping; the second attempt reused the stale report. Corrected local configuration, explicitly reran main lint/report and retained both failed logs privately. No lint baseline, suppression or business workaround.
4. First native data group:25 total/24 PASS/1 FAIL. Inherited CSV test used backup13 as export scope. Corrected only to an independent literal12-table whitelist, exact count/set and explicit absent wrist CSV; full13source hash preserved. Native25 subsequently passed.
5. Installed reading failed after actual Note autosave, at `V111InstalledReadingJourneyFixture.writeNote`'s `Espresso.closeSoftKeyboard`. Two real screenshots already show the **System UI isn't responding** modal at reading00:01 and focus-display00:04. A stable SystemUI PID was not evidence of health. No driver root matcher, Back injection, timeout, production window or core-monitoring workaround was introduced.

## Actual screenshots — environmental obstruction, not clean UI acceptance

All are from the installed1.4.1 production app with synthetic data; none is a concept image or a mockup. They show attempted Preparation/normal reading/focus-display scenes but **the sequence is obstructed by SystemUI ANR**. Do not count them as a clean UI or complete-journey PASS.

- [Preparation capture](environment-anr-preparation.png)
- [Normal reading with SystemUI ANR](environment-anr-normal-reading.png)
- [Focus display with SystemUI ANR](environment-anr-focus-display.png)

The old B APK/movie/screens and all prior failures remain preserved separately. Their results do not become current1.4.1 evidence.

## Environment and restoration

Only a newly created dedicated E:-drive API37 google_apis x86_64 AVD was used: WHPX,2CPU,2048MiB,software SwiftShader,1080×2340,headless,no snapshot. First boot186.663s. Host available memory reached337–467MiB during its first run. The AVD was normally stopped, without reset, to rebuild the CSV test correction; the retained installation was resumed, not replaced with clean data. No broken old AVD restart or request to close user applications.

Before the actual offline journey, original capability/network states were recorded privately. Only Mirra Usage Access(default→allow) and POST_NOTIFICATIONS(false→true) were temporarily granted; Wi-Fi/mobile data1→0. After verified formal baseline restoration, Usage returned todefault, POST false with original permission flags, Wi-Fi/mobile data1/1; readback verified. DND access and Overlay stayed absent/default. Runtime checks found no Mirra monitoring FGS/intervention notification, and exact restored baseline has no Active Intent/Session/PENDING. Only the new owned AVD was then normally stopped with userdata preserved; shared ADB and phone were not reset. A separately hash-verified host copy of the private UI baseline remains local, not in Git.

## Remaining boundary

Complete unfiltered Android/connected run, clean installed note/page/finish/lifecycle journey, clean Mine/Trends screenshots,320/360/411dp and fontScale1/2 execution of this final build: **NOT RUN/unfinished**. B's existing semantic tests were ported and compile; that is not current native PASS. Actual long-list500-note sheet performance was not measured (existing eager composition note retained). API23–36/full OEM/physical matrix/TalkBack/Release/Play/real radio/power-loss/manual clock changes: **NOT RUN**.

Next work is only the missing Android gates in a genuinely healthy isolated environment. Do not restart 05E-1/V2, change frozen product rules, downgrade Room/signing or weaken assertions to close this gate. Raw logs, XML, device identifiers, private packages/backups/database/media are local-only. This folder contains minimal synthetic screenshots and sanitized summaries.
