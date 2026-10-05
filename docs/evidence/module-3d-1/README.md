# 3D-1 actual AVD evidence

Captured 2026-10-05 on dedicated Mirra_API_37, Android 17 / API 37, from the final Task 5 implementation. Only dedicated test book/Note data appear; these are actual emulator screenshots, not concept images or physical-phone results.

- `low-page-rejected.png`: current position 40; final page 35 explicitly rejected; no Session end.
- `final-confirmation.png`: final page edited to 42, before the unique end boundary is committed.
- `normal-summary.png`: unchanged Summary after normal closeout, 40–42 / two pages / one Note.
- `offline-reinstalled-start.png`: after cover installation and offline cold start, real position 42 and recent normal Session; no `currentPage + 1`.

Full JVM 264/264 and connected 228/228 are recorded in the 3D-1 checkpoint. Read-only database checks verified exact end-boundary equality, old-page Note independence and cover-install retention. Verbose logs, UI XML and database snapshots stay in ignored local build storage; no serial, private Note, full logcat or notification content is submitted.

These screenshots do not prove FULL monitoring, physical/OEM compatibility, TalkBack, release behavior, effective metrics or hardware crash recovery. Those unexecuted scopes remain NOT RUN.
