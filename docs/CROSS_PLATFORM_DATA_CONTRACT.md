# Cross-platform regional data contract

## 1. Purpose and status

This document defines the semantic boundary that ATX Plan desktop and ATX Plan Android must share for regional elevation, land-cover, and building data. It deliberately does not require both applications to use the same storage engine, downloader, raster library, scheduler, or user interface.

The contract prevents a file that happens to open on both platforms from being mistaken for an equivalent engineering input. Equivalence requires the same dataset identity, release, spatial convention, units, `NoData` behavior, processing version, provenance, and tested numerical interpretation.

Status terms in this document have exact meanings:

| Status | Meaning |
|---|---|
| **Delivered** | Executable Android behavior exists and has repository evidence. |
| **Foundation** | A bounded component exists, but it does not yet provide an engineering capability. |
| **Planned** | The design is selected here, but production code and completion evidence do not yet exist. |
| **Blocked** | Work must not be presented as available until the named legal, security, data, or validation gate is closed. |

Current Android status:

| Capability | Status | Boundary |
|---|---|---|
| Fixed-source regional planning and transfer | **Delivered bounded slice** | Half-open WGS 84 bounds, fixed HTTPS providers, byte budgets, resumable eligible GETs, bounded retries, hashes, atomic private inventory schema 2, and bounded live-snapshot cache control. |
| TIFF/BigTIFF handling | **Foundation** | Metadata is indexed; pixels, COG layout, mosaics, and engineering sampling are not delivered. |
| OSM building processing | **Foundation** | Tiny `building`/`building:part` way responses become attributed GeoJSON. Bounded raw height/level/roof tags and an upstream OSM timestamp are retained, but height interpretation, relations, holes, map use, and RF use are absent. |
| Canonical regional job contract and private store | **Foundation** | Passive microdegree plans, semantic and Android-execution SHA-256 identities, exact accepted-license snapshots, a strict lifecycle/CAS model, bounded per-job `AtomicFile` storage, pure reconciliation decisions, and a shared runner are delivered. They are not wired to the production Data screen. |
| Process-durable Android regional execution | **Foundation** | The shared runner and API 23-33 foreground WorkManager envelope, notification, and exact cancel action are delivered outside the user flow. Data-screen submission/observation, reconciliation execution, API 34+ UIDT, API 23/33 runtime proof, and tested process/reboot recovery remain absent; current acquisition remains screen-bound. |
| Inventory schema 2 and bounded schema-1 migration | **Delivered** | Records carry a nested source snapshot and requested/effective route provenance. A valid primary or atomic-backup schema-1 inventory is rewritten atomically as schema 2; a valid backup replaces an invalid primary. |
| Append-only content-addressed snapshot index and pins | **Planned** | The delivered inventory remains path-keyed and retains only the current snapshot for a logical path. |
| CPU raster window decoder and adapters | **Planned** | No raster sample currently enters a study. |
| Bare-earth DTM | **Blocked** | No approved Android bare-earth source/adapter is delivered. Copernicus GLO-30 is a DSM. |
| Native RadioPlanner `.rp3` import | **Blocked** | Legal/provenance, hostile-input, parser, and corpus gates remain open. |

Desktop behavior was inspected to derive the constraints below. This document does not claim that identified desktop differences have been changed.

## 2. What is shared and what remains platform-specific

Both products must share the following semantics through versioned schemas and golden fixtures:

- WGS 84 envelope and tile-edge convention;
- canonical dataset family, immutable release, source-data kind, and known aliases;
- CRS, horizontal and vertical units, pixel/grid convention, and `NoData` rules;
- immutable source/license snapshot and actual endpoint used;
- raw and derived SHA-256 digests;
- processing/query/adapter versions and effective parameters;
- fallback chain and the exact source used for every result;
- OSM element identity and the declared geometry/tag subset;
- error, readiness, staleness, and unsupported-capability meanings;
- portable numerical fixtures and tolerances.

The following are intentionally platform-specific:

- desktop threads/processes versus Android durable-job scheduling;
- Rasterio/GDAL versus a bounded Android CPU decoder;
- Windows application-data paths versus Android private or user-authorized storage;
- desktop table/dialog layout versus compact Compose UI;
- cache size, worker count, and thermal policy;
- file paths and content-URI handling;
- installation, notification, and operating-system recovery mechanics.

Cross-platform parity is asserted only for a named contract and fixture version. Similar screen labels or use of the same upstream provider are not parity evidence.

## 3. Spatial contract

### 3.1 Canonical envelope

`RegionalBounds` is a positive-area WGS 84 longitude/latitude envelope:

```text
west <= longitude < east
south <= latitude < north
```

Rules:

- coordinate order is always `west, south, east, north`;
- all values are finite decimal degrees;
- `west < east` and `south < north` are mandatory;
- longitude is within `[-180, 180]` and latitude within `[-90, 90]`;
- east and north are half-open edges;
- negative zero is normalized to positive zero before canonical serialization;
- an antimeridian-crossing request is represented by two explicit envelopes, never by `west > east`;
- tile selection uses mathematical floor and the half-open upper edge, not inclusive iteration over the north/east origin.

For tile step `s`, origins are:

```text
first = floor(minimum / s) * s
last  = (ceil(maximum / s) - 1) * s
```

This convention is already delivered on Android. The current desktop regional planner uses a different inclusive north/east iteration at exact tile boundaries; desktop parity remains **planned** until it adopts the contract and passes the shared fixtures.

### 3.2 Canonical coordinate serialization

The delivered passive job contract converts every plan and artifact-coverage bound to signed integer microdegrees (`E6`). Conversion uses locale-independent decimal arithmetic, six decimal places, and half-even rounding; `-0.0` therefore becomes integer `0`. The exact conversion is performed before canonical serialization, path/query identity, and both plan fingerprints. Differences smaller than one microdegree do not create different job identities. This normalization is a contract choice, not display rounding.

Canonical JSON recursively orders object keys and preserves the contract-defined ordering of arrays. Selections, artifacts, and license snapshots have explicit stable sort rules. OSM query version 1 formats the already normalized bounds to six decimal places and stores both the request body and its SHA-256 in the passive plan, so a future precision or query change requires a new contract/query version.

## 4. Canonical dataset identity

Identity separates the dataset family from a release and from a download endpoint:

```text
DatasetIdentity
  familyId
  releaseId
  dataKind
  adapterVersion
  catalogRevision
```

Target canonical identities:

| Family | Release | Data kind | Known current aliases |
|---|---|---|---|
| `copernicus-dem-glo30` | `2021` | `SURFACE_ELEVATION_DSM` | Android `copernicus-dem-glo30-2021`; desktop `copernicus-dem-glo30` |
| `esa-worldcover` | `2021-v200` | `CATEGORICAL_LAND_COVER` | Android `esa-worldcover-2021-v200`; desktop `esa-worldcover-2021` |
| `openstreetmap-buildings` | `live-snapshot` | `BUILDING_FOOTPRINTS` | Android experimental Overpass ID; desktop derived OSM GeoJSON |
| `radio-planner-st2` | source-specific | `TERRAIN_COMPATIBILITY_GRID` | Desktop compatibility cache only |
| `radio-planner-gct` | source-specific | `CLUTTER_COMPATIBILITY_GRID` | Desktop compatibility cache only |

Aliases exist for migration and import only. New records use the canonical family/release fields. A physical relative path is not a dataset identifier, and changing `landcover/` to `land-cover/` must not change semantic identity.

`catalogRevision` identifies the application catalog used to create a plan. It controls new acquisition. It must not retroactively rewrite or invalidate the immutable provenance snapshot of an already acquired artifact.

## 5. Source-specific semantics

### 5.1 Copernicus GLO-30

Copernicus GLO-30 is a digital surface model. It can include buildings and vegetation. It is not a bare-earth DTM and cannot silently satisfy an engine requirement declared as `BARE_EARTH_TERRAIN`.

The delivered Android slice downloads a complete one-degree COG and indexes metadata only. Local desktop corpus inspection found a representative Brazil tile with one 3,600 x 3,600 `float32` band, EPSG:4326, 1,024 x 1,024 DEFLATE-compressed blocks, floating-point predictor 3, and internal overviews. That observation guides Android fixtures; it is not a reason to skip per-file validation or to assume every future release has the same encoding.

The planned Android decoder must:

- require the declared Copernicus release and expected one-degree tile identity;
- verify EPSG:4326, one supported band, supported sample type, transform, block table, compression, predictor, and bounded offsets before allocation;
- decode only intersecting blocks on the CPU;
- preserve source `NoData`/invalid samples rather than converting them to zero;
- record the raw tile hash and decoder version in every derived grid;
- expose the result as surface elevation in metres, never as bare-earth terrain.

### 5.2 ESA WorldCover

ESA WorldCover 2021 v200 is categorical land cover, not a table of RF losses. A class adapter and an RF-loss model are separate versioned stages.

Local desktop corpus inspection found a representative tile with one 36,000 x 36,000 `uint8` band, EPSG:4326, `0` as `NoData`, 1,024 x 1,024 DEFLATE-compressed blocks, and internal overviews. Android must still validate each artifact.

The planned stages are:

1. decode source class values and `NoData` without interpretation;
2. map source values to a versioned ATX land-cover taxonomy;
3. only after independent engineering approval, apply a named frequency/environment-specific RF clutter model.

No study may claim clutter loss merely because a WorldCover tile is present.

### 5.3 OpenStreetMap buildings

OSM acquisition is a dynamic snapshot, not an immutable released raster. Its identity includes:

- adapter/query version;
- exact normalized bounds;
- exact query text and query SHA-256;
- configured endpoint instance and actual final HTTPS URL;
- local acquisition time and upstream source timestamp when supplied;
- raw response SHA-256;
- normalizer version and derived output SHA-256;
- geometry/tag capability flags.

The delivered Android query version `osm-building-and-part-ways-bbox-v1` is a ways-only union of `building` and `building:part`. It does not request multipolygon relations. The processor retains bounded raw `height`, `building:levels`, `roof:height`, `roof:levels`, `min_height`, `building:min_level`, and `roof:shape` values, but it does not parse units, derive a height, apply a fallback, or establish a height model. It also retains a valid Overpass `osm3s.timestamp_osm_base` when supplied. Relations and holes remain outside the delivered acquisition contract even though the parser contains defensive code for unsupported shapes.

Planned building contract versions must be additive and explicit:

| Version | Geometry/tag boundary | Status |
|---|---|---|
| `osm-building-and-part-ways-bbox-v1` | Closed `building` and `building:part` ways; bounded raw height/level/roof tags and upstream source timestamp retained; no relation, hole, or interpreted-height claim | **Delivered foundation on Android** |
| `osm-building-height-interpretation-v1` | Declared unit parsing, level-derived height, ambiguity handling, and a versioned fallback policy over retained raw tags | **Planned** |
| `osm-building-multipolygon-v1` | Relations, outer and inner rings, duplicate-member suppression, deterministic ordering | **Planned** |

Desktop currently queries a broader ways/parts/relations subset and applies explicit-height, level-derived, then default-height logic. Android must not claim equivalent building behavior until both platforms run the same versioned normalization and height fixtures.

Public Overpass has no ATX service-level agreement. Android keeps requests tiny, explicit, user-triggered, and bounded. Production recurring or guaranteed acquisition requires a self-hosted or contracted source. There is no silent endpoint failover inside one snapshot: a different endpoint is a new recorded attempt/source instance.

### 5.4 RadioPlanner ST2/GCT compatibility data

Desktop can read user-owned ST2/GCT caches for compatibility work. ST2 and GCT are not interchangeable with Copernicus DSM or WorldCover. Android access, distribution rights, acquisition, and portable packaging are unresolved; therefore native Android ST2/GCT use is **blocked** until legal/source and fixture gates close.

If later authorized, semantic compatibility requires the desktop golden rules, including ST2 projection/grid/endianness/`NoData`/triangular interpolation and GCT packed-class sampling. Missing data must remain `NoData` or use an explicitly selected, recorded fallback. Silent zero elevation is prohibited.

## 6. Provenance, integrity, and `NoData`

This section is the shared semantic target. The delivered Android schema-2 subset is called out below; fields that exist only in the reviewed plan or derived GeoJSON are not misrepresented as raw-inventory fields.

### 6.1 Immutable acquisition snapshot

Every raw artifact record preserves:

- canonical family and release plus legacy alias when migrated;
- data kind, catalog revision, adapter/query version;
- requested and coverage bounds;
- source reference, configured endpoint, actual final URL, HTTP method, and query hash/body reference;
- license ID/title/URL/attribution text as accepted at acquisition time;
- byte count, local SHA-256, ETag, Last-Modified, and acquisition time;
- upstream timestamp/checksum only when actually supplied;
- validation outcome and explicit limitations.

The local SHA-256 proves subsequent cache integrity. It is not described as upstream authenticity when the provider does not publish a trusted per-artifact checksum. ETags are HTTP validators, not content hashes.

Old records are validated structurally against their recorded catalog/query revision. They are not required to equal the current embedded endpoint, attribution text, or catalog object. Current catalog policy can mark an old artifact stale, revoked, or unsupported, but cannot erase its historical provenance.

### 6.2 Derived output

Every derived output adds:

- all input raw hashes in deterministic order;
- processor/adapter ID and semantic version;
- effective parameters and bounds;
- CRS, affine/grid convention, units, data type, and `NoData` representation;
- dimensions, valid/invalid counts, and coverage ratio;
- output byte count and SHA-256;
- capability and limitation flags.

A study references immutable hashes and effective algorithm versions, not a mutable cache path.

### 6.3 `NoData` rules

`NoData` is a first-class state:

- missing tile, missing pixel, invalid source value, unsupported decode, and outside-coverage are distinct reasons;
- `NoData` is never converted to elevation zero, land-cover class zero, or an assumed building;
- interpolation declares its required valid neighbors/weight and reports failure when the rule is not met;
- mosaics define deterministic precedence and retain source identity per contributing tile;
- a result records sample counts and coverage ratio;
- an engineering engine declares a minimum data-completeness gate before execution.

The desktop elevation implementation currently uses pixel-centre bilinear interpolation and requires at least 0.5 valid interpolation weight. Android parity with that adapter remains **planned** and requires shared golden samples.

## 7. Plan and fingerprint contract

Android now delivers `RegionalCanonicalPlanV1` as a passive, bounded plan representation plus two distinct canonical UTF-8 JSON identities. Passive means that a stored plan can be decoded structurally without constructing a current-catalog `RegionalArtifact`; current acquisition compatibility is checked separately by rebuilding the request through the installed fixed catalog.

The complete passive plan contains:

- contract schema version;
- catalog revision;
- E6 normalized request/coverage bounds and a trimmed NFC-normalized reason;
- ordered dataset family/release/adapter selections;
- ordered artifacts with immutable tile/query identity, method, limits, and coverage;
- exact ordered license snapshots;
- Android resource profile, without device-specific free-byte values.

The delivered `semanticFingerprintSha256` binds only portable meaning: normalized request and coverage bounds, canonical data kind, dataset family/release alias, file format, snapshot policy, tile identity, and transport-independent query and normalizer versions. The exact Overpass form body is execution-only because it also embeds Android timeout and response-size controls. The semantic identity deliberately excludes Android endpoint/route choices, HTTP encoding, cache-refresh choice, byte budgets, relative cache paths, license wording, reason, timestamps, and scheduler state.

The delivered Android execution `planFingerprintSha256` includes the semantic fingerprint and additionally binds catalog revision, Android resource profile, dataset/license IDs, requested endpoints/routes and route-policy versions, HTTP method/content type/request-body SHA-256, logical relative paths, cache policy and maximum cache age, optionality, artifact/batch byte limits, and exact license snapshots. It excludes progress, free-space observations, timestamps, scheduler identity, and job ID. Both payloads use recursively key-sorted canonical JSON, total artifact ordering, unique logical/semantic identities, and exact Android golden fixtures. Desktop execution of the semantic fixture is still required before cross-platform parity can be claimed.

Canonical hash input is compact UTF-8 without BOM, insignificant whitespace, or a trailing line ending; numbers are base-10 integers and coordinates are signed E6 integers, never floating-point JSON. The checked-in text fixture files use one final LF only as a repository container convention. Tests remove exactly that one convention byte, reject BOM/CR/additional payload whitespace, compare the remaining bytes to the generated canonical payload, and independently bind its lowercase SHA-256.

The execution fingerprint and logical artifact paths are used to reject conflicting active jobs and to support idempotent creation of the same job ID and exact plan. New ownership fails closed while any existing job record is unreadable. Neither fingerprint is a raw-content digest, an acquisition result, or a substitute for raw/derived SHA-256.

The repository reconstructs the plan from the current compatible catalog before starting a new job. A migrated historical record remains readable even if it can no longer be newly acquired.

## 8. Android execution profile

Android must preserve the shared semantics while applying stricter resource and lifecycle controls:

- fixed HTTPS hosts and provider-specific URL/query validation; the initial URL and each resolved redirect target are independently bounded to 2,048 characters;
- manual bounded redirects; cross-origin targets are rejected before the redirected request is opened;
- strong-ETag `If-Range` resume with exact `Content-Range` checks;
- provider `Retry-After` is honored only inside the existing attempt budget and only when it resolves to 1-30 seconds; HTTP 429 without a valid bounded value is not replayed;
- source-specific streaming ceilings and free-space preflight including staging, derived output, and safety allowance;
- one artifact transferred and processed at a time; Android repository instances share application-wide in-process serialization for acquisition and inventory access, without claiming multi-process locking;
- bounded buffers and block/window raster decoding, never whole-raster materialization;
- cooperative cancellation between network reads, decoded blocks, and publication stages;
- app-private no-backup storage for the current bounded cache;
- a future optional user-authorized dataset library through Android's [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider), with persisted URI permission and seekability checks;
- app-specific storage rules consistent with Android's [app-specific storage guidance](https://developer.android.com/training/data-storage/app-specific);
- no GPU dependency for the regional data path.

Desktop may use more worker concurrency and Rasterio/GDAL, but those differences do not change hashes, `NoData`, units, or adapter semantics.

## 9. Delivered inventory schema 2

Schema 2 and its bounded schema-1 migration are **delivered**. New records preserve source semantics and acquisition routing independently of later catalog wording:

```text
RegionalInventoryV2
  schemaVersion = 2
  artifactsByRelativePath
    RegionalInventoryRecord
      datasetId / relativePath
      requestedUrl / effectiveUrl?
      routeId / routePolicyVersion
      acquiredAt?
      sourceSnapshot
        datasetId / datasetFamily / datasetRelease
        catalogRevision / dataType / fileFormat
        queryVersion / normalizerVersion
        provider/source/license/provenance/limitations
        snapshotPolicy / maximumCacheAgeMillis?
      status / bytes / rawSha256 / HTTP validators
      checkedAt / bounds / processing / derived output
  updatedAt / lastBounds
```

For a schema-2 acquisition, `sourceSnapshot` is captured from the reviewed catalog entry, `requestedUrl` remains the planned request, `effectiveUrl` is the actual final bounded same-origin HTTPS URL, and `acquiredAt` is the local acquisition time. Acquisition, check, and inventory-update timestamps require the exact non-lenient UTC millisecond form `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`. Completed `READY`/`EXISTING` records require effective URL, valid acquisition timestamp, byte count, and SHA-256 as one provenance set. The effective URL also propagates to the processor, GeoJSON source object, result, and inventory. A reused entry retains its original acquisition metadata. Partial-transfer metadata applies the same bounded same-origin URL rule; an incomplete partial cannot carry acquisition time, while a completed partial requires its bounded total bytes and a valid nonfuture UTC completion timestamp before it can be reopened.

The delivered format is still path-keyed and represents only the current snapshot for each logical relative path. It stores `queryVersion` but not the exact HTTP method/body, query hash, or plan fingerprint in the raw inventory record; the exact Overpass query is retained separately in processed GeoJSON. It does not yet provide an append-only content-addressed snapshot index, project/study/job pins, reference-aware deletion, or historical live-snapshot retention. Those capabilities are **planned** and require a future versioned schema rather than an undocumented schema-2 shape change.

### 9.1 Migration from schema 1

The bounded schema-1 migration is delivered:

1. bound and strictly decode schema 1;
2. map a known legacy dataset ID to the bounded fixed catalog and mark catalog revision 1;
3. preserve the stored requested URL, source URL, license ID/URL/attribution, provenance, bounds, hashes, validators, processing record, status, notes, errors, and check time structurally;
4. populate family/release/data type/file format/query/normalizer/route fields from the known legacy catalog mapping;
5. keep `effectiveUrl` and `acquiredAt` null because schema 1 did not record those facts separately;
6. validate every relative path under the private root;
7. encode and sync staging, then atomically replace the primary inventory with schema 2.

Migration accepts the valid primary or the valid atomic backup, performs no network request, and does not rewrite or backfill raw/derived artifacts; it preserves the legacy processing state even when no processed output was recorded. When the primary is invalid and the backup is valid, the migrated backup atomically replaces the primary. It does not invent an effective endpoint or acquisition time. Fields that schema 1 never stored, including provider/title/version/CRS/limitations/query/normalizer/route/cache policy, are compatibility reconstruction from the known current mapping and are not claimed as a complete historical acquisition snapshot. Atomic replacement keeps the last authoritative inventory recoverable if commit fails. A migrated record remains structurally readable without requiring its stored license/provenance text to equal the current catalog.

## 10. Refresh, cache, and ownership policy

### 10.1 Immutable releases

Copernicus 2021 and WorldCover 2021 v200 artifacts are immutable-release cache entries. A valid hash-matched artifact is reused until the user removes it, storage corruption is detected, or catalog policy explicitly revokes it. A recheck never silently replaces bytes under the same content hash.

A version-scoped 404 may be recorded as `ABSENT_AT_SOURCE` with its endpoint/catalog revision. It is not converted to an all-zero tile and does not prove absence in another release.

### 10.2 Live OSM snapshots

The delivered OSM cache reuses an existing entry only when its path, dataset identity, family/release, query and normalizer versions, size, and SHA-256 verify and its `acquiredAt` age is within the fixed 24-hour maximum. A missing/invalid acquisition time is not fresh.

Starting a new explicit acquisition refreshes a stale snapshot. The **Refresh the live snapshot now** checkbox uses `LIVE_SNAPSHOT_FORCE_REFRESH` and bypasses even a verified fresh entry. There is no timer, polling, or background refresh of public Overpass. Because schema 2 is currently path-keyed, a successful refresh replaces the current logical-path snapshot; a failed refresh preserves the prior record on disk but returns failure rather than a successful stale-data fallback. Append-only history and pins are still planned.

### 10.3 Reference-aware cleanup

The regional cache remains separate from the project content-addressed artifact store. A future small hash-based pin table will link projects, studies, and active jobs to regional raw/derived hashes. Reference-aware cleanup and export materialization are **planned**; the delivered schema-2 inventory does not claim them.

## 11. Durable Android lifecycle

The selected target is detailed in [ADR 0001](adr/0001-android-regional-data-lifecycle.md). Contract/store/shared-runner and API 23-33 WorkManager foundations are delivered, while complete product integration remains planned:

- Android API 34+ uses a [user-initiated data transfer job](https://developer.android.com/develop/background-work/background-tasks/uidt) for a long transfer explicitly started by the user;
- Android API 23-33 has a delivered constrained foreground [WorkManager long-running worker](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running) compatibility envelope;
- the delivered foundation can persist the exact accepted plan and licenses before scheduler enqueue;
- one transfer runner owns provider retries; scheduler wrappers do not create a second retry loop;
- runner progress/checkpoints/terminal outcomes and cancel intent are persisted, while the delivered notification shows bounded current-artifact progress;
- process return reconciles persisted jobs with scheduler state and never infers success from a missing worker.

The contract/store portion is a delivered foundation. `RegionalJobRecordV1` validates scheduler kind/generation, exact reviewed inputs, GET/POST attempt ceilings, cumulative retry-byte bounds, monotonic checkpoint promotion, committed per-artifact inventory-entry references, monotonic cancellation, strict state transitions, and immutable terminal states. Checkpoint references cannot point beyond the current artifact, and a mutation can introduce a checkpoint only for the previously current artifact. `SUCCEEDED` requires one bounded committed outcome for every artifact; reconciliation supplies the owning record and matching canonical artifact to the outcome validator instead of trusting context-free digest text. `FileRegionalJobRepository` stores at most 64 UUID-named strict JSON records of at most 256 KiB each under private no-backup storage, using one `AtomicFile` per record, synced writes, readback verification, and single-revision compare-and-set updates. Unreadable records remain preserved and visible by ID while valid peers continue to load, but new artifact ownership fails closed until unreadable records are reconciled.

`RegionalJobReconciler` is a pure decision engine over persisted records, preserved unreadable record IDs, an abstract scheduler snapshot, checkpoint/inventory-outcome validation, and active versus finished scheduler observations. A bounded complete snapshot may contain stale and current scheduler targets for the same job, but it rejects reuse of one physical `(scheduler kind, scheduler identity)` target across generations or job IDs. The same identity text may appear in different scheduler namespaces. The reconciler deterministically selects at most one matching current target, emits cancellation for every extra target, never infers success, treats unavailable scheduler observation as a no-op, gives persisted cancellation priority, adopts/enqueues valid pending work, and marks catalog, scheduler-generation/identity, finished-without-result, outcome, or checkpoint inconsistencies orphaned. Persisted cancellation emits exact scheduler-cancel actions but never marks the record `CANCELED` from snapshot state alone. It preserves any scheduler entry whose job ID is unreadable rather than treating it as recordless. A truly recordless cancel carries `expectedRecordAbsent`; the future executor must atomically re-read the job store and confirm that no readable or unreadable record now owns the ID before the external effect. Every record-derived action carries expected record revision, execution fingerprint, and expected record scheduler generation as stale-decision guards; scheduler cancel/adopt actions separately carry the concrete target kind, plan fingerprint, generation, and identity. Committed outcomes are contextually audited even for terminal records. An invalid terminal outcome yields guarded non-mutating `REPORT_TERMINAL_OUTCOME_INVALID` with typed problem code `terminal-artifact-outcome-invalid`, preserving immutable terminal state. Interrupted active states first produce a persisted new enqueue generation, then enqueue on a later guarded decision; if the bounded generation is already exhausted, reconciliation emits `MARK_ORPHANED` with typed problem code `scheduler-generation-exhausted`. No production reconciliation executor invokes this engine.

`RegionalJobRunner` is the delivered scheduler-neutral execution boundary. The API 23-33 `RegionalJobWorker` reconstructs it from private storage, validates strict three-field input against its actual deterministic UUID, enters foreground `dataSync` mode, and delegates without adding a WorkManager retry loop. `RegionalWorkManagerScheduler` mirrors the plan fingerprint in strict tags, recomputes the UUID during observation, supplies connected-network/storage-not-low constraints and generation-scoped unique `KEEP`, acknowledges only active exact retained work, isolates finished-without-durable-advance races, and emits a fingerprint-carrying strict snapshot capped at 128 entries. The foreground notification derives its identity from the physical WorkRequest and rechecks visibility. The explicit immutable cancel action persists durable intent and cancels the exact UUID, but leaves a nonterminal record cancellation-pending because scheduler cancellation is not execution-drain proof. API 34+ UIDT, Data-screen submission/observation, reconciliation execution/drain completion, notification-permission UX, job-specific navigation, API 23/33 runtime proof, and actual process/reboot recovery are **planned**. The delivered `RegionalDataViewModel` path still requires the app process to remain alive.

## 12. CPU raster processing target

The Android raster path remains pure CPU unless a later, separately approved backend passes the same fixtures.

Implementation stages:

1. extend the bounded TIFF index to IFD chains, tile offsets/byte counts, predictor, planar configuration, and overview selection;
2. implement source-profile validation and DEFLATE block decoding with hard compressed/uncompressed limits;
3. implement floating-point predictor handling required by verified Copernicus fixtures;
4. read only blocks intersecting the requested crop/window;
5. publish a versioned, content-addressed cropped grid with explicit transform, type, valid mask/`NoData`, source hashes, and processor version;
6. add elevation and categorical land-cover sample adapters over the derived grid;
7. add mosaicking and interpolation only after cross-platform golden results pass;
8. connect an engineering study only after data-completeness and provenance gates pass.

Unsupported compression, predictor, CRS, rotated transform, sample format, or unsafe offset fails explicitly. The implementation does not fall back to decoding the entire image.

## 13. Portable project/data interchange and `.rp3`

Android first supports a versioned portable ATX import package whose declared producer is an ATX exporter. Every imported package remains untrusted: it is opened through SAF, copied through bounded private staging, hashed, structurally inspected, capability-negotiated, and imported as a copy with a conversion/loss report. Unknown capabilities are never discarded by a rewrite.

This portable contract precedes native `.rp3` parsing because desktop `.rp3` is a ZIP containing MS-NRBF records and can carry large graphs. Android must not use Java serialization, a .NET runtime, or an unbounded generic object decoder.

Native `.rp3` remains **blocked**. If later authorized, it requires a restricted streaming Kotlin ZIP/MS-NRBF parser, mobile resource limits, deterministic SHA-derived identities, cancellation, fuzz/hostile-file tests, and the approved desktop corpus summaries. The corpus itself is not bundled in the APK without explicit rights.

## 14. Phased implementation

| Phase | Status now | Android output | Exit evidence |
|---|---|---|---|
| D0a - plan identity fixtures | **Delivered foundation on Android** | Canonical E6 bounds plus semantic and Android-execution canonical JSON/SHA-256 fixtures | Android golden tests agree; a desktop semantic runner and broader identity/provenance/`NoData` fixtures remain. |
| D0b - broader semantic fixtures | **Planned** | Machine-readable provenance, OSM geometry, raster metadata/pixels, adapter, and `NoData` fixtures | Kotlin and desktop fixture runners agree; current divergences remain explicitly reported. |
| D1 - path-keyed inventory v2 | **Delivered bounded slice** | Nested source snapshots, family/release identity, requested/effective route provenance, acquisition time for new transfers, and atomic primary/backup v1 migration and recovery | JVM tests prove v1 rewrite, invalid-primary/valid-backup recovery, unknown legacy effective URL/time, offline reuse, v2 route propagation, and live-cache age/force behavior. |
| D1b - append-only snapshot index | **Planned** | Content-addressed historical snapshots, logical-to-hash index, pins, ownership, and reference-aware cleanup | Migration from delivered path-keyed v2, multi-snapshot retention, pin/release/recovery fixtures. |
| D2a - durable job contract/store | **Delivered foundation** | Passive canonical plan, dual fingerprints, exact license acceptance, strict state/CAS validation including future-artifact checkpoint rejection, bounded per-job atomic JSON, contextual terminal/nonterminal outcome auditing, and pure reconciliation actions with separate record guards/concrete scheduler targets, physical-target uniqueness, unreadable-ID preservation, guarded record absence, and typed generation exhaustion | JVM goldens/state/store/fault/reconciliation tests, including bounded stale/current target handling and non-mutating terminal audit decisions; no execution or process-recovery claim. |
| D2b - scheduled durable execution | **Delivered foundation / incomplete integration** | Shared runner plus API 23-33 foreground WorkManager fallback with fingerprint-bound deterministic generation identity, active-only retained acknowledgement, constraints, physical-request foreground notification, and exact durable cancel-pending semantics. API 34+ UIDT, Data-screen observation, reconciliation execution/drain completion, and recovery remain planned. | Existing JVM/API 36 adapter evidence must be extended with API 23/33 foreground runtime plus API 34+ UIDT selection, process-kill/reboot/constraint transitions, and no-duplicate-transfer/retry-owner proof. |
| D3 - CPU COG windows | **Planned** | Bounded block decoder and content-addressed crop grids for verified Copernicus/WorldCover profiles | Golden pixels, malformed offsets, compression/DEFLATE limits, memory/thermal benchmark, process recovery between blocks. |
| D4 - data adapters | **Planned** | DSM sampler and categorical WorldCover adapter with explicit `NoData`/provenance | Desktop/Android sample and edge fixtures pass; no DTM or RF-loss claim. |
| D5a - bounded building-way snapshots | **Delivered foundation** | Versioned `building`/`building:part` ways query, 24-hour verified reuse, force refresh, actual final URL, upstream timestamp, and bounded raw height/level/roof tags | JVM query/processor/cache/route tests and compact checkbox instrumentation; no geometry or height engineering claim. |
| D5b - historical/semantic building adapters | **Planned** | Append-only pinned snapshots, interpreted height policy, and multipolygon relations/holes only after a complete geometry contract | Cross-platform query/normalizer fixtures, duplicate/holes/unit/height cases, with live endpoint smoke treated as non-deterministic evidence. |
| D6 - terrain-aware study | **Planned/Blocked by DTM policy** | Dataset-backed profile and completeness gate | Independent numerical goldens and approved DSM-versus-DTM product policy. |
| D7 - portable ATX import | **Planned** | SAF import-copy with capability and loss report | Cross-platform round trip of approved subset and hostile-package suite. |
| D8 - native RP3 | **Blocked** | Restricted parser only if separately authorized | Legal/provenance approval, security review, corpus and fuzz gates. |

## 15. Cross-platform golden fixture matrix

Fixtures contain no secrets and use redistributable synthetic or separately approved minimal source excerpts.

| Fixture group | Required cases | Comparison |
|---|---|---|
| Bounds/tile math | Exact integer and 3-degree north/east edges, negative coordinates, zero-area rejection, antimeridian split | Exact ordered tile IDs and canonical JSON |
| Plan identities | Collection reordering, negative zero, sub-microdegree noise, semantic query/bounds change, Android route/cache change, exact licenses and limits | Byte-identical canonical semantic/execution JSON and SHA-256; routing changes execution only; desktop semantic runner remains planned |
| Identity/aliases | Current desktop and Android IDs/paths, new release, unknown alias | Exact family/release mapping or explicit unsupported result |
| Inventory migration | Delivered v1 ready-path rewrite and invalid-primary/valid-backup recovery, plus planned broader failed/processed and changed-catalog cases | Exact preserved stored fields; missing legacy effective URL/acquisition time stays unknown; no network; last valid inventory survives |
| HTTP resume | Strong/weak ETag, 200 reset, exact/wrong 206 range, oversize, redirects, 404, 408/425/429/5xx | Exact status, retained bytes, attempt count, and final hash |
| Copernicus metadata | Classic TIFF and BigTIFF, endian variants, unsafe IFD/count, verified source profile | Exact metadata or explicit rejection |
| Copernicus pixels | Block edges, overview/crop selection, predictor 3, finite and invalid samples | Float values within declared tolerance plus exact valid mask/source hash |
| Elevation sampling | Pixel centres, tile boundary, four-neighbor interpolation, <0.5 and >=0.5 valid weight | Same value/`NoData` reason and source set |
| WorldCover | All source class values, `0` `NoData`, block/tile edge, unknown value | Exact categorical result; no RF loss in output |
| OSM ways v1 | `building`/`building:part` union, closed/unclosed way, duplicate ID, invalid coordinate, inactive tag, source timestamp, actual endpoint, raw-tag bounds, output cap | Byte-identical canonical GeoJSON and counts; current delivered subset does not include relations/holes |
| OSM height interpretation v1 | metres, levels, roof height, malformed/ambiguous text, deterministic fallback | Planned exact height, unit, and source rule or explicit `NoData`; raw tag retention alone is not interpretation |
| OSM relations | joined/reversed segments, duplicate member, inner rings, incomplete relation | Exact polygons/holes or explicit unsupported count |
| ST2/GCT | projection boundaries, endianness/nibbles, `NoData`, triangular/pixel sampling | Blocked on Android until authorized; then exact/tolerance comparison |
| Portable ATX | supported, unknown-preserved, read-only, import-copy, corrupt/path traversal/bomb | Capability report, hashes, IDs, and no silent loss |
| RP3 summaries | Approved network/site/sector/CPE/pattern/layer counts and warnings | Blocked until native parser gate; never inferred from desktop-only success |

Future instrumentation must additionally cover API-level UIDT/WorkManager selection, API 23/33 foreground runtime, process death, reboot reconciliation, real network/storage constraint transitions, notification permission denial, job-specific navigation, SAF permission loss, low storage, and compact high-font-scale UI. Basic WorkManager identity/deduplication/constraints metadata and notification/cancel contracts already have API 36 integration evidence; that does not prove older-version runtime behavior. A live Overpass response is smoke evidence only; it cannot be a deterministic golden fixture.

## 16. Completion and claim rules

- A downloaded TIFF is not a terrain engine.
- A DSM is not a DTM.
- A land-cover category is not RF loss.
- A footprint without a validated height policy is not a 3D obstruction.
- A local SHA-256 is not upstream authenticity unless an upstream checksum is independently verified.
- A cached live snapshot is not current merely because it is readable.
- Desktop-only success is not Android parity.
- Android process-survival documentation is not delivery until device tests prove it.
- `.rp3` inspection or conversion on desktop is not native Android support.

Every UI, report, screenshot, test name, and release note must keep those boundaries explicit.
