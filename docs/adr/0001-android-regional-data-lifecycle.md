# ADR 0001: Android regional-data lifecycle

- **Status:** Accepted target architecture; job-contract/store/reconciliation/shared-runner and API 23-33 foreground WorkManager foundations are **Delivered**, while API 34+ UIDT, production UI/reconciliation wiring, and process recovery are **Planned**
- **Date:** 2026-08-27
- **Decision owners:** ATX Plan Android maintainers
- **Scope:** Long-running, user-triggered regional data acquisition and bounded CPU processing on API 23-36+

## Context

The delivered user-facing regional flow is owned by `RegionalDataViewModel`. It runs on an I/O dispatcher, serializes acquisition and inventory loads application-wide across `FileRegionalDatasetRepository` instances through one in-process mutex, supports cooperative cancellation, can resume an eligible strong-ETag GET partial after a later action, and writes the delivered path-keyed inventory schema 2. Resume metadata and completed schema-2 provenance require bounded same-origin HTTPS endpoints and valid completion fields/timestamps, while transport independently bounds initial and resolved redirect URLs to 2,048 characters. A verified live OSM snapshot may be reused for at most 24 hours or bypassed by an explicit force-refresh choice. The mutex is not multi-process coordination. The UI flow does not survive process death and creates no durable job record or operating-system job. It has no user-reachable foreground notification, scheduler-managed network/storage constraints, reboot recovery, append-only snapshot history, pins, or cache cleanup policy. Separate scheduler-neutral runner and API 23-33 WorkManager foundations now exist, but the screen and application composition root do not submit or observe them.

Regional jobs can transfer tens or hundreds of MiB and later decode raster blocks. They are initiated explicitly by a person, need visible progress and cancellation, and must preserve provenance and partial integrity. A second retry loop in a scheduler would multiply the repository's provider retries and could overload a public service.

Android provides a purpose-specific API for long transfers started by a user on Android 14/API 34 and later: [user-initiated data transfer jobs](https://developer.android.com/develop/background-work/background-tasks/uidt). Older supported releases need a durable fallback. Android documents WorkManager for [persistent work](https://developer.android.com/develop/background-work/background-tasks/persistent) and a foreground mode for [long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running).

This ADR selects the target lifecycle. `MOB-027A` delivers the passive canonical-plan contract, dual plan fingerprints, strict job state model, bounded private per-job store, and a pure reconciliation decision engine. `MOB-027B` delivers a scheduler-neutral shared runner for one previously persisted job, with exact scheduler-ownership checks, sequential artifact execution, provider-attempt permits before transport, durable cancellation/stop observation, and inventory-fingerprinted outcomes. `MOB-027C` delivers the API 23-33 WorkManager contract/adapter/worker, required foreground/notification/boot manifest foundations, a compact notification, and exact durable cancellation. These increments do not deliver a reconciliation executor, API 34+ UIDT, Data-screen wiring, notification-permission UX, late network-byte/checkpoint crash evidence, API 23/33 runtime proof, or actual process/reboot recovery.

## Decision

### 1. Scheduler by API level

For a long regional transfer explicitly started from the reviewed download screen:

- **API 34 and later:** schedule an Android user-initiated data transfer job through `JobScheduler`. Declare the required `RUN_USER_INITIATED_JOBS` permission and implement the notification contract described by the [UIDT guidance](https://developer.android.com/develop/background-work/background-tasks/uidt). Connected-network constraints also require `ACCESS_NETWORK_STATE`; a future decision to persist the scheduler entry across reboot requires `RECEIVE_BOOT_COMPLETED`. The job uses connected-network and storage-not-low requirements where supported.
- **API 23-33:** the delivered compatibility envelope enqueues a unique `CoroutineWorker` through WorkManager with connected-network and storage-not-low constraints. Before runner execution, it enters foreground mode with a visible notification, following Android's [long-running worker guidance](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running). The manifest explicitly declares and tests the target-SDK foreground-service type/permissions and notification behavior rather than relying on a transitive manifest assumption.
- **Short metadata verification or already-local processing:** may use ordinary constrained WorkManager work when it is not a long user-initiated transfer. It still reads the same persisted job contract.

The scheduler is an execution envelope, not the source of engineering truth. The delivered WorkManager worker invokes `RegionalJobRunner` and the job repository; the future UIDT adapter must do the same.

On API 34+, the implementation does not silently fall back to a direct foreground service when a UIDT job cannot be scheduled. It persists a typed scheduling failure and offers a user-visible retry. Any future fallback requires a new policy review.

### 2. Persist before enqueue

The delivered foundation defines and can atomically store a bounded `RegionalJobRecordV1` before either scheduler is called. The production screen does not create this record yet. Its implemented shape is:

```text
RegionalJobRecordV1
  schemaVersion
  jobId
  semanticFingerprintSha256 / planFingerprintSha256
  catalogRevision
  canonicalPlan
  acceptedLicenseSnapshots
  schedulerKind / schedulerGeneration / schedulerIdentity
  state
  currentArtifactIndex
  networkBytesTransferred / artifactAttemptCounts
  checkpointReferences
  artifactOutcomes
  createdAt / updatedAt
  cancelRequested
  terminalProblem
```

The record uses only bounded canonical values and safe relative/hash references. It does not embed a response or trust UI state. License acceptance is tied to the exact ordered license/catalog snapshot and acceptance time, not only a timeless license ID. The passive `RegionalCanonicalPlanV1` remains structurally readable without invoking current-catalog artifact constructors; compatibility with the installed catalog is a separate explicit check.

Coordinates are normalized to signed integer microdegrees with six decimal places and half-even rounding before tile, query, path, and fingerprint construction. Negative zero becomes zero. Canonical JSON recursively sorts object keys and uses a total deterministic artifact order. The semantic fingerprint binds portable dataset meaning, normalized bounds, tile/query identity, source format, snapshot policy, and transport-independent normalizer/query versions while excluding Android routing and the resource controls embedded in its Overpass body. The execution fingerprint includes that semantic fingerprint and additionally binds the exact Android catalog, dataset/license IDs, route/endpoint, HTTP body contract, cache choice and maximum age, relative paths, byte limits, resource profile, and license snapshots. Neither fingerprint is a downloaded-content digest.

`FileRegionalJobRepository` stores at most 64 strict UTF-8 JSON records of at most 256 KiB each under `noBackupFilesDir/datasets/regional/jobs`. Each UUID-named record uses Android `AtomicFile`, a synced write, readback verification, a process-wide mutex shared by repository instances, and compare-and-set updates that advance exactly one revision. Invalid, future-schema, oversized, or unknown-key records remain preserved and are reported as unreadable instead of hiding valid peers; new ownership fails closed while any record is unreadable. Active jobs cannot share a logical artifact path. A post-commit readback error is an indeterminate result that requires reload, not proof that the prior revision remained current. This is in-process serialization, not a multi-process locking policy or retention/cleanup policy.

The target end-to-end enqueue integration is idempotent by `jobId` and execution fingerprint:

1. persist `ENQUEUE_PENDING`;
2. enqueue unique scheduler work;
3. persist scheduler identity and `QUEUED`;
4. if interrupted active work has no scheduler entry, persist a new `ENQUEUE_PENDING` generation with compare-and-set before calling a scheduler again;
5. if the process dies between steps, reconciliation either finds the matching generation or produces a guarded enqueue decision.

For an already persisted exact `ENQUEUE_PENDING` generation, `RegionalWorkManagerScheduler` implements steps 2-3 with a deterministic UUIDv8, a strict plan-fingerprint tag, generation-scoped unique work plus `ExistingWorkPolicy.KEEP`, awaited enqueue/query, and identity recomputation from job/fingerprint/generation. Only active retained work receives revision compare-and-set acknowledgement. It can adopt the same UUID if the worker wins the race and advances the durable record first; a finished WorkSpec without that durable advancement yields a typed indeterminate result rather than `QUEUED`. Data-screen record creation and execution of pure reconciliation actions remain planned.

The delivered pure `RegionalJobReconciler` already produces deterministic actions for this policy: prioritize persisted cancellation, distinguish active and finished scheduler observations, adopt or enqueue a valid pending generation, validate required checkpoints and inventory outcomes, and mark incompatible records orphaned. A complete snapshot is bounded and may contain stale and current targets for the same job; the reconciler selects at most one matching current target deterministically and cancels every extra. It rejects reuse of one physical `(scheduler kind, scheduler identity)` target across generations or job IDs, while allowing the same identity text in different scheduler namespaces. A scheduler entry whose ID belongs to a preserved unreadable job record is not considered recordless and receives no cancellation action. A truly recordless cancellation carries `expectedRecordAbsent`; the future executor must atomically re-read the job store and confirm that no readable or unreadable record owns the ID before touching the scheduler. Record-derived actions carry expected revision, execution fingerprint, and expected record scheduler generation so an executor can reject stale decisions. Cancel/adopt actions separately carry the concrete target scheduler kind, plan fingerprint, generation, and identity, including when the target generation differs from the guarded record generation. A cancellation snapshot emits only exact scheduler-cancel actions; it cannot emit `MARK_CANCELED` without later runner or drain-aware executor evidence. Terminal outcomes are audited contextually; an invalid committed terminal outcome yields guarded non-mutating `REPORT_TERMINAL_OUTCOME_INVALID` with problem code `terminal-artifact-outcome-invalid` rather than rewriting immutable state. A recoverable record with no scheduler target at the maximum generation yields guarded `MARK_ORPHANED` with problem code `scheduler-generation-exhausted`. The reconciler returns no actions when scheduler state is unavailable and never infers success. The WorkManager adapter supplies strict bounded snapshot and exact enqueue/cancel primitives, but no reconciliation executor currently invokes the pure engine or performs its full guarded action sequence.

### 3. One retry owner

The delivered `FileRegionalDatasetRepository` remains the only owner of provider retry policy. The delivered `RegionalJobRunner` persists each provider-attempt permit before entering the repository transport boundary, passes only the remaining durable attempt allowance, and does not create a second retry loop. These bounded rules remain:

- immutable GET: at most three total transfer attempts, resuming only with the same approved endpoint and a matching strong ETag plus exact `Content-Range`;
- replay-safe read-only Overpass POST: at most two total attempts, with clean staging for every replay;
- security, certificate, malformed range, oversize, storage, validation, and processing failures: no automatic provider retry;
- explicit user retry creates or resumes a durable job action and is not hidden inside UI recomposition.

The delivered WorkManager worker returns a terminal result after the repository exhausts its provider policy and the runner maps the outcome; it never calls `Result.retry()` for the same provider failure. A future UIDT wrapper must not add another provider attempt loop. Operating-system interruption/rescheduling must retain the same persisted provider-attempt count and validate any checkpoint rather than resetting either value.

The current repository honors a syntactically valid `Retry-After` only when it resolves to 1-30 seconds and only inside the existing attempt budget. HTTP 429 without a valid bounded value is not replayed. Cross-origin redirect targets are rejected before a redirected request is opened. Scheduler adapters must delegate these decisions instead of retrying them again. Public Overpass is never polled in the background.

### 4. One artifact at a time

The delivered runner transfers and processes one artifact at a time. This is an intentional difference from desktop worker concurrency. It bounds simultaneous network sockets, staging space, decompression memory, CPU, battery, and thermal load.

The delivered runner checks cancellation and durable state itself and through the repository cancellation boundary:

- before opening a connection;
- between streaming reads;
- before and after retry backoff;
- before and after the current synchronous artifact processor call;
- before raw/derived promotion;
- after the repository returns and before the runner links an inventory outcome to the job.

Fine-grained cancellation between raster blocks or vector features is planned; the current processor interface has no cancellation probe inside one call. An eligible completed strong-ETag GET staging file can be retained when later processing is interrupted, while replay-safe POST staging is discarded under the existing resume policy. Publication remains staged, synced, hash-verified, and atomic.

### 5. Notification and user control

The complete target contract gives every active long job a user-visible notification containing:

- dataset/source title and bounded region summary;
- current artifact and aggregate byte progress when known;
- queued, downloading, verifying, processing, or paused state;
- a cancel action routed to the durable job record and scheduler;
- a content action that opens the Data screen for that `jobId`.

The delivered WorkManager subset uses a stable low-importance channel and an always-nonzero notification ID derived from the physical WorkRequest UUID, bounded current-artifact progress, and a generic app content intent. Every progress update rechecks channel/permission visibility. The derived 31-bit ID is not a persisted collision-safe allocation; that registry remains release work before concurrent production submission. Its immutable explicit cancel action validates exact job/fingerprint/generation/UUID ownership, persists `cancelRequested` before asking WorkManager to cancel only that UUID, and retains a nonterminal cancellation-pending record after scheduler confirmation. WorkManager operation completion or a `CANCELLED` WorkInfo does not prove that execution drained; the runner or future reconciliation executor owns `CANCELED` terminalization after cooperative/drain evidence. An indeterminate storage or scheduler effect also retains durable cancellation intent. The delivered runner relies on the repository to retain only a valid resumable GET partial and discard POST staging. Region summary, aggregate byte progress, job-specific Data-screen navigation, and permission request/denial UX remain planned. The UI never treats dismissal of a notification as cancellation.

The release implementation must follow current Android notification requirements, including the [notification runtime permission guidance](https://developer.android.com/develop/ui/views/notifications/notification-permission), without claiming work is invisible when permission is denied.

### 6. Recovery and state machine

The delivered record validates these states and their allowed transitions:

```text
DRAFT
ENQUEUE_PENDING
QUEUED
RUNNING_DOWNLOAD
RUNNING_VERIFY
RUNNING_PROCESS
PAUSED_CONSTRAINT
SUCCEEDED
FAILED
CANCELED
ORPHANED
```

The delivered repository enforces immutable reviewed identity, exact single-revision compare-and-set updates, monotonic timestamps, one-artifact-at-a-time index/outcome progress, cumulative retry bytes, GET/POST attempt ceilings, checkpoint promotion, cancel intent, generation-scoped scheduler identity publication, and immutable terminal states. A checkpoint cannot refer to an artifact beyond the record's current artifact, and a mutation may introduce a checkpoint only for the previously current artifact. `SUCCEEDED` requires a bounded inventory-entry outcome reference for every artifact. The delivered runner and `RegionalJobInventoryOutcomeResolver` derive and later resolve an exact canonical inventory-entry fingerprint before an artifact advances; reconciliation passes each outcome together with its owning record and indexed canonical artifact to the validator, including during a terminal audit. If a terminal outcome no longer validates, the record remains immutable and only a guarded report action is emitted. API 34+ UIDT, the atomic reconciliation executor, Data-screen projection, and complete notification UX remain future work.

Provider-attempt permits are durably ordered before transport. Network-byte totals are persisted only after the bounded repository call returns, followed by verified-raw checkpoint and inventory-outcome linking. Focused runner tests cover CAS conflict recovery by adopting an already committed inventory result, but crash injection after accepted network bytes and during those late checkpoint/outcome writes remains **Planned**.

The planned app-start and Data-screen integration will invoke reconciliation as follows:

1. loads bounded job records;
2. asks the API-specific scheduler about known IDs where possible;
3. verifies partial/checkpoint metadata and current inventory commit points;
4. resumes or re-enqueues recoverable work exactly once;
5. marks inconsistent records `ORPHANED` with a safe user action;
6. never infers `SUCCEEDED` from a missing process, worker, or notification.

After reboot, a future persistent scheduler may invoke the runner again, but the runner must still reconstruct all work from the job record and validated checkpoint. Device/API tests must prove the selected scheduler behavior before release. The focused runner and pure reconciler tests are not process-death or reboot evidence.

### 7. Storage and cache boundary

The current bounded cache remains under `noBackupFilesDir/datasets/regional`; it is intentionally excluded from backup. The delivered job records use its `jobs/` child, and the WorkManager worker reconstructs and consumes both repositories from that private root. The Data screen and application-start reconciliation path are not wired to them. Android's [app-specific storage guidance](https://developer.android.com/training/data-storage/app-specific) remains the baseline.

A future larger dataset library may use a user-selected directory through the [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider). That path requires persisted URI permission, free-space/seekability checks, permission-loss recovery, and a `SeekableSource` abstraction over `ParcelFileDescriptor` or a private file. It is not delivered by this ADR.

The global regional cache remains separate from project artifact blobs. The delivered inventory schema 2 is path-keyed and has no pin table. A future content-addressed snapshot index adds pins for active jobs/projects/studies; only that later ownership layer can support reference-aware cleanup without silently removing a reproducibility input.

### 8. Inventory and snapshot behavior

The bounded inventory schema 2 and schema-1 migration described in [the cross-platform data contract](../CROSS_PLATFORM_DATA_CONTRACT.md) are delivered prerequisites:

- each new acquisition carries a nested source snapshot plus requested/effective URL, route policy, and local acquisition time;
- schema-1 migration preserves its stored source/license/provenance fields and leaves unavailable effective URL/acquisition time unknown;
- static released rasters reuse verified content;
- verified live OSM data is reused only within 24 hours unless the user forces refresh during a new explicit acquisition;
- endpoint, query version, source time when supplied, local acquisition time, retained raw building tags, and raw hash remain visible.

The delivered inventory still stores one current record per relative path. Append-only content identity, historical live snapshots, pins, durable job ownership, and reference-aware cleanup remain **planned**.

### 9. CPU processing

The delivered shared runner delegates the currently supported bounded CPU processing after download, one artifact at a time. Future TIFF raster decoding is block/window based; it must never materialize a whole WorldCover or Copernicus raster in memory or use GPU availability as a correctness requirement.

Unsupported source encoding is a typed failure, not a request to lower resolution or substitute a source. Derived outputs carry source hashes, `NoData`, processor version, dimensions, transform, and output hash before they become ready.

## Consequences

### Expected after complete product integration

- User-started transfers survive ordinary UI navigation and process loss through an Android-supported mechanism.
- API 34+ uses the operating-system category designed for long user-initiated transfers.
- API 23-33 retains a durable supported fallback.
- One runner keeps retry/load policy auditable and prevents multiplicative retries.
- Persist-before-enqueue and unique IDs make recovery deterministic.
- Sequential processing reduces storage and thermal peaks.
- Notifications and cancellation are tied to durable truth instead of ViewModel lifetime.

### Costs and limitations

- One additional UIDT scheduler adapter and API-level instrumented tests are required.
- UIDT permission/service/notification behavior adds manifest and release-policy work.
- WorkManager becomes a dependency for API 23-33 fallback.
- Inventory/job migrations and reconciliation add persistence complexity.
- The design does not create a DTM, raster sampler, building-height model, RF clutter model, or `.rp3` importer.
- System scheduling is not an availability guarantee; constraints and operating-system policy can delay work.

## Alternatives considered

### Keep ViewModel-owned coroutines

Rejected as the target. This is the delivered bounded implementation, but it cannot meet process-death, reboot, or durable-notification requirements.

### Use WorkManager on every API

Rejected for long user-started transfers on API 34+ because Android provides the more specific UIDT category. WorkManager remains the selected API 23-33 fallback and may handle short local maintenance work.

### Start a direct foreground service on every API

Rejected. It duplicates scheduler/recovery responsibilities, increases policy complexity, and does not remove the need for a durable job record.

### Copy desktop concurrent workers

Rejected for Android. Additional throughput does not justify multiplied memory, storage, battery, thermal, and provider load in the bounded mobile scope.

### Let both scheduler and repository retry

Rejected. Nested retries obscure attempt counts, can exceed provider policy, and make cancellation/recovery non-deterministic.

## Implementation sequence

1. **Delivered prerequisite:** inventory schema 2, bounded primary/atomic-backup schema-1 migration and recovery, strict same-origin effective/completion provenance, 2,048-character initial/redirect URL limits, application-wide in-process repository serialization, acquisition routing metadata, 24-hour live-cache reuse/force refresh, bounded `Retry-After`, and same-origin redirect enforcement. Multi-process coordination remains absent.
2. **Delivered MOB-027A foundation:** add dual canonical plan fingerprint fixtures, strict lifecycle/CAS validation, a bounded per-job atomic repository, and pure reconciliation tests. No UI path uses them yet.
3. **Delivered MOB-027B foundation:** extract scheduler-neutral orchestration into `RegionalJobRunner` without changing provider retry semantics or creating a second retry owner.
4. **Delivered MOB-027C foundation:** add the API 23-33 WorkManager adapter, fingerprint-bound deterministic identity, active-only retained acknowledgement, finished-race isolation, constraints, foreground `dataSync` worker/physical-request notification, exact cancel-pending action, and API 36 adapter integration evidence. No Data-screen or reconciliation executor submits it yet.
5. Add API 34+ UIDT `JobService`, permission, notification, and scheduler adapter.
6. Add late network-byte/checkpoint crash injection plus process-death, reboot, constraint, duplicate-enqueue, retry-owner, and storage tests.
7. Move the Data screen to observe job repository state; retain the current screen-bound path only until durable evidence is green.
8. Add CPU COG block checkpoints and the separately versioned content-addressed snapshot index/pins after lifecycle correctness is proven.

## Required verification

| Layer | Required evidence |
|---|---|
| Pure JVM | **Foundations delivered:** normalized canonical plan, semantic/execution golden fingerprints, state machine, job IDs, monotonic attempt/progress/cancel state, strict CAS, pure reconciliation, checkpoint decisions, atomic-failure preservation, corrupt/future/unknown/oversized record isolation, exact inventory-entry fingerprint goldens, stale scheduler rejection before dataset access, sequential artifact execution, provider-attempt permits before transport, remaining-attempt clamping, durable cancellation/system stop, committed-inventory adoption after outcome CAS conflict, success only from complete inventory-backed outcomes, fingerprint-bound strict WorkManager input/tags, deterministic UUIDv8 recomputation, constraints, API/notification gates, active/finished retained identity, enqueue/acknowledgement races, bounded fingerprint-carrying snapshots, cross-plan reconciliation rejection, and persist-before-cancel ordering without premature terminalization. Job-schema migration remains future work when a second schema exists. |
| API 36 instrumentation | **Foundation delivered:** 8 worker/foreground/manifest/notification/strict-cancel cases, including real deterministic WorkManager `KEEP` deduplication and constraints metadata. This is adapter-logic evidence, not API 23/33 foreground-runtime evidence. |
| API 23/33 instrumentation | Foreground-service runtime, notification permission behavior where applicable, real constraint transitions, cancel, process termination/reopen, partial resume, no nested retry |
| API 34+ instrumentation | UIDT permission/service registration, enqueue, required notification, cancel, process termination/reopen, system stop/resume |
| Storage | Low-space preflight, full disk during staging, atomic last-valid recovery, late network-byte/checkpoint/outcome crash injection, SAF permission loss when that target is added |
| Network | Offline/online transition, 200 range reset, exact 206 resume, weak/changed ETag, bounded 408/425/429/5xx, redirect/security rejection |
| Processing | Cancellation between blocks, unsupported compression, malformed offsets, output hash/atomic promotion, raw preservation on processing failure |
| UI | Compact and high-font-scale job/progress/error/cancel/recovery states; no success inferred from a missing worker |

The runner and WorkManager JVM/API 36 suites prove shared-runner and adapter contracts, not a Data-screen production caller, reconciliation execution, API 23/33 foreground runtime, UIDT, notification-permission UX, late network-byte/checkpoint crash recovery, process death, reboot, or UI observation. The compatibility scheduler is a **Delivered foundation**, while end-to-end durable regional execution remains incomplete. Product text must continue to say that user-facing regional acquisition is screen-bound and requires the app to remain open.
