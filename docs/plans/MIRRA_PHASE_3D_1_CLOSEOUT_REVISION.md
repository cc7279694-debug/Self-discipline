# Phase 3D-1 — Authorized closeout revision

Date: 2026-10-07. Scope: 3D-1 only, based on actual HEAD `c39f135e09d48e27573dbb331c0bf57a9e075499` (includes the previously completed brand commit).

## Authority and contract

The user adopted the new Durable Session Closeout task. Attachment SHA-256: `88C2656831DA6DFE4F2F3C1F67A575A1789E51B329D472CA4DC54564AF154ABC`. This revision supersedes **only** the old first-tap flush and A/B cleanup ordering in the original 3D-1 plan/design. Existing later implementations and historical acceptance records remain intact; no new 3D-2/3/4 implementation is authorized.

Goal: a cancellable editable confirmation, followed by a successfully saved Note, one durable irreversible end boundary, owned runtime cleanup, and idempotent normal settlement.

## Implementation steps

1. First end tap checks current durable eligibility and opens the dialog using the persisted page; it does not force a save, retry a page write, sample the end clock, or end the Session. Normal 500ms autosave remains independent.
2. Final confirmation synchronously gates duplicates/editing, awaits real pending save outcomes, saves the latest Note under the existing draft ID, and only then repairs any ordinary unsaved reading-page input. The dialog's final page is never sent to `updatePage`.
3. Revalidate the latest persisted progress and known total pages after flush. Note/page failure retains ACTIVE, the draft, and temporary end-page input; no closeout clock or end fact is generated.
4. Sample the existing clock once after successful flush. The existing controller serial boundary settles monitoring, executes Room Stage A, then invalidates only this Session's memory ownership.
5. Release the existing facts mutex. Finitely attempt owned intervention/monitor/DND cleanup, then execute Stage B outside the facts mutex. PENDING already guards all learning writes and retains the occupied slot until B. Cleanup failure does not hide or prevent NORMAL/COMPLETED; cancellation still propagates after conservative durable compensation.
6. Reuse PENDING retry/startup recovery, the frozen wall-clock rollback proof, and the existing Summary route. No second coordinator, lock, note system, or cleanup framework.

## Guardrails

Room v4 and schemas 1–4/identity/Migrations unchanged. DND ownership, READY, FULL/PARTIAL/NONE, Usage/FGS implementation, 3C thresholds and actions, Phase 2 analytics, effective formulas and ReadingRecord unchanged. Controller change is documentation-only. Brand resources are retained unchanged.

The existing coroutine timeouts bound cooperative suspend operations; they cannot guarantee preemption of a synchronous Android Binder call. Owned cleanup remains best-effort and failures retain existing owner metadata/reconciliation.

## Verification / handoff

Test-first expectations cover confirmation versus final flush, Note failure and unchanged temporary page, post-save clock, pending saves/page retries, latest-progress validation, duplicate confirmation, A/cleanup/B ordering, independent cleanup failure/timeout, cancellation, B retry, and real Room/controller late-writer protection.

Run full JVM, full dedicated API37 connected, lintDebug and assembleDebug. Report assertion execution separately from opt-in or permission assumptions. No permission widening, physical-device operation, data reset, schema generation, main merge or Phase 4. Commit and push only the new feature branch; stop for independent 3D-1 review.

## Independent Acceptance

On 2026-10-07 the user completed independent review: **PASS WITH NOTES**. This **Phase 3D-1 Closeout Revision** is now **accepted / frozen**. Accepted implementation HEAD: `085543ffd0b5d03859565577a8ee277036fc50f9`, on `codex/phase-3d-closeout-v2`.

The implementation steps and guardrails above are frozen, including Note-save failure retaining ACTIVE, the unique `closeoutStartedAt`, irreversible PENDING, complete/retry without resampling, PENDING-first normal startup settlement versus legacy ACTIVE abnormal recovery, no new learning facts after PENDING, and cleanup failure never undoing the ended fact. Room remains v4 with no new Schema/Migration.

This accepts only the limited revision, not a new whole-Phase-3D freeze. Historical overall freeze `096af8e5e943b8efbca8aa47b10ab2b7d2f53e18` remains preserved; existing 3D-2/3/4 implementations are inherited, not rerun or reimplemented.

Evidence retains JVM 327/327 PASS; connected 294 discovered / 285 actual PASS / 9 unmet assumptions / 0 actual business assertion failures, **not 294/294 PASS**; lint/build PASS. Manual AVD smoke remains DEGRADED under Launcher/SystemUI ANR. Old installed-data preservation is unverified after framework package removal; later 17 unchanged hashes only prove current-file preservation for that cover install. physical/OEM, API23–36, TalkBack and release/Play remain NOT RUN. Full limitations and failure history are preserved in the [revision checkpoint](../checkpoints/2026-10-07-phase-3d-1-closeout-revision.md#independent-acceptance-and-limited-freeze).

The verification/handoff instructions above describe the completed implementation round, not a request to rerun it during this documentation freeze. No Gradle/AVD rerun or production/test change is part of this acceptance synchronization. Stop here; do not re-enter 3D-2/3/4 or Phase 4.
