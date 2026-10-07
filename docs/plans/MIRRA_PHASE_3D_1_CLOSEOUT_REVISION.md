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
