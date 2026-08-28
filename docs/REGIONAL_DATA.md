# Regional raw GIS data

## Delivered boundary

ATX Plan Android can plan a small WGS 84 envelope and acquire raw files from a fixed source catalog. The first catalog contains:

- Copernicus DEM GLO-30 Public 2021, stored and labeled as a digital surface model (DSM);
- ESA WorldCover 10 m 2021 v200 categorical land cover; and
- an experimental, user-triggered OpenStreetMap `building` and `building:part` way snapshot from the public Overpass service.

This capability is an acquisition and processing foundation. It is not yet a terrain, clutter, building-height, propagation, or coverage engine. Downloaded rasters are not silently substituted into an RF result.

Copernicus GLO-30 can contain vegetation and buildings. It is not a bare-earth DTM. WorldCover classes are source observations and are not RF-loss coefficients. OpenStreetMap footprints can be incomplete or inaccurate and the public Overpass endpoint has no application service-level agreement.

## Planning limits

The planner rejects non-finite coordinates, antimeridian crossings, empty selections, arbitrary URLs, unsafe paths, and envelopes wider or taller than 1 degree. East and north edges are half-open so a request ending exactly at a tile boundary does not fetch the adjacent tile.

The default mobile batch budget is 384 MiB. It is evaluated before a transfer, together with the actual free-space check. Copernicus uses deterministic 1-degree tiles and WorldCover uses deterministic 3-degree tiles.

Experimental building requests are opt-in and additionally limited to 0.05 degrees per axis, 25 km2, and a 16 MiB raw response. Query version `osm-building-and-part-ways-bbox-v1` asks for a ways-only union of `building` and `building:part`; it does not request multipolygon relations. The processor retains bounded raw height, level, roof, minimum-height, and roof-shape tags without parsing units or deriving a height. Relations, holes, interpreted building heights, addresses, and completeness are not promised by this source adapter.

## Transfer and cache behavior

Only fixed HTTPS provider hosts are accepted. The initial URL and every resolved redirect target are each bounded to 2,048 characters before use. The experimental building query is pinned to the documented `lambert.openstreetmap.de` public Overpass server instead of depending on round-robin endpoint selection. Redirects are handled manually; a cross-origin target is rejected before the redirected request is opened. The actual final bounded same-origin HTTPS URL propagates to processing output, result, and inventory rather than being replaced by the configured endpoint. Requests use an identifying ATX Plan user agent.

GET downloads stream into private `.part` files with a per-source byte ceiling. A partial GET is resumed only with a strong ETag, a consistent `206 Content-Range`, and strict bounded metadata whose saved effective URL remains on the original HTTPS origin. Incomplete metadata cannot claim an acquisition time; completed metadata requires a bounded total byte count and a syntactically valid nonfuture UTC completion timestamp. Invalid metadata and staging are discarded rather than resumed. A server that returns a full `200` response causes a safe restart instead of appending unrelated bytes. Transient connection, TLS, body-read, and selected HTTP failures receive at most three total GET attempts. The fixed read-only building query receives at most two total POST attempts, and its staging file is discarded before every replay; POST responses are never resumed. `Retry-After` is accepted only when it resolves to 1-30 seconds and remains inside that attempt budget; HTTP 429 without a valid bounded value is not replayed. Cancellation, security rejection, malformed ranges, ordinary client errors, size violations, storage failures, and processing failures are not retried.

Completed raw files receive a local SHA-256 digest and are promoted atomically inside the app-owned no-backup dataset root. The providers do not publish a trusted SHA-256 for every raster tile, so this digest proves subsequent local cache integrity; it is not presented as proof of upstream authenticity. ETags are HTTP validators and are not treated as content hashes.

The delivered path-keyed schema-2 inventory nests an acquisition-time source snapshot with dataset family/release, data type, file format, catalog revision, query/normalizer versions, source license, attribution, provenance, limitations, and cache policy. Each record also carries the requested URL, actual effective URL when known, route/policy version, local acquisition time when known, bounds, bytes, local SHA-256, HTTP validators, processing state/output, notes, and errors. Any effective URL must be bounded same-origin HTTPS. Acquisition, check, and inventory-update timestamps use the exact non-lenient UTC millisecond form `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`. Completed `READY`/`EXISTING` provenance requires its effective URL, valid acquisition timestamp, byte count, and SHA-256 together; legacy schema-1 migration is the documented case that may retain unknown effective URL/time without inventing them. Inventory reads and writes are bounded and atomic.

All acquisition and inventory-load operations in `FileRegionalDatasetRepository` share one application-wide mutex across repository instances, so two instances in the same app process cannot race the inventory or staging lifecycle. This serialization does not coordinate another process and is not a multi-process locking policy.

A bounded schema-1 reader migrates a valid primary or atomic-backup inventory offline and atomically rewrites it as schema 2. A valid backup also replaces an invalid primary during recovery. Stored URL, source/license/attribution/provenance, bounds, hashes, validators, processing, and status fields are preserved structurally. Because schema 1 did not distinguish the final endpoint or acquisition time, migrated `effectiveUrl` and `acquiredAt` remain unknown instead of being invented. Other fields absent from schema 1 are reconstructed through the known fixed-catalog mapping and are not claimed as complete historical acquisition metadata. Migration does not generate a missing TIFF index or GeoJSON; it preserves the legacy processing record. The inventory still has one record per relative path and does not contain an exact request body or plan fingerprint. Those fields now exist in the separate, not-yet-wired job-contract foundation; append-only content-addressed snapshots and pins are not delivered.

## CPU processing

GeoTIFF and BigTIFF inputs pass a bounded random-access metadata indexer. It validates the signature and safe IFD ranges, dimensions, bands, sample width and format, compression, bounded georeferencing tags, CRS keys, and `NoData` text when present. The derived metadata record explicitly states that raster samples were not decoded and that Cloud Optimized GeoTIFF layout was not proven. A future raster adapter must add tested window reads, reprojection, mosaicking, and `NoData` behavior before an engineering model can consume these files.

The experimental building processor validates a bounded UTF-8 Overpass JSON response, coordinates, element counts, features, vertices, raw tag lengths, and `osm3s.timestamp_osm_base` when supplied. It then atomically publishes deterministic WGS 84 GeoJSON with the raw-response hash, exact query, actual final endpoint, local acquisition time, upstream timestamp, bounded raw building/height/level/roof tags, attribution, counts, and limitations. Unsupported or unclosed geometry is counted rather than invented; no retained tag is interpreted as a trusted height. Processing is bounded but currently materializes the response/string/JSON tree and geometry in memory rather than using a streaming parser, so the 16 MiB request cap remains a material mobile heap limit.

## Lifecycle limitation

Acquisition currently runs in the regional-data ViewModel on an IO dispatcher. The app must remain open while a transfer is active. A bounded GET partial can resume after a later user action, but the Data screen does not create or observe a durable regional job and this slice does not claim a WorkManager or UIDT job that survives process death. Durable background scheduling, network constraints, user-visible Android notifications, shared-runner execution, process/reboot recovery, provider-specific load-shedding policy, cache ownership, and garbage collection remain release work.

## Delivered durable-job foundation

The non-executing `MOB-027A` foundation provides a passive `RegionalCanonicalPlanV1`. Request and coverage coordinates are normalized to integer microdegrees with six decimal places, half-even rounding, and negative-zero removal. Stable ordering and recursively key-sorted canonical JSON produce two SHA-256 identities:

- a semantic fingerprint for portable dataset meaning, bounds, tile/query identity, source format, snapshot policy, and transport-independent query/normalizer versions; and
- an Android execution fingerprint that additionally binds the fixed catalog, dataset/license IDs, route/endpoint and exact HTTP body contract, cache choice and maximum age, logical paths, resource profile, byte limits, and exact license snapshots.

The normalized reason is retained in the passive plan but excluded from both identities. Routing and force-refresh changes affect the execution identity without changing semantic dataset identity. Neither identity hashes acquired bytes; raw and derived SHA-256 remain separate.

`RegionalJobRecordV1` binds the passive plan, both fingerprints, exact accepted-license snapshots/times, scheduler kind/generation/identity, strict state, cumulative retry bytes, provider-specific attempt ceilings, monotonic checkpoint promotion, committed per-artifact inventory-entry references, timestamps, cancellation intent, and a typed terminal problem. A record rejects a checkpoint beyond its current artifact, and a revision may introduce a checkpoint only for the previously current artifact. `SUCCEEDED` requires one committed outcome reference for every artifact, and terminal records are immutable. `FileRegionalJobRepository` can store at most 64 strict UTF-8 JSON records of at most 256 KiB each in `noBackupFilesDir/datasets/regional/jobs`, one UUID-named Android `AtomicFile` per job. Writes are synced and read back, updates use an exact one-revision compare-and-set, and repository instances share an in-process mutex. Invalid/future/oversized records are preserved and listed as unreadable without hiding valid peers; new ownership fails closed until unreadable records are reconciled. Active plans with an overlapping logical artifact path are also rejected. No deletion/retention or multi-process policy is delivered.

The pure `RegionalJobReconciler` compares these records and preserved unreadable job IDs with an abstract scheduler snapshot plus checkpoint and inventory-outcome validators. The outcome validator receives the owning job record, the matching canonical artifact, and the committed outcome rather than validating digest text without context. Terminal outcomes are audited too; an invalid one produces a guarded non-mutating `REPORT_TERMINAL_OUTCOME_INVALID` action with typed problem code `terminal-artifact-outcome-invalid` because terminal job state remains immutable. A complete snapshot is bounded to 128 concrete scheduler entries and may include stale and current targets for one job; reconciliation deterministically selects at most one matching current target and emits cancellation for every extra. A physical `(scheduler kind, scheduler identity)` target must be unique across the snapshot, even across job IDs or generations; the same identity text may exist in different scheduler namespaces. A scheduler entry whose job ID belongs to an unreadable record is preserved and fails closed instead of being mislabeled recordless. Every record-derived action binds expected revision, execution fingerprint, and expected record scheduler generation as stale-decision guards. Scheduler-entry cancellation/adoption separately names the concrete target scheduler kind, generation, and identity, so a stale target generation is not confused with the record guard. A cancel for a genuinely recordless entry carries `expectedRecordAbsent`; the future executor must atomically re-read the store and confirm absence before the external scheduler effect. Recoverable work normally first persists a new enqueue generation before a later enqueue decision, but a missing target at the maximum bounded generation yields a guarded `MARK_ORPHANED` action with problem code `scheduler-generation-exhausted`. The reconciler performs no Android scheduling, file transfer, state mutation, notification, or process recovery itself and never infers success.

## Delivered cache and refresh behavior

The shared spatial, identity, provenance, `NoData`, refresh, cache, raster-processing, and interoperability rules are specified in the [cross-platform regional data contract](CROSS_PLATFORM_DATA_CONTRACT.md). The Android lifecycle decision is recorded in [ADR 0001](adr/0001-android-regional-data-lifecycle.md).

An immutable raster release is reused after path, identity, size, and SHA-256 verification. A live OSM snapshot is reused only when the same checks pass, its query/normalizer versions match, and `acquiredAt` is no more than 24 hours old. Starting a new explicit acquisition refreshes stale data. The **Refresh the live snapshot now** checkbox bypasses a fresh cache entry. There is no timer, polling, or background refresh of public Overpass, and the current path-keyed inventory retains only the latest successful logical-path snapshot. A failed refresh leaves the previous valid record on disk but reports failure; it is not exposed as a successful stale-data fallback.

## Approved execution target, not yet delivered

The selected execution lifecycle persists the delivered bounded job record before enqueue, executes one artifact at a time through a shared runner, and assigns provider retries to that runner alone. Long user-triggered work targets Android API 34+ [user-initiated data transfer jobs](https://developer.android.com/develop/background-work/background-tasks/uidt); API 23-33 targets a constrained foreground [WorkManager long-running worker](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running). Both adapters require visible progress, notification cancellation, checkpoint validation, and process-return reconciliation. No scheduler adapter, shared runner, service, worker, permission, notification channel, Data-screen observer, or actual process recovery is implemented, so execution remains **planned**.

An append-only content-addressed regional snapshot index, project/study/job pins, historical live-snapshot retention, ownership, and reference-aware cleanup are also **planned**. The delivered schema-2 migration must not be described as delivering those lifecycle capabilities.

Raster engineering remains **planned**. The next decoder reads only validated intersecting COG blocks on the CPU, publishes a content-addressed cropped grid with explicit `NoData` and source hashes, and then exposes separately versioned DSM and categorical land-cover adapters. Copernicus remains a DSM, WorldCover remains categorical input rather than RF loss, and native `.rp3` import remains blocked. A portable, capability-negotiated ATX import package through Android's [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider) precedes any native `.rp3` parser.

## Earlier bounded runtime evidence

On August 27, 2026, before the current `building`/`building:part` union and schema-2 additions, one live API 36 emulator run at system font scale 1.30 requested only `building` ways for `-23.562000,-46.656000,-23.560000,-46.654000`. The pinned Overpass server returned 63,744 raw bytes. ATX Plan recorded raw SHA-256 `87f7ed8ee04adffbb241957e64ef2cbf465011be7d3402b3c2839edb2d6a10a7` and published a 27,261-byte GeoJSON with 42 features, 778 vertices, zero omitted inner rings, and zero unsupported elements. The processed SHA-256 was `894c0574af9775d5f66b04b04e19cb0b11d3726d19a42cb339509462532596da`. A force-stop and relaunch reopened that earlier inventory and derived-output record from private storage.

This is evidence for one bounded transfer, processing, integrity, and reopen path. It is not an availability guarantee, an OSM completeness assessment, a broad device matrix, or evidence that the footprints are suitable for RF engineering.

## Source terms

Users review and accept every source license represented in a plan before starting acquisition. Cached or derived data must retain the applicable attribution and downstream obligations:

- Copernicus DEM License for GLO-30;
- Creative Commons Attribution 4.0 for ESA WorldCover; and
- Open Database License 1.0 and OpenStreetMap attribution for the experimental building snapshot.

Provider availability, licensing, and service policy must be revalidated before a production release. Public Overpass is suitable only for occasional tiny, user-triggered requests; recurring or guaranteed building acquisition requires a self-hosted or contracted service, or a separately verified regional preprocessing pipeline.
