# ATX Plan Android

Android RF-planning application for the ATX Plan ecosystem. The mobile foundation is designed to work **offline first**, preserve units and data provenance, and grow through verifiable numerical gates instead of screens that only imitate capabilities.

The product, source-facing messages, and project documentation use English as the canonical language. Proper nouns and official dataset names retain their original spelling.

## Current status

The foundation release delivers:

- a responsive Jetpack Compose and Material 3 shell for phones and tablets;
- a compact phone information-density pass with scalable typography, responsive feature layouts, denser cards and forms, and explicit 48 dp minimum targets for the controls changed by that pass;
- Navigation 3 with five areas plus typed, saveable nested RF-path, RF-assets, antenna-pattern, and project-name editor routes;
- a store-schema-1 indexed local store carrying project schema 6, with independently versioned immutable SHA-256 project documents, ordered legacy project migration through schema 6, strict UTF-8, and bounded reads; schema 5 remains the historical link-study milestone;
- project creation, selection, and rename with stale competing-rename protection; transactional project duplication from the latest durable source; bounded transactional archive, restore, and logical hard deletion with complete-snapshot conflict protection; a combined Add RF Path flow; and independent create/edit/delete for networks, sites, sectors, and receivers with stale-snapshot and linked-deletion checks;
- a private content-addressed artifact-store foundation with streaming limits, SHA-256 verification, deduplication, and explicit available/missing/corrupt states;
- a bounded Antenna Pattern Lab with reviewed SAF import, deterministic two-file HRP/VRP pairing, immutable source/canonical artifacts, convention-explicit PRN, ADT HRP/VRP, V-Soft HRP/VRP, Progira PAT, native ATX Antenna JSON v2 interchange with legacy-v1 reading, strict ATX Planner desktop JSON v1 attenuation/phase import and export compatibility, explicit-plane generic CSV/TXT import, complex-vector duplicate-angle averaging, gain-bound normalized-content identity V2, interchange-safe canonical-artifact deduplication, converged CPU coherent synthesis for single, vertical, horizontal, planar, circular, multipanel, and arbitrary-element topologies; arbitrary elements expose per-element pattern, 3D wavelength coordinates, relative power, phase, delay, orientation, and enable state in a compact editor; a compact pattern library, sector assignment, and verified SAF export retain explicit separable-cut and format-loss warnings;
- a synthetic demonstration project with networks, sites, sectors, and study summaries;
- an offline Web Mercator coordinate viewport with fit, pan, pinch zoom, metric scale, site selection, active azimuths, and stale-safe location-only site edits;
- a bounded interactive raster-basemap layer matching the desktop catalog's six remote XYZ providers, with a fixed 10-provider ceiling, visible-view-only planning capped at 48 tiles, HTTPS host restrictions, raster validation, a private 128 MiB cache, visible attribution, and grid-only fallback;
- CPU-only FM and first-generation digital-TV service-contour reference plotting from bundled quantized P.1546-6 land tables, with protected, explicitly revoked E(50,10) legacy interfering envelopes, statistical-screening, incomplete, and `NoData` states kept distinct, plus deterministic SAF KMZ export of already calculated contour evidence;
- a CPU-only channel-42-capable digital-TV regulatory study that keeps the project transmitter independent, samples acquired terrain for radial HNMT, derives the P.1546 `E(50,90)` protected contour, evaluates protected-boundary D/U with P.526-15 Deygout-Assis, and retains point evidence plus explicit filing gates;
- a bounded `181 x 181` operational coverage surface with verified HRP/VRP shaping, transparent `NoData`, desktop-compatible broadcast bands, continuous and Turbo heatmap rendering, and basemap/contour/D/U layer ordering;
- verified SAF study export as bounded HTML, paginated PDF, six-sheet XLSX, and KMZ documents;
- a review-gated, user-triggered Anatel TV/FM Basic Plan workflow that downloads the complete official `Canais.zip` artifact over allowlisted HTTPS, retains immutable SHA-256-addressed raw snapshots, builds and atomically publishes a staged SQLite v1 index, and supports offline service/state/channel/text search in the Data Catalog;
- a bundled, integrity-checked IBGE 2022 national attribute index with 468,099 sector records, 5,570 municipality summaries, offline normalized search, explicit `NoData`, and portable bounding envelopes;
- a user-triggered regional raw-data foundation for fixed Copernicus GLO-30 DSM and ESA WorldCover 2021 tiles plus experimental tiny-area OSM `building`/`building:part` ways, with bounded planning, license review, HTTPS/same-origin restrictions, resumable GET partials, streaming limits, local SHA-256, path-keyed provenance inventory schema 2 with bounded v1 migration, a 24-hour verified live cache/force refresh, TIFF metadata indexing, and derived GeoJSON;
- a durable regional-job foundation with passive microdegree plans, separate semantic and Android-execution SHA-256 identities, exact license snapshots, strict lifecycle/CAS validation, bounded per-job private `AtomicFile` records, pure reconciliation decisions, a scheduler-neutral shared runner, and an API 23-33 foreground WorkManager compatibility envelope that is not yet invoked by the production Data screen;
- a local link budget with ITU-R P.525-5 FSPL, EIRP, received power, margin, noise floor, SNR, midpoint first-Fresnel-zone radius (not clearance), and explicit result provenance;
- a bounded project-linked point-to-point workflow that derives mean-Earth endpoint distance/bearing, uses an AGL-only inclined distance over a flat reference, and durably saves a fingerprinted scalar result with explicit terrain `NoData`;
- a UI-independent Kotlin domain/application model with validated engineering values, receiver/network references, deterministic use cases, and automated tests;
- a custom light/dark theme, API 23 minimum, and the `com.gecesars.atxplan` application ID;
- a GitHub Actions pipeline for build, tests, and lint.

This release does **not** claim to deliver an authorized offline basemap package, an approved bare-earth DTM, RF clutter loss, a building-height model, full-wave or measured 3D antenna modeling, `.atxp`/`.rp3` project import, population-by-coverage, an Anatel licensing/station-authority conclusion, a current regulatory interfering iso-field contour, strict FM interference, or complete desktop parity. The red dash-dot E(50,10) envelopes are reconstructed from revoked rules and never labeled current regulatory results. The strict digital-TV slice consumes acquired Copernicus DSM terrain but remains not filing-ready when its bare-earth DTM, current catalog, completeness, or D/U gates fail. Basic Plan rows are read-only viability/interference references and never populate the independently created project transmitter. See [Antenna, coverage, interference, and export parity](docs/ANTENNA_COVERAGE_PARITY.md) for the delivered/foundation/planned/blocked matrix.

Project duplication is a bounded local operation. It validates and normalizes the requested copy name, reads the latest durable source inside the repository transaction, assigns a fresh root project ID and fresh root creation/update timestamps, and preserves the complete project-scoped RF graph, nested IDs, references, data, order, demonstration flag, and study timestamps. Existing immutable link-study records remain unchanged, including the snapshotted source project identity from the original calculation; they are historical evidence and are not rebased to the copy's root ID. The source is left unchanged, the copy is appended to the catalog and selected on commit. The copied aggregate does not yet record a separate source-project lineage or duplication-provenance marker.

Project archiving is a bounded reversible operation inside the local store-schema-1 indexed store carrying project schema 6. The transaction compares the complete active project aggregate that was reviewed with the latest durable aggregate and rejects stale, repeated, or missing requests without writing. A successful commit retains the aggregate unchanged with an archive timestamp and its original active-list index, removes it from active selection and active project metrics, and selects the next active project, the previous one when the archived project was last, or no project when no active project remains. Restore compares the complete reviewed archive record, reinserts the unchanged aggregate at its original index clamped to the latest active catalog, and selects the restored project. The collapsible **Archived Projects** section exposes retained counts, archive time, and the restore action.

Project deletion remains a separate bounded logical hard-delete operation. The dialog reports the current catalog impact and requires the exact `DELETE` keyword. The transaction compares the complete project aggregate that was reviewed with the latest durable aggregate; a peer rename, RF change, or study-summary change rejects the attempt for review instead of deleting newer data. A successful commit removes that active project and its project-scoped records from the reachable catalog, then selects the next project in the original order, the previous project when the deleted project was last, or no project when the active catalog becomes empty. Archived projects must be restored before permanent deletion. No in-app backup or hard-delete undo is created. Immutable orphan files are intentionally retained until a separately verified cleanup policy exists.

All catalog mutations run against the latest durable catalog inside the repository transaction and publish state only after persistence succeeds. Rejected and no-op outcomes rebase the UI to that latest catalog without a write. The local archive is not backup, export, synchronization, or recovery for a permanently deleted project. General artifact attachment/import UI beyond the bounded antenna flow, reachability-based garbage collection, unreadable/future-store recovery, duplication lineage/provenance, a Room/SQLite project-store migration, multi-process coordination, portable project containers, and file-ownership policy remain outside this slice.

The Engineering Map combines a bounded interactive raster basemap with an offline geographic-coordinate fallback. Pure Kotlin Web Mercator camera math drives project fitting, direct-manipulation pan/pinch zoom, antimeridian-aware projection, and a metric scale. The fixed catalog matches the desktop application's remote XYZ services; requests are limited to the visible viewport and cached privately, while bulk, multi-zoom offline-area downloads remain unavailable. Layer order is basemap, operational coverage surface, coordinate grid, protected/reference contours, current D/U boundary evidence, then project sites. Coverage offers desktop-compatible broadcast bands, continuous color, and Turbo heatmap modes; values below `45 dBµV/m` and `NoData` are transparent. Current P.526/Assis D/U dots are visually and semantically separate from red dash-dot legacy E(50,10) envelopes. The map keeps provider attribution, model/unit/palette identity, range, and `NoData` visible. See [Interactive basemap providers](docs/BASEMAPS.md) and [Antenna, coverage, interference, and export parity](docs/ANTENNA_COVERAGE_PARITY.md).

The broadcast slice follows the pinned protected-contour profiles: FM `E(50,50)` at 66 dBµV/m and first-generation digital TV `E(50,90) = 2 × E(50,50) − E(50,10)` at 43 dBµV/m for channels 7–13 or 51 dBµV/m for channels 14–51. FM/TV cochannel and first-adjacent E(50,10) envelopes are available only as clearly labeled non-regulatory reconstructions of revoked rules. `E(80,80)` is unsupported and remains `NoData`. Current Anatel interference compliance requires point-to-point P.526 plus Assis and D/U evaluation; Android delivers a bounded digital-TV boundary workflow, while strict FM remains open. See [Brazil broadcast service contours](docs/BRAZIL_BROADCAST_CONTOURS.md) for source versions, thresholds, assumptions, and remaining gates.

The project-linked P.525 study is also deliberately bounded. It snapshots one stored sector and compatible receiver, computes mean-Earth great-circle distance and bearing, uses only the endpoints' AGL antenna-height difference for inclined distance, and saves the complete reproducible scalar result. A stored transmitter ground elevation may be snapshotted but is not evaluated. Terrain remains `NoData`; Earth-curvature clearance, effective-Earth propagation, LOS, Fresnel clearance, diffraction, clutter, buildings, vegetation, atmospheric gas, rain, variability, and directional antenna-pattern attenuation are not calculated. See [Project-linked P.525 study](docs/PROJECT_LINK_STUDY.md) for formulas, persisted provenance, migration behavior, and exact exclusions.

## Anatel Basic Plan catalog

The Data Catalog now exposes an explicit **Download & Index** action for the official Anatel TV/FM Basic Plan. The action remains disabled until the user acknowledges that the official source and attribution were reviewed; the source license itself remains `REVIEW_REQUIRED`. Refresh downloads the entire pinned `Canais.zip` artifact over the allowlisted HTTPS path, streams it into a bounded immutable raw snapshot, records its SHA-256, byte count, acquisition time, effective URL, and available HTTP validators, then parses the exact TV/FM XML entries into a staged SQLite schema-v1 index. The closed index is synced and promoted before one atomic current pointer makes it visible. Failure preserves the prior current snapshot, while a newly verified raw archive may remain as immutable evidence.

Installed snapshots support offline, service-scoped queries. The domain core additionally supports state, exact municipality/name or IBGE code, channel, frequency range, accent-insensitive descriptive text, and exact Basic Plan ID; the current compact UI exposes service, state, channel, and text with bounded replacing 25-record previous/next pages. Domain availability (`READY` or `NO_DATA`) is separate from the screen phases (`CHECKING`, `NOT_ACQUIRED`, `READY`, `REFRESHING`, and `FAILED`); a valid zero-match page remains `READY`. No official archive is bundled. Refresh is owned by a route-scoped foreground ViewModel coroutine with an indeterminate spinner, so the Data screen must remain open for UI ownership and publication. Leaving the route clears that owner, but the blocking catalog call has no cooperative cancellation signal and may continue until it returns. The path has no byte progress, background scheduling, process-death/reboot survival, conditional HTTP request, live catalog-metadata/license resolution, automatic retention cleanup, project pin/application/diff, or contour integration. Raw retention is capped at eight immutable archives and 512 MiB; each SQLite index is capped at 256 MiB and its family at eight files/768 MiB. Neither store evicts automatically, so a new distinct generation fails visibly when a ceiling is full.

## Embedded IBGE reference data

The APK includes a 21.1 MiB compressed, read-only IBGE 2022 Census Sector attribute package. First access extracts a 67.6 MiB SQLite database into private no-backup storage after disk preflight, bounded streaming, compressed and database SHA-256 checks, SQLite validation, and atomic promotion. The Data Catalog can search municipalities offline by normalized name or seven-digit code and inspect population, area, urban/rural/unspecified totals, missing-value counts, and bounding envelopes.

This is a bounded reference index, not a geometry or coverage-analysis engine. Sector polygons are not bundled, envelopes are not official boundaries, and the application cannot perform exact containment or population-within-coverage calculations. The immediate row source is a pinned desktop-derived index; the official IBGE archive is independently hash-pinned but is not parsed by the current transformer. Public redistribution remains blocked until the applicable IBGE terms are reviewed. See [Embedded IBGE dataset](docs/IBGE_DATASET.md) for exact hashes, provenance, lifecycle, rebuild instructions, and limitations.

## Regional raw GIS data

The Data screen can build a deterministic plan for a small WGS 84 envelope, show exact source editions and licenses, and download only after explicit acceptance. Copernicus GLO-30 is labeled as a DSM rather than a bare-earth DTM. ESA WorldCover remains categorical source data rather than RF clutter loss. The OSM building option is explicitly experimental, uses a public best-effort Overpass endpoint, requests only a ways union of `building` and `building:part`, and is limited to a tiny user-triggered area and 16 MiB raw response.

Transfers are restricted to fixed HTTPS hosts, same-origin redirects, and private no-backup storage; both the initial and every resolved redirect URL are bounded to 2,048 characters. GET partials resume only with a strong ETag, a consistent `Content-Range`, and bounded metadata whose effective URL remains on the requested HTTPS origin. Completed partial metadata additionally requires its byte-total fields and a valid nonfuture acquisition timestamp. Transient GET failures receive at most three total attempts. The fixed read-only Overpass POST receives at most two total attempts and always restarts its staging file. `Retry-After` is accepted only from 1-30 seconds within that budget, and HTTP 429 without a valid bounded value is not replayed. Every accepted raw file receives a local SHA-256 and a bounded schema-2 provenance record containing a nested source snapshot, requested/effective route metadata, and acquisition time; completed effective provenance requires the bounded same-origin HTTPS endpoint and valid completion fields. A valid primary or atomic-backup schema-1 inventory migrates offline and atomically without inventing an unavailable effective URL or acquisition time; recovery from a valid backup also replaces an invalid primary. All `FileRegionalDatasetRepository` acquisition and inventory operations share one application-wide in-process mutex across repository instances. This is not multi-process coordination. The SHA verifies the later local cache and is not presented as an upstream authenticity proof because the raster providers do not publish a trusted SHA-256 for every tile.

CPU processing validates bounded TIFF/BigTIFF metadata or converts bounded building/building-part ways to attributed GeoJSON. GeoJSON retains the actual final endpoint, a valid upstream OSM base timestamp when supplied, and bounded raw height/level/roof tags, but does not interpret them as height or support multipolygon relations/holes. A verified live snapshot may be reused for 24 hours; stale refresh occurs only during another explicit acquisition, while the force-refresh checkbox bypasses fresh cache. There is no polling/background refresh. The inventory remains current-snapshot/path-keyed.

The separate `MOB-027A` foundation normalizes reviewed bounds to integer microdegrees and produces a portable semantic fingerprint plus an exact Android execution fingerprint. It can persist strict revisioned job records with scheduler generations, bounded retry/checkpoint state that rejects future-artifact checkpoints, and per-artifact inventory outcome references. Its pure reconciliation decisions prioritize cancellation, contextually audit committed outcomes including immutable terminal records, and deterministically cancel extra stale/current scheduler targets observed for one job. An invalid terminal outcome produces only a guarded `REPORT_TERMINAL_OUTCOME_INVALID` action; it does not rewrite terminal state. Record-derived actions carry revision, fingerprint, and expected record-generation guards separately from the concrete target scheduler kind, target plan fingerprint, generation, and identity. Scheduler targets whose job ID belongs to an unreadable record are preserved, while a truly recordless cancel carries an expected-record-absent guard for a future executor to verify atomically before the external effect. A persisted cancellation emits exact scheduler-cancel actions but never `MARK_CANCELED` from snapshot state alone; runner or drain-aware executor evidence is still required. Recoverable work at the maximum scheduler generation becomes a typed `scheduler-generation-exhausted` orphan decision. New ownership also fails closed for unreadable records or overlapping active artifact paths.

`MOB-027B` adds a scheduler-neutral `RegionalJobRunner` for one previously persisted canonical job. It rejects stale scheduler ownership before dataset access, rebuilds the fixed-catalog plan, executes artifacts sequentially, and persists each provider-attempt permit before transport through guarded compare-and-set updates. `FileRegionalDatasetRepository` remains the only provider-retry owner. Durable cancellation can win before or during transfer, a scheduler stop requests pause and reconciliation rather than claiming user cancellation, and success requires an exact committed inventory-entry fingerprint for every artifact outcome. A committed inventory result can be adopted after an outcome-link conflict without repeating the transfer.

`MOB-027C` adds the API 23-33 foreground WorkManager foundation. Its strict three-field input derives a deterministic SHA-256 UUIDv8 per job/fingerprint/generation, mirrors that fingerprint in strict scheduler tags, uses generation-scoped unique `KEEP` work, and requires connected network plus storage-not-low. WorkInfo decoding recomputes the UUID from all three identity fields; finished retained work is never acknowledged as newly `QUEUED`. The foreground `CoroutineWorker` reconstructs private-storage dependencies after process loss and delegates only to `RegionalJobRunner`; it never returns `Result.retry()` for provider failure. A low-importance `dataSync` notification derives a nonzero ID from the physical WorkRequest, rechecks visibility on progress, and exposes an immutable exact-owner cancel action. Cancellation persists `cancelRequested` and cancels only that physical UUID; a WorkManager `CANCELLED` observation means cancellation was requested, not that execution drained. Durable terminalization waits for runner or future drain-aware reconciliation evidence. Scheduler snapshots and every scheduler-target action carry the plan fingerprint and fail closed on malformed, inconsistent, or excessive WorkManager metadata.

The Data screen still does not create or observe these durable jobs, and no application-start reconciliation executor invokes the adapter. Current user-facing acquisition remains ViewModel-owned and screen-bound, so the app must remain open while it runs. API 34+ UIDT, job-specific notification navigation, notification-permission UX, late network-byte/checkpoint crash evidence, and tested process/reboot recovery remain planned, as do content-addressed history/pins, raster sampling, and cache garbage collection. See [Regional raw GIS data](docs/REGIONAL_DATA.md) for limits, lifecycle, source terms, and exact exclusions.

## Documentation

- [Application map](docs/APPLICATION_MAP.md): desktop/RadioPlanner reference capabilities mapped to Android and their delivery state.
- [Roadmap](docs/ROADMAP.md): phases, priorities, gates, and Definition of Done.
- [Architecture](docs/ARCHITECTURE.md): UI/domain/data boundaries, offline-first behavior, persistence, and compute strategy.
- [Antenna pattern composer](docs/ANTENNA_PATTERN_COMPOSER.md): canonical field/angle equations, CPU synthesis, import/export formats, SAF artifacts, directional contours, KMZ separation, and the delivered review-gated Anatel Basic Plan catalog boundary.
- [Brazil broadcast service contours](docs/BRAZIL_BROADCAST_CONTOURS.md): current FM/digital-TV profiles, packaged P.1546 reference data, map behavior, `NoData`, and strict regulatory blockers.
- [Embedded IBGE dataset](docs/IBGE_DATASET.md): source boundary, exact package identity, Android lifecycle, rebuild, and capability limits.
- [Regional raw GIS data](docs/REGIONAL_DATA.md): fixed providers, regional limits, transfer integrity, CPU processing, licenses, and engineering exclusions.
- [Cross-platform regional data contract](docs/CROSS_PLATFORM_DATA_CONTRACT.md): shared identity/provenance semantics, delivered dual plan-identity foundation, remaining fixtures, and portability gates.
- [Android regional-data lifecycle ADR](docs/adr/0001-android-regional-data-lifecycle.md): delivered job-contract/store/shared-runner and API 23-33 WorkManager foundations, plus planned reconciliation execution, Data-screen wiring, and API 34+ UIDT.
- [Project-linked P.525 study](docs/PROJECT_LINK_STUDY.md): endpoint geometry, RF terms, the immutable record introduced by schema 5 and retained by schema 6, tests, and explicit exclusions.

## Stack and compatibility

| Item | Baseline |
|---|---|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose 1.11 (BOM 2026.04.01) + Material 3 |
| Navigation | AndroidX Navigation 3 |
| Project persistence | atomic JSON index + immutable SHA-256 project documents/artifact blobs in private app storage |
| Bundled reference data | verified read-only SQLite extracted from a content-addressed IBGE asset into private no-backup storage |
| Regional dataset cache | bounded raw files, resumable GET staging, derived metadata/GeoJSON, and a schema-versioned provenance inventory in private no-backup storage |
| Regional job foundation | passive schema-1 plans/jobs, semantic and Android-execution SHA-256 identities, bounded UUID-named `AtomicFile` JSON records in private no-backup storage, a scheduler-neutral shared runner, and an API 23-33 foreground WorkManager adapter; not invoked by the production Data screen or reconciliation executor |
| Antenna patterns | pure Kotlin complex HRP/VRP model; bounded PRN, ADT, V-Soft, PAT, native ATX JSON v2, and strict ATX Planner desktop JSON v1 attenuation/phase interchange; one- or two-cut desktop/PRN import and deterministic two-file HRP/VRP source bundles; explicit-plane generic table import; gain-bound identity V2 plus canonical-artifact-aware deduplication; converged/budgeted CPU coherent-array composition with separable-cut/HRP peak gates; schema-6 fixed-grid records; and verified SAF interchange with format-loss warnings |
| Broadcast contour reference | pure Kotlin CPU planner with SHA-256-verified, 0.01 dB packaged P.1546-6 land tables; transient and always non-regulatory |
| Contour interchange | deterministic, bounded service-contour KMZ writer with KML plus evidence manifest and SAF read-back verification; not an antenna import format or regulatory package |
| Anatel Basic Plan catalog | explicit review-gated whole-archive HTTPS acquisition, immutable raw snapshots, bounded ZIP/XML parsing, staged/atomic SQLite v1, and offline core/UI queries; no bundle, background/process survival, metadata resolution, project pin/application, or contour integration |
| Gradle | Wrapper 9.3.1 |
| Android Gradle Plugin | 9.1.1 |
| Java | Java 17 bytecode; JDK 21 recommended for builds |
| Android | `minSdk 23`, `targetSdk 36`, `compileSdk 36.1` |

The store-schema-1 index and project-schema-6 document store, together with the content-addressed artifact store, are durable foundations, not a portable project package or a complete raster/dataset lifecycle. The antenna slice now uses SAF and verified immutable artifacts for its bounded formats, but a general attachment/package workflow, ownership cleanup, recovery, and portable project export are still absent. The separate embedded IBGE SQLite file is release-managed reference data and is not the project store. Room/SQLite remains an option for future granular project/job queries.

## Reference-device layout evidence

The compact information-density implementation has bounded manual portrait and landscape checks on one physical Android 16 phone. The observed configuration was:

| Property | Observed value |
|---|---|
| Physical display | 1280 × 2772 pixels |
| Reported density | 520 dpi |
| Approximate portrait width | 394 dp |
| Baseline system font scale | 1.15 |
| Additional fallback check | 1.30, restored to 1.15 after inspection |
| Orientation checks | Portrait and landscape at 1.15; portrait at 1.30 |

For the baseline portrait configuration, Dashboard and Projects use 16 dp content gutters while the field-heavy feature screens use compact 12 dp phone gutters. Shared headers, chips, cards, forms, the technical canvas, and vertical rhythm are denser. Dashboard uses one row for three metrics; Studies uses safe two-column input pairs; Map and Data use width-bounded responsive content. At font scale 1.30 on the same device, Dashboard changed to a two-plus-one metric arrangement and Studies changed to single-column fields without clipping labels, values, or units. In baseline landscape, the 720 dp width gate selected a navigation rail and the short-height mode kept all five destinations visible as accessible icons while wide feature content retained its responsive layout. The project-name editor was also checked in portrait and landscape with the on-screen keyboard on the physical device: explicit Activity resize handling keeps the top app bar and editable name visible in the short landscape window, while its `LazyColumn` retains access to the remaining content. Body text continues to use scalable `sp` units; the application does not replace, clamp, or override the system font scale. Engineering summaries and numerical facts are allowed to wrap, while ellipsis is limited to noncritical project/customer labels.

Separately, the compact adaptive project actions and dialogs were manually validated on the Android 16/API 36 emulator at 1080 × 2400 pixels and 420 dpi. Duplicate Project and Delete Project were checked in portrait and short landscape at font scales 1.0 and 1.30 with Gboard open and closed. Archive Project, its actions, and the archived-project card were reachable in portrait at font scales 1.0, 1.30, and 2.0 and in landscape at 1.30. Manage RF Assets has an automated 360 × 480 dp/font-scale-1.30 reachability check for its tabs, long editor, and blocked deletion, plus a 1080 × 2400 visual inspection after a real legacy migration. The Data Catalog has a separate 360 × 480 dp/font-scale-1.30 automated check for Ready/search/selection/limitations and its progress/failure/query states. Five Studies cases cover the compact searchable selectors and save action, complete saved details, lazy saved history, collision-safe sector identity, and the no-compatible-receiver state at the same large-text scale. A fresh 1080 × 2400/font-scale-1.30 manual Catalog run installed the packaged IBGE database, displayed its six dense metrics without clipping, resolved unaccented `sao paulo` locally, exposed four municipality results in the viewport, and showed the selected envelope/`NoData` details. The exact `DELETE` field remained fully visible. At font scale 1.30, portrait stacked the project actions; short landscape used the compact dialogs and retained scroll access to their actions. The application does not reduce or override the system font scale.

A bounded manual emulator force-stop/relaunch check retained the archived record. Restoring it retained the project as active and selected across a second force-stop/relaunch. This is evidence for the tested local catalog path only; it is not proof of Android Backup, system-reclaim restoration, every process-death timing, or a broader device support matrix.

A separate manual API 36 check calculated and saved a project-linked study at font scale 1.30, force-stopped the application, relaunched it, and reopened the same endpoint, FSPL, received power, margin, SNR, provenance, and fingerprint. A real-storage instrumentation case also promotes an indexed schema-4 fixture through schema 5 to current schema 6 and verifies integrity and reopen behavior. These are bounded local-storage observations; JVM fault injection remains the evidence for interrupted publication paths.

The physical-device observations and separate emulator checks are bounded evidence, not a support matrix. They do not establish coverage for other OEMs, Android versions, aspect ratios, tablets, foldables, extreme font scales, screen readers, switch access, or every light/dark-theme combination. Those checks remain release work.

## Local build on Windows

`local.properties` must point to an Android SDK containing platforms 36/36.1 and Build Tools 36.1. This file is local and must never be committed.

In PowerShell, using the Android Studio JDK:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

The debug APK is generated at `app/build/outputs/apk/debug/`.

On Linux/macOS with `JAVA_HOME` already configured:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Instrumented tests require an emulator or connected device:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

The current aggregate instrumented run passed **99/99 tests** with no failures or skips on the Android 16/API 36 `Medium_Phone_API_36.1` emulator at 1080 × 2400 pixels and 420 dpi. It includes navigation, compact Compose screens, structured and arbitrary antenna-composer controls, legacy-contour map semantics, coverage render-mode/NoData reachability at enlarged text, Anatel/IBGE repositories, project persistence, and regional-job/WorkManager foundations. The preceding 18-test revision also passed on the physical Android 16 reference phone; the expanded application still needs a fresh physical rerun, and API 23/33 runtime proof remains open.

The current aggregate JVM run discovered **602 tests**: **601 passed**, one Windows symlink-hardening case was permission-skipped, and there were zero failures or errors across project persistence, acquisition, durable jobs, WorkManager contracts, antenna composition/interchange/identity, broadcast/strict DTV contours, coverage palettes/surfaces, PDF/XLSX export, Anatel, IBGE, and RF-domain coverage. The Android 16/API 36 emulator aggregate passed **99/99** tests with no failures or skips, including the compact arbitrary-element editor and legacy-contour map semantics. `lintDebug` completed with 0 errors, 19 warnings, and 3 hints; debug APK and Android-test APK assembly also succeeded. A live API 36 emulator run downloaded the 11,870,186-byte official Anatel archive with SHA-256 `51391ba6d2c9a58233eeedd8cc0fef64eb4ab8f33622b9ea6e469d7bf90384f6`, parsed and indexed all 87,400 emitted TV/FM source records, and verified offline FM/TV query, source details, and replacing 25-record pagination. This is one dated provider snapshot, not a provider-availability, licensing, or regulatory-validity guarantee. Separately, a bounded live-provider run downloaded 65,317 raw OSM bytes through the fixed Lambert route, produced 44 building/building-part way features, verified both raw and processed SHA-256 values, retained the actual endpoint and upstream OSM timestamp, and reopened its schema-2 inventory. That OSM run is smoke evidence for one small request, not a completeness guarantee; the delivered durable adapter remains unreachable from the current Data screen, and API 23/33, UIDT, late network-byte/checkpoint crash, physical-device, process-death, and reboot proof remain open for the expanded revision.

## Code organization

```text
app/src/main/java/com/gecesars/atxplan/
|-- data/anatel/        # on-demand raw snapshots, bounded parser, and atomic SQLite catalog
|-- data/antenna/       # bounded structured codecs plus explicit-plane table import
|-- data/dataset/       # verified bundled IBGE SQLite repository
|-- data/export/        # bounded HTML/PDF/XLSX/KMZ engineering exporters
|-- data/scheduler/work/# API 23-33 foreground WorkManager foundation
|-- data/project/       # storage and concrete repository
|-- domain/application/ # injected use cases and transactional commands
|-- domain/anatel/      # Basic Plan source, provenance, records, and bounded queries
|-- domain/antenna/     # canonical complex cuts and CPU coherent-array synthesis
|-- domain/contour/     # CPU-only Brazil broadcast reference rules, tables, radials, and geodesy
|-- domain/coverage/    # bounded field surfaces and broadcast/continuous/heatmap palettes
|-- domain/dataset/     # dataset identity, progress, query models, and contract
|-- domain/geo/         # pure Web Mercator camera, gesture, fit, and scale math
|-- domain/model/       # projects, RF entities, typed units, and studies
|-- domain/rf/          # pure Kotlin calculations
|-- ui/components/      # shared components
|-- ui/anatel/          # review-gated Basic Plan refresh/query feature state
|-- ui/antenna/         # antenna lab state, SAF orchestration, and ViewModel
|-- ui/dataset/         # Data Catalog feature state and ViewModel
|-- ui/forms/           # saveable RF-path draft and parsing boundary
|-- ui/navigation/      # typed stable-ID Navigation 3 routes
|-- ui/screens/         # Compose flows
|-- ui/theme/           # design system
|-- ui/AppViewModel.kt  # UDF state and coordination
`-- MainActivity.kt     # Android composition root

app/src/main/assets/datasets/ibge/
|-- manifest.json       # strict pinned package identity and hashes
|-- NOTICE.txt          # attribution, transformation, and release caveat
`-- *.ibgedata          # content-addressed gzip SQLite payload

third_party/javaP1546/
|-- LICENSE             # retained upstream license
`-- NOTICE.md           # pinned source commit and ATX Plan table transformations
```

Extraction into separate Gradle modules will be incremental, after the boundaries and build benefit are demonstrated.

## Data safety

- External files will be treated as untrusted input.
- An invalid or future-schema catalog is never overwritten automatically.
- Project archive and restore are atomic local catalog transitions. Archive retains the complete project aggregate, its archive timestamp, and its original active-list index; restore preserves that aggregate and selects it only after a successful write.
- The local archive is not backup, export, synchronization, external-asset recovery, or recovery for a permanently deleted project.
- Project hard deletion is one atomic index replacement: a failed write preserves the previous reachable catalog. The successful operation has no in-app backup or undo; immutable orphan project documents and artifact blobs are retained until a verified reachability cleanup policy is delivered.
- Missing physical data remains missing; `NoData` never silently becomes zero.
- Datasets and services must record license, attribution, version, and hash; the embedded IBGE package also records its immediate-source boundary and unresolved redistribution review.
- Keystores, local properties, secrets, APKs, and AABs are excluded from Git.

## Repository safety

The canonical working copy is a standalone repository. Before any `git add`, `commit`, remote, or push operation, verify that:

```powershell
git rev-parse --show-toplevel
```

returns the Android project directory itself and does not resolve to an enclosing desktop repository. The canonical remote supplied for this project is `https://github.com/Gecesars/ATX_PLA_APK.git`; local directory spelling does not change the ATX Plan product name or the `com.gecesars.atxplan` application ID.

## License and distribution

The repository is public, but the product license still requires an explicit decision before formal public distribution. Distribution of an APK/AAB containing the derived IBGE asset also requires owner review of the applicable IBGE redistribution terms and retention of its notice, attribution, source identity, and limitations. The packaged P.1546 land-table subset retains the pinned javaP1546 upstream license and ATX Plan modification notice under `third_party/javaP1546/`; release review and the SBOM must preserve them. Proprietary materials used as behavioral references are not included in this repository.
