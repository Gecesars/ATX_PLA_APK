# Android Roadmap

> Evidence baseline: August 28, 2026. The repository now contains an adaptive Compose shell, typed and saveable Navigation 3 routes, explicit UDF/application boundaries, a schema-6 project model stored through a small atomic index and immutable SHA-256 project documents, explicit legacy migration through schema 6 while preserving schema 5 as the link-study milestone, transactional project lifecycle operations, independent network/site/sector/receiver CRUD, a compact Manage RF Assets UI, a content-addressed artifact store with a bounded antenna workflow, a CPU-only Antenna Pattern Lab with strict codecs and coherent array composition, a bounded manual RF calculator, a persisted project-linked ITU-R P.525-5 study, pattern-aware CPU-only non-regulatory Brazil broadcast-contour reference plotting with bounded deterministic KMZ export, a review-gated on-demand Anatel TV/FM Basic Plan catalog with immutable raw snapshots and atomic SQLite v1 publication, a verified offline IBGE 2022 national attribute/municipality index, regional job contract/store/reconciliation foundations, a scheduler-neutral shared runner, and an API 23-33 foreground WorkManager compatibility envelope, plus CI, JVM tests, and Android instrumentation. This roadmap does not treat those bounded capabilities as terrain-aware analysis, a strict regulatory result, an Anatel licensing/authorization conclusion, a process-durable/background catalog refresh, a user-reachable process-durable regional workflow, proven reboot recovery, a full-wave antenna solution, or complete desktop/RadioPlanner parity.

## 1. Delivery strategy

Development follows small, reproducible vertical slices. A phase ends with behavior that is usable, persisted where required, tested, and honest about its limits—not with disconnected screens.

Sequencing principles:

1. harden the existing identity, build, navigation, state, and persistence foundation before multiplying features;
2. complete project → RF entities → geographic map → terrain-aware link before advanced coverage;
3. keep local storage as the source of truth and make network use explicit;
4. validate units, geometry, and numerical results before optimization;
5. introduce native code or an optional service only after a Kotlin baseline and benchmark;
6. separate file compatibility, functional compatibility, and numerical parity;
7. require device or emulator evidence for every product milestone;
8. keep all product code, UI, tests, and documentation in English.

## 2. Current implementation status

| Item | Status | Current evidence | Remaining gap |
|---|---|---|---|
| Package and compatibility | Delivered | `com.gecesars.atxplan`, `minSdk 23`, `targetSdk 36`, `compileSdk 36.1`, version `0.1.0`. | Formal physical-device matrix and release policy. |
| Build and lint | Delivered | Debug/test APKs built; latest lint has 0 errors, 16 warnings, and 3 informational findings. | Dependency/toolchain update advisories and no signed release. |
| CI | Delivered | GitHub Actions runs unit tests, lint, debug APK, and debug test APK builds. | Connected Android test is not in CI. |
| Compose theme and shell | Delivered | Custom light/dark ATX theme, Material 3, edge-to-edge, compact bottom bar, expanded navigation rail. | Full accessibility, locale enforcement, and device-matrix validation. |
| Compact phone information density | Delivered baseline | Scalable compact typography and shared components, 12–16 dp feature gutters, responsive metrics/fields/cards/map height, a short-height rail, IME resizing for the project-name editor, and explicit 48 dp minimums on changed controls have manual evidence on one physical Android 16 phone: 1280 × 2772 pixels, density 520, portrait at font scales 1.15/1.30 and landscape at 1.15. Duplicate Project and Delete Project were separately validated on an Android 16/API 36 emulator at 1080 × 2400 pixels and 420 dpi, in portrait and short landscape at font scales 1.0/1.30 with Gboard open and closed. Archive Project, its actions, and the archived-project card were reachable in portrait at font scales 1.0/1.30/2.0 and in landscape at 1.30. Manage RF Assets and the IBGE Data Catalog have 360 × 480 dp/font-scale-1.30 automated reachability checks. Five project-link cases at that size and scale cover searchable selectors and save action, complete saved details, lazy saved history, collision-safe sector identity, and the explicit no-compatible-receiver state. | These bounded physical-device and emulator observations are not a complete OEM/API/aspect-ratio/theme/font-scale/accessibility matrix; the app does not clamp or override system font scale. |
| Navigation 3 | Foundation | Serializable stable-ID route keys, a saveable typed `NavBackStack`, bounded unknown-route fallback, and nested RF-path, project-name, and project-scoped RF-asset routes are implemented. Saved-instance-state restoration tests cover all three nested route families. | Deep links, deleted-ID recovery UX, adaptive list/detail, and true system process-death/rotation testing across the device matrix remain. |
| UDF/ViewModel | Foundation | Explicit `AppUiAction`/`AppUiEffect`, structured problem/recovery values, injected use cases/dispatchers, cancellation-aware calculation, serialized catalog mutations, and the regional job contract/store/reconciler plus a scheduler-neutral runner exist. An API 23-33 WorkManager adapter can invoke the runner for an exact durable generation. The Data Catalog has separate IBGE/regional feature state, while the Anatel review gate, refresh, status, filters, and replacing pages are owned by a Navigation-3-entry-scoped ViewModel. Leaving that route clears the owner and cancels later UI publication. Every project-catalog mutation is evaluated against the latest durable catalog inside the repository transaction; state is published only after persistence, while rejected/no-op outcomes rebase to the latest catalog without writing. | The Data-screen ViewModels do not persist, submit, or observe durable regional jobs. Anatel refresh remains a foreground, route-lifetime coroutine, but its current blocking catalog call has no cooperative cancellation signal and may continue until it returns. No production reconciliation executor calls either scheduler path. Cross-instance catalog observation, broader DI/scoping, API 34+ UIDT, observability, accessibility, and system recovery evidence remain. |
| Project persistence | Delivered baseline | Strict UTF-8 JSON stores current project schema 6 through a small store-schema-1 `AtomicFile` index and immutable SHA-256-addressed project documents. Legacy schemas 1–5 migrate in order to schema 6; schema 5 introduced link studies. Indexed legacy promotion writes and verifies current project documents before atomically publishing the replacement index. Schema-5 antenna-field injection is stripped before migration. Document length/hash checks, no-op reuse, and single-project document updates have JVM coverage. | Recovery/export UX for unreadable/future indexes, garbage collection, multi-process policy, Android storage-exhaustion/interruption evidence, backup, portable ownership, and the long-term indexed-files-versus-Room decision remain. |
| Artifact store | Foundation with delivered antenna use | A private content-addressed store stages, operation-bounds, SHA-256-verifies, deduplicates, checks, and copies immutable bytes. Schema 6 persists bounded artifact references; confirmed antenna imports store exact single-file bytes or a deterministic ZIP with both original HRP/VRP files and a hash manifest as `IMPORT_SOURCE`, plus deterministic ATX JSON v2 as `ANTENNA_PATTERN`. Synthesis stores the canonical artifact, and export reopens/correlates it before encoding. Antenna-catalog duplicate detection requires both normalized engineering identity and the exact canonical-artifact SHA-256, so equal normalized cuts with different interchange metadata/content remain distinct. The regional raw-data foundation uses a separate private cache and inventory. | The antenna slice is not a general attachment/package lifecycle. Artifact-management UI, garbage collection, portable project export, project-deletion cleanup, and reference-aware regional removal remain. The persisted project-link record still lives in its project document rather than an artifact-backed portable package. |
| Project workflow | Foundation | Load, create, select, rename, duplicate, archive, restore, hard-delete, and display projects; seed the synthetic demo; add one linked RF path; and independently create/edit/delete networks, sites, sectors, and receivers through latest-durable transactions. Exact expected-entity checks reject stale RF edits/deletes. Network deletion is blocked while sectors or receivers reference it, and deleting a non-empty site explicitly includes its sectors. | Hard-delete recovery/undo/export, artifact ownership/cleanup, scenario/import workflows, dependencies from future study/GIS/antenna records, bulk edits, and duplication lineage/provenance remain. |
| Domain model | Foundation | Schema-6 active/archive invariants, validated engineering values and RF records, the schema-5 project-link record, and schema-6 calculation-ready antenna records are persisted and validated. Antenna records include source/canonical artifact references, origin, convention, fixed one-degree HRP/VRP, hashes, warnings, and sector assignments. Structural GIS, scenario, coverage, regulatory, and import-provenance records remain, while transient contour models retain radial/status/provenance evidence. | Existing legacy entity primitives still need staged migration. Regulatory and other non-RF records remain structural foundations; contour overlays are not persisted regulatory studies. Terrain-aware, exported, and other study types remain planned. |
| Antenna Pattern Lab | Delivered bounded slice | Bounded pure Kotlin PRN/ADT/V-Soft HRP/VRP, Progira PAT, native ATX Antenna JSON v2 write/v1-v2 read, strict ATX Planner desktop JSON v1 attenuation/phase import/export compatibility, and explicit-plane generic CSV/TXT-compatible import are implemented. Desktop JSON v1 and PRN can import one or two cuts; full desktop JSON export requires real HRP and VRP plus known nominal frequency and gain. Desktop three-column PRN phase is retained, export includes phase and gain when available, and any phase substitution is warned. Known V-Soft beam tilt is preserved on export. Untrusted JSON structure/sample counts and paired-source aggregate bytes are bounded before large allocations. Canonical export is artifact-correlated, destination-verified, and surfaces format-loss warnings; catalog deduplication also matches the exact canonical-artifact hash so interchange-distinct sources are retained. Array synthesis uses spatially separated local-peak seeds, always discloses its separable-cut representation, and refuses to attach converged 3D gain when the sampled HRP peak is more than 0.1 dB below the converged 3D peak. Missing-plane placeholders remain display-only; ambiguous ADT `.pat` files fail closed, while explicitly vertical circular ADT input is cropped to `-90..+90` with a warning. | The separable HRP/VRP result is not measured/full-wave 3D, and no GPU solver is delivered. Generic tables are import-only and KML/KMZ is explicitly rejected as pattern input. Native ATX JSON v1 remains readable but its absent gain/vertical-azimuth/beam-tilt metadata stays explicit `NoData`; desktop JSON compatibility is an antenna-pattern subset, not `.atxp`/`.rp3` project parity. ADT placement/power/phase-offset semantics, mutual coupling, tower/feed effects, cross-project catalog, artifact garbage collection, P.525 directional loss, and regulatory validation remain absent. |
| Engineering canvas | Foundation | An offline Compose Web Mercator coordinate grid plots sites, active azimuths, and supplied broadcast-contour references with project fitting, pan/pinch camera controls, a metric scale, touch and accessible-list selection, explicit local-grid attribution, compact contour provenance/status, durable coordinate-only site editing, and bounded SAF export of already calculated contours as a deterministic KML-plus-manifest KMZ with destination read-back verification. | The dedicated contour layer/KMZ is not a general GIS renderer, antenna format, persisted study, or regulatory package. A cartographic renderer, authorized basemap source/package lifecycle, DEM/terrain, IBGE geometry/map integration, general GIS features, receiver editing, drag placement, external-reader validation, and map performance evidence remain. |
| Data Catalog | Delivered bounded slices | The bundled 21.1 MiB IBGE asset installs to a verified 67.6 MiB read-only SQLite database. The Anatel section review-gates an explicit whole-archive HTTPS refresh, shows verified immutable snapshot evidence, and queries its offline SQLite v1 generation by service/state/channel/text. A separate regional flow plans and acquires fixed Copernicus GLO-30 DSM and WorldCover COGs plus tiny opt-in OSM `building`/`building:part` way responses using bounded transport, provenance, inventory, processing, and cache rules. Regional durable-job foundations add canonical E6 plans, dual fingerprints, strict state/CAS, private records, pure reconciliation, a shared runner, and an API 23-33 foreground WorkManager adapter. | Anatel refresh lacks byte progress, background/process survival, conditional metadata refresh, retention cleanup, project/contour integration, and approved license status. Regional user execution is also screen-bound and no production reconciliation executor invokes its scheduler. Broader raster, terrain, clutter, geometry, map, coverage, process/reboot, API 34+ UIDT, and release-license work remains. |
| Anatel Basic Plan catalog | Delivered bounded slice | Official source/license/provenance/frequency models, bounded hostile ZIP/XML parsing, a review-gated production HTTPS refresh, immutable SHA-256 raw/provenance snapshots, transactionally staged and atomically promoted SQLite schema-v1 indexes, an atomic current pointer with prior-generation preservation, explicit domain `NoData`, bounded core queries, and a compact offline service/state/channel/text UI with `CHECKING`/`NOT_ACQUIRED`/`READY`/`REFRESHING`/`FAILED` phases and replacing 25-record previous/next pages are implemented. | No official archive is bundled. The license remains `REVIEW_REQUIRED`; refresh ownership is tied to the Data route, but the blocking catalog call is not cooperatively cancelable. There is no current metadata/license resolver, conditional GET, byte progress, WorkManager/UIDT, process/reboot survival, automatic cleanup after the raw eight-archive/512 MiB or index eight-file/768 MiB ceilings, project pin/application/diff, or contour/regulatory integration. |
| RF calculation | Delivered bounded slice | The manual calculator provides ITU-R P.525-5 free-space loss, EIRP, received power, fade margin, midpoint Fresnel radius, thermal noise, and SNR. The project-linked flow selects a stored sector and compatible receiver, derives spherical mean-Earth distance/bearing and AGL-only inclined distance, snapshots effective inputs, calculates P.525-5, and persists one immutable fingerprinted record and completed summary before UI publication. | The project-linked P.525 flow still does not apply its preserved antenna reference. DEM/terrain, ground-elevation contribution, curvature/LOS/Fresnel clearance, directional loss, diffraction, clutter, fading variability, coverage, export, `.rp3`, and RadioPlanner parity remain absent. |
| Brazil broadcast contour reference | Delivered bounded slice | A pure Kotlin planner uses bundled 0.01 dB P.1546-6 land-table values to produce 72-radial transient FM/DTV overlays. FM protected is `E(50,50)`/66 dBµV/m; first-generation DTV protected is derived `E(50,90)`/43 or 51 dBµV/m; FM `E(50,10)` is a distinct non-regulatory statistical screen; `E(80,80)` is `NoData`. An assigned canonical HRP applies `ERP_peak × (E/Emax)^2` once per radial; missing data uses an explicit nominal fallback and zero field stays radial `NoData`. All results set `regulatory = false`; a deterministic bounded KMZ preserves supplied geometry and evidence without recalculation. | AGL substitutes for radial HNMT, VRP/tilt and downloaded terrain are unused, and neither transient state nor KMZ is a persisted executable study or regulatory filing. Strict P.1546 evidence, approved pattern/fallback policy, P.526+Assis/D-U interference, legal review, and regulatory conclusions remain blocked. |
| JVM tests | Delivered evidence | The current aggregate discovered 580 tests: 579 passed, one Windows symlink-hardening case was permission-skipped, and there were zero failures or errors across project, regional-data/job, WorkManager-contract, antenna, Anatel/IBGE, RF, and contour paths. `lintDebug` completed with 0 errors, 16 warnings, and 3 informational findings; debug APK and Android-test compilation succeeded. | The tests do not prove a production durable-job caller, late network-byte/checkpoint crash recovery, API 23/33 runtime behavior, process/reboot recovery, or strict contour parity. |
| Instrumented tests | Delivered evidence | The current Android 16/API 36 AVD aggregate passed 96/96 tests with no failures or skips. A separate live official Anatel run downloaded and indexed 87,400 source records and exercised offline FM/TV query, source details, and page replacement. The preceding 18-test physical-phone revision remains historical evidence. | A fresh physical run, API 23 and API 33 scheduler execution, true system-reclaim process termination, reboot recovery, rotation/device matrix, broader accessibility automation, map performance, and CI execution remain. |
| Product language | Delivered baseline | Production UI/errors/demo/tests and documentation are English; a unit test scans Kotlin, XML, JSON, and text production resources for common Portuguese terms while allowing pinned official identifiers. | The blacklist is partial and must expand with each new user-visible resource type. |
| Public release | Blocked | Debug baseline only; backup disabled. | Product license, IBGE redistribution review/NOTICE retention, signing, SBOM, privacy, shrinker, upgrade testing, and release channel. |

## 3. Priority matrix

### P0 — Offline mobile MVP

Already delivered or founded:

- Android identity and API 23–36.1 compatibility;
- reproducible debug build and CI workflow;
- Compose/Material 3 shell and custom theme;
- typed, saveable Navigation 3 routes including nested RF-path, project-name, project-scoped RF-asset, and antenna-pattern editors;
- explicit action/effect ViewModel flow, use cases, injected dispatchers, and structured recovery;
- store-schema-1 indexed project-schema-6 persistence with explicit legacy 1→2→3→4→5→6 project migration, immutable SHA-256 project documents, schema-5 link-study history, and defensive promotion/integrity tests;
- a content-addressed artifact-store foundation with bounded staging, hash verification, deduplication, availability checks, and copying; the antenna slice uses it for reviewed source/canonical artifacts, while general attachment and garbage collection remain absent;
- project create/select/rename/duplicate/archive/restore/hard-delete, synthetic demo, combined Add RF Path, and independent network/site/sector/receiver create/edit/delete slices;
- typed engineering values, receiver/CPE, and network references;
- free-space RF calculator and numerical unit tests;
- a bounded project-linked P.525-5 flow with stored sector/receiver selection, immutable endpoint/effective-input snapshots, mean-Earth great-circle geometry, AGL-only inclined distance, SHA-256 fingerprint, and durable result/summary persistence.

Still required to close P0:

- continued English-only enforcement and complete accessibility review;
- true process-death/system-flow and broad device restoration evidence;
- remaining project lifecycle work including hard-delete recovery/undo/export, artifact ownership/cleanup, and lineage/provenance policy;
- durable-job reconciliation-executor and UI integration beyond the delivered contract/store/shared-runner and API 23-33 WorkManager foundations, API 34+ UIDT, recovery/export UX, garbage collection, and the long-term operational-store decision;
- real offline geographic map;
- local DEM, terrain profile, ground-elevation-aware path geometry, Earth-curvature clearance/effective-Earth policy, LOS, and Fresnel clearance;
- portable/exported study manifest and artifact package beyond the delivered in-project bounded result;
- reproducible export manifest;
- connected smoke test in release validation.

### P1 — Capable mobile product

- **Delivered bounded slice:** release-managed national IBGE sector attributes and municipality summaries with offline verification/search; geometry and population-by-coverage are not delivered;
- **Delivered bounded slice:** explicit review-gated acquisition of the complete Anatel TV/FM `Canais.zip`, immutable raw/provenance snapshots, staged and atomic SQLite v1 publication, and bounded offline catalog queries; current metadata/license resolution, conditional refresh, background/process survival, retention management, and project/contour use remain;
- broader networks and scenarios beyond the delivered bounded immutable P.525-5 study snapshot;
- **Delivered bounded slice:** project HRP/VRP library; bounded PRN/ADT/V-Soft/PAT/native ATX JSON v2 interchange plus strict ATX Planner desktop JSON v1 antenna-pattern compatibility and explicit-plane generic table import; gain-bound identity V2 with exact canonical-artifact-aware deduplication; validity-gated CPU rectangular-array composition; sector assignment; and pattern-shaped broadcast radials; broader catalog/search, full 3D/GPU solving, project interchange, and P.525 integration remain;
- validated Hata/3GPP and selected diffraction methods;
- resource-bounded local coverage, best server, overlap, and C/(I+N);
- hardening of the delivered bounded regional acquisition foundation into a process-durable managed lifecycle;
- lightweight import/export, JSON/HTML report, GeoTIFF, and broader KMZ; bounded service-contour KMZ is already delivered as a non-regulatory visualization/evidence export;
- field measurements and basic comparison;
- offline help and sanitized diagnostics.

### P2 — Selective advanced capabilities

- **Delivered bounded reference:** a packaged CPU-only P.1546-6 land-table subset for non-regulatory FM/DTV contour plotting; general/terrain-aware P.1546 and independent parity remain blocked;
- ITM, P.1812, P.528, and FCC curves;
- clutter, buildings, census-sector geometry, and population-by-coverage;
- LTE/5G indicators, FWA, simulcast, and air-to-ground;
- calibration, PDF reports, and 3D antenna patterns;
- large-area coverage through native code or an optional service;
- strict mobile regulatory screening after terrain, pattern, numerical, persisted executable-study export, and legal approval; the bounded contour KMZ alone does not close that gate.

### P3 — Later exploration

- arbitrary antenna-array geometry editing and independently validated full-wave comparison beyond the delivered bounded rectangular CPU composer;
- complex GIS editing;
- real-time collaboration;
- urban ray tracing;
- `.rp3` binary round trip.

## 4. Mandatory gates

| Gate | Required output | Current state | Blocks |
|---|---|---|---|
| **G0 — Product and identity** | Product name, package, repository, license, privacy policy, English-only policy, and supported API range approved. | **Foundation:** package/API/repository and English-only baseline exist; license/privacy/release approval remains blocked. | Public release and irreversible contracts. |
| **G1 — Reproducible build** | Clean checkout runs unit tests, lint, debug APK, and test APK in CI with documented JDK/SDK. | **Delivered for debug baseline:** workflow and local artifacts exist. | Functional milestones if regression occurs. |
| **G2 — Application architecture** | UDF, ViewModel, Navigation 3, dependency assembly, error model, restoration, and observability demonstrated. | **Foundation:** explicit actions/effects, structured recovery, use cases, injected dispatchers, typed saved-state route tests, a pure regional job/state/reconciliation contract, a scheduler-neutral shared runner, and an API 23-33 WorkManager adapter exist. Feature splitting, DI/scoping policy, reconciliation-executor/UI integration, API 34+ UIDT, observability, accessibility, and true process-death/device flows remain. | Scaling feature count safely. |
| **G3 — Durable persistence** | Exported schema, migrations, safe writes, backup policy, recovery, and data ownership validated. | **Foundation:** current project schema 6, explicit legacy 1→2→3→4→5→6 migrations, schema-5 link-study history, a small atomic index, immutable verified project documents, serialized latest-catalog transactions, and a content-addressed artifact store with bounded antenna source/canonical references exist. Unreadable/future-index recovery/export, garbage collection, general attachment/ownership policy, backup, jobs, multi-process policy, Android interruption evidence, and the long-term store decision remain. | Real user projects and portable assets. |
| **G4 — Map and data** | Renderer, offline format, attribution, license, NoData, disk budget, and lifecycle approved. | **Foundation:** the tested coordinate viewport remains non-cartographic. The bounded national IBGE package is delivered, and the regional cache adds fixed-source planning/acquisition, license acceptance, 384 MiB planning, per-artifact limits, resumable GET staging, hashes/provenance, atomic inventory, TIFF metadata indexing, and tiny OSM-way-to-GeoJSON processing. G4 remains open for an authorized basemap, durable/general package lifecycle, bare-earth DTM and raster sampling, renderer/map integration, ownership/removal, performance evidence, and IBGE redistribution approval. | Terrain, field-map, polygon, and population-by-coverage claims. |
| **G5 — Numerical core** | Units, geodesy, FSPL, LOS, and Fresnel pass independent golden cases with tolerances. | **Foundation:** FSPL/noise/midpoint-Fresnel-radius, spherical mean-Earth endpoint geometry, complex HRP/VRP interpolation, and bounded coherent-array fixtures are tested. The P.1546 contour reference can shape ERP from an assigned HRP, but its AGL proxy, unapproved pattern/fallback policy, and missing independent parity do not advance the strict gate. DEM/terrain sampling, Earth-curvature clearance, LOS, Fresnel clearance, and an independent desktop/RadioPlanner parity bench are missing. | Terrain-aware or regulatory engineering label. |
| **G6 — Mobile compute** | Memory/time/battery budget, cancellation, blocking strategy, and device benchmarks. | Planned. | Raster coverage and heavy engines. |
| **G7 — Interoperability** | Android/desktop contract, capability negotiation, fixtures, and read/write matrix. | Blocked pending contract. | `.atxp` support. |
| **G8 — Advanced engines** | License, edition, runtime, validity domain, and reference vectors for each engine. | Blocked per strict engine. The P.1546 land-table subset is exposed only as a visibly non-regulatory reference and does not clear source/license, independent-vector, terrain/pattern, persistence, or regulatory gates. | Stable exposure of each advanced or regulatory model. |
| **G9 — Release** | Signed APK/AAB, device matrix, SBOM, notices, privacy, backup, upgrade, and support plan. | Blocked. | Public distribution. |

A gate cannot be satisfied by documentation alone. It requires executable artifacts and recorded evidence.

## 5. Phases

### F0 — Android foundation baseline

**State:** Foundation delivered; hardening work remains.

**Delivered:**

- production package `com.gecesars.atxplan`;
- API 23 minimum, target 36, compile 36.1;
- Kotlin/Compose/Material 3 baseline and custom theme;
- adaptive five-area shell;
- compact responsive feature information density with bounded physical Android 16 portrait checks at approximately 394 dp and font scales 1.15/1.30 plus a baseline landscape check; separate API 36 emulator validation covers Duplicate Project and Delete Project at font scales 1.0/1.30 in portrait and short landscape with Gboard open/closed, plus Archive Project/actions and the archived-project card in portrait at 1.0/1.30/2.0 and landscape at 1.30;
- Navigation 3 dependency and top-level display;
- store-schema-1 indexed project-schema-6 JSON repository and demonstration project; schema 5 remains the historical link-study introduction;
- free-space RF calculator plus the bounded persisted project-linked P.525-5 flow;
- CI workflow plus a current local aggregate with 0 lint errors, 16 warnings, 3 informational findings, and successful debug APK/Android-test compilation;
- current green 521-test JVM and 92-test Android 16/API 36 AVD aggregates, plus live Anatel archive/index/query evidence; only the preceding 18-test revision is proven on the physical reference phone;
- English production strings plus `EnglishOnlySourceTest` regression guard;
- application backup disabled while the data policy is incomplete.

**Remaining:**

- approve license, privacy, signing, and formal distribution model;
- extend the current Kotlin/XML/JSON/TXT English-only guard when additional user-visible resource types are introduced;
- document the supported physical-device matrix;
- resolve or explicitly accept dependency-version warnings;
- establish release changelog/versioning and reproducible release evidence;
- run connected instrumentation in a suitable CI/release lane.

**Exit evidence:** G0 decisions that remain open plus a maintained G1 baseline.

**Out of scope for this phase:** claiming terrain-aware RF, real maps, or desktop parity.

### F1 — Architecture hardening

**State:** Foundation implemented, not complete.

**Objective:** turn the existing simple ViewModel/repository/Nav3 structure into a restorable, testable application framework.

**Delivered foundation:**

- explicit `AppUiAction`, `AppUiEffect`, `AppProblem`, problem codes, and recovery actions;
- immutable `AppUiState` and `StateFlow` with lifecycle-aware collection;
- injected application use cases, storage/computation dispatchers, repository, clock, ID generator, and calculator boundary;
- cancellation-aware calculation and serialized durable catalog mutations evaluated against the latest catalog inside the repository transaction, with persist-before-publish state and no writes for rejected/no-op outcomes;
- ViewModel tests for loading, create/select/rename/duplicate/archive/restore/delete, latest-catalog rebase, mutation-completion accounting, save failure, retry, stale aggregate rejection, concurrency, invalid mutations, cancellation, and stale calculation results;
- serializable stable-ID `AtxRoute` keys and a saveable typed Navigation 3 back stack;
- safe unknown/malformed route fallback and nested project-name, RF-path, and RF-asset routes;
- saved-instance-state instrumentation for top-level, unknown, and nested editor routes;
- ViewModel factory and repository interface;
- storage recovery banner, retry action, and one-time notice effect;
- Navigation 3 `NavDisplay` with five top-level routes plus nested RF-path, project-name, and project-scoped RF-asset editors;
- compact/expanded navigation UI;
- shared screen components and custom design tokens;
- compact scalable typography, responsive Dashboard metric density, wrapping Projects cards, and 48 dp minimum targets for controls changed by the density pass.

**Remaining scope:**

- split the application-wide ViewModel into feature contracts as flows grow;
- approve dependency-injection/scoping and composition-root policy;
- deep links, deleted-ID recovery, adaptive list/detail, and route ownership contracts;
- true process-death, rotation, background/foreground, and device-matrix system tests;
- regional reconciliation-executor/UI integration beyond the delivered job model/store/reconciler/shared runner and API 23-33 scheduler foundation, API 34+ UIDT, sanitized diagnostics, correlation, and broader observability;
- accessibility semantics, focus, contrast, and text-scaling validation beyond the single reference device and its bounded orientation/font-scale checks;
- maintain English-only UI and diagnostics as features are added.

**Delivered component evidence:** serialized saved-instance-state restoration preserves stable top-level, nested RF-path, nested project-name, and nested RF-assets route IDs and safely handles unknown/malformed routes.

**Remaining exit demonstrator:** terminate and restore the application process through the Android system while the selected project and nested destination remain recoverable from durable IDs.

**Exit gate:** G2.

**Definition of Done:**

- no Composable accesses files, repository implementation, or calculator directly;
- no ViewModel owns an Activity or visual controller;
- navigation and selected durable state recover after process death;
- loading, empty, content, recoverable error, and retry states have tests;
- dispatchers and repositories are replaceable in tests;
- UI strings comply with the English-only policy.

### F2 — Complete project and RF entity lifecycle

**State:** Foundation implemented; store-schema-1 indexed project-schema-6 persistence, project lifecycle slices, independent network/site/sector/receiver CRUD, the bounded antenna artifact/assignment workflow, and the in-project P.525-5 result record are delivered as bounded capabilities.

**Objective:** evolve the current catalog/demo into editable, durable engineering projects.

**Delivered foundation:**

- schema-6 `ProjectCatalog`, `ArchivedProject`, and `PlannerProject`, with explicit legacy 1→2→3→4→5→6 migration; schema 5 introduced immutable project-link records;
- a small strict-UTF-8 atomic index backed by immutable SHA-256-addressed project documents; the index remains limited to 5 MiB and each project document to a conservative 8 MiB;
- commit ordering that makes project documents durable and verifies length/hash before publishing the index, preserving the previous reachable catalog when a document or index write fails;
- tests for legacy migration through schema 6, indexed document-before-index promotion, successful and failed index publication, schema-5 antenna-field injection removal, missing/corrupt/future documents or indexes, no-op document reuse, single-project document replacement, size limits, failed writes, and concurrent repository instances;
- a private content-addressed artifact-store foundation with bounded staging, optional expected SHA-256, immutable deduplication, availability/corruption checks, and bounded copying;
- validated engineering value objects for coordinates, frequency, bandwidth, power, gain, loss, distance, height, azimuth, and tilt with primitive JSON representation;
- receiver/CPE model plus backward-compatible receiver collection and nullable sector network reference;
- aggregate duplicate/reference validation for RF records, schema-6 antenna source/canonical artifacts, fixed-grid HRP/VRP and sector assignment, plus bounded structural GIS, scenario, coverage, regulatory, artifact, and import-provenance records; schema 5 remains the milestone that added immutable project-link records and required each to match one completed point-to-point summary;
- create/select/rename/archive/restore/delete project workflow, including a saveable project-name editor and transactional rename use case;
- compact adaptive project-duplication dialog and transactional use case that read the latest durable source, assign a fresh route-safe root project ID and fresh root timestamps, preserve the project-scoped nested graph/IDs/references/data/order, demonstration flag, and study timestamps, leave the source unchanged, append the copy, and select it;
- compact adaptive project-deletion dialog with exact `DELETE` confirmation and impact counts, plus a transactional use case that compares the complete reviewed aggregate with the latest durable aggregate, rejects stale or already-removed targets without a write, atomically removes the current project aggregate, preserves other projects and order, and selects the next project, previous project, or none deterministically;
- compact adaptive project-archive dialog and collapsible archived-project list with retained-data counts, timestamps, and accessible restore actions; the transactional use cases compare complete reviewed active/archive snapshots, retain the unchanged aggregate plus archive timestamp and original index, remove archived projects from active selection/metrics, deterministically reinsert and select a restored project, and reject stale/repeated/missing requests without writing;
- a generic latest-catalog mutation boundary that rebases every mutation inside the repository transaction, persists before publishing UI state, and returns the latest durable catalog for rejected/no-op outcomes;
- combined Add RF Path editor/use case that atomically adds one network, one site/sector, and one receiver with injected IDs and clock;
- independent RF CRUD transitions for network, site, sector, and receiver create/edit/delete with request receipts, exact expected-entity stale checks, project-wide RF ID collision checks, monotonic project timestamps, and no writes for unchanged/rejected outcomes;
- reference-aware deletion that blocks a network still used by sectors or receivers and reports those counts, while a non-empty site requires an explicit contained-sector deletion decision;
- a compact project-scoped Manage RF Assets screen with lazy network/site/sector/receiver sections, bounded editor/delete dialogs, 48 dp action minimums, explicit local-only/reference-impact language, and read-only disclosure of preserved per-network receiver compatibility profiles;
- JSON round trips preserve IDs, `Double` precision, explicit units, and network references;
- synthetic FM demo with one network, three sites, and two study summaries.

**Remaining scope:**

- functional scenario, general dataset/import, GIS, coverage, and regulatory workflows beyond their structural records; broader antenna catalog/3D/full-wave/P.525 integration; and study types beyond the bounded P.525-5 project-link record;
- artifact attachment/import UI, project ownership rules, project-deletion cleanup, export packaging, and garbage collection;
- staged migration of remaining legacy primitive entity fields to canonical unit types;
- hard-delete recovery/undo/export and source-project lineage and duplication-provenance policy;
- editing/removal of individual per-network receiver compatibility profiles, dependency impact from future study, scenario, antenna, GIS, and artifact records, richer conflict diagnostics, bulk edits, and undo;
- long-term decision for indexed JSON files versus Room/SQLite, including multi-process behavior and portable ownership;
- recovery/export workflow for preserved unreadable/future indexes and project documents;
- durable-job UI execution and retention/migration beyond the delivered bounded per-record store, shared runner, and API 23-33 WorkManager foundation; API 34+ UIDT; multi-process policy; late network-byte/checkpoint crash injection; Android `AtomicFile` interruption; and storage-exhaustion system evidence.

**Delivered vertical slices:** rename, duplicate, archive/restore, and hard-delete exact project snapshots through the latest durable transaction; migrate legacy monolithic or indexed catalogs through schema 5 to current schema 6 with current documents durable before index publication; update one project without rewriting peers; independently manage RF assets and linked deletion impact; create one linked RF path; append/reopen one immutable fingerprinted P.525-5 project-link result; and review/import or synthesize a canonical antenna pattern, retain its verified artifact references and calculation cuts, assign it to a sector, export it through SAF, and block deletion while referenced. Round trip preserves IDs, precision, units, links, result terms, hashes, cuts, and the canonical link fingerprint. Existing immutable studies retain the original calculation's snapshotted project identity when their aggregate is duplicated. The antenna artifact flow is bounded and does not supply a general attachment/package UI, ownership cleanup, garbage collection, or portable project export. The duplicated aggregate still has no separate source-project lineage marker. Local archive cannot recover permanent deletion and is not backup, export, synchronization, or artifact recovery.

**Bounded manual archive evidence:** on the Android 16/API 36 emulator, archive UI/actions and the archived-project card were reachable in portrait at font scales 1.0, 1.30, and 2.0 and in landscape at 1.30. A force-stop/relaunch retained the archived record; after restore, a second force-stop/relaunch retained that project as active and selected. This does not prove Android Backup, system-reclaim restoration, all process-death timings, or a device support matrix.

**Bounded manual project-link evidence:** at system font scale 1.30 on the same API 36 emulator, one stored sector/receiver study was calculated and saved, then reopened after a force-stop/relaunch with the same endpoint, scalar terms, P.525-5/geodesy identities, warnings, and fingerprint. This proves only the observed local-storage path.

**Remaining phase demonstrator:** cover project creation, RF CRUD, and the antenna artifact/assignment flow through true process termination/relaunch, then define general artifact attachment, ownership, portable export, and safe cleanup before enabling unrelated import workflows.

**Exit gate:** G3.

**Definition of Done:**

- round trip preserves IDs, precision, units, and references;
- migrations never rely on destructive fallback;
- interrupted writes preserve the last valid catalog/project;
- linked deletions show impact before confirmation;
- domain remains independent of Android, Compose, and persistence models;
- demonstration data stays explicitly synthetic.

### F3 — Geographic map and offline data

**State:** Geographic coordinate viewport, durable site move, bounded offline IBGE attributes, the review-gated Anatel Basic Plan catalog, fixed-source regional raw-data acquisition/processing, and regional job contract/store/reconciler/shared-runner foundations are delivered. The Anatel path performs one foreground whole-archive HTTPS refresh and atomically publishes an offline SQLite v1 generation; it has no background/process survival or automatic retention management. An API 23-33 WorkManager compatibility envelope can call the regional runner, but the production screen and reconciliation path do not submit or observe it. Path-keyed regional inventory schema 2, bounded schema-1 migration, and explicit live-snapshot caching are included; offline basemap, bare-earth DTM/raster sampling, append-only snapshot ownership, general packages, API 34+ UIDT, user-reachable scheduled/process-durable regional execution, late network-byte/checkpoint crash evidence, and sector geometry remain planned.

**Objective:** make the selected project geographically useful without a network in a prepared region.

**Delivered foundation:**

- pure Kotlin Web Mercator projection, antimeridian-aware project fit, pan, anchored pinch zoom, and metric scale with numerical tests;
- Compose coordinate-grid rendering of local sites and active-sector azimuths;
- touch selection plus accessible site-list, fit, reset, and center controls;
- a stale-safe location-only site mutation that preserves all unrelated stored fields and publishes only after durable commit;
- stored project elevation versus explicit `NoData` visualization, including disclosure that a move does not resample elevation;
- permanent in-app disclosure and attribution for the local grid, with no third-party tiles or basemap claim;
- a release-managed national IBGE 2022 package with strict manifest, exact source/asset hashes, known 21.1 MiB packaged and 67.6 MiB installed sizes, 16 MiB extraction allowance, private staged installation, SQLite validation, and superseded-version cleanup;
- a compact Data Catalog with explicit checking/installing/validating/ready/failed states, retry, offline normalized municipality search, population/area/situation summaries, `NoData`, source/CRS/license disclosure, and permanent geometry limitations;
- a separate Anatel TV/FM catalog section that requires source/attribution review before downloading the complete pinned `Canais.zip`, preserves a bounded immutable raw snapshot and provenance, builds a staged SQLite schema-v1 index, atomically switches the current generation only after validation, and supports offline service/state/channel/text queries with explicit `NoData`;
- a user-reviewed regional plan for fixed Copernicus GLO-30 DSM and ESA WorldCover COG tiles plus an optional experimental ways-only OSM `building`/`building:part` union, bounded to one degree overall and to much smaller building requests;
- fixed HTTPS hosts, same-origin redirects, 2,048-character limits for initial and resolved redirect URLs, a 384 MiB plan ceiling, source-specific artifact/response caps, disk preflight, provenance-valid GET partial resume, bounded transient GET/read-only-POST retry, 1-30-second `Retry-After` handling with no unbounded 429 replay, SHA-256, and atomic promotion;
- delivered path-keyed inventory schema 2 with nested family/release/type/format/catalog/query/normalizer and license/provenance snapshots, requested/effective route metadata, strict bounded same-origin HTTPS completion provenance and acquisition time for new transfers, plus bounded offline atomic migration of a valid primary or atomic-backup schema-1 inventory without inventing missing effective URL/time;
- application-wide in-process serialization of acquisition and inventory loads across `FileRegionalDatasetRepository` instances, explicitly without a multi-process locking claim;
- verified OSM reuse for at most 24 hours, stale refresh only during a new explicit acquisition, and a force-refresh checkbox that bypasses fresh cache without polling or background refresh;
- bounded TIFF/BigTIFF metadata-only indexing and deterministic WGS 84 building GeoJSON processing that retains the actual final endpoint, upstream OSM timestamp, and bounded raw building/height/level/roof tags, with permanent disclosures that no raster sample, bare-earth DTM, RF clutter coefficient, interpreted building height, relation, or hole support is produced;
- real-asset Android integration coverage for extraction, hashes, schema, counts, corruption recovery, low-storage rejection, update cleanup, reopen, and offline queries.

**Remaining scope:**

- cartographic renderer/package adapter decision and performance spike;
- drag placement and receiver editing on the map;
- authorized offline basemap format;
- general/user package inventory beyond the pinned IBGE package and fixed regional catalog;
- Storage Access Framework import;
- shared desktop/Android golden fixtures plus an append-only content-addressed regional snapshot index, pins, historical live-snapshot retention, ownership, and reference-aware cleanup beyond the delivered path-keyed schema 2;
- complete the durable lifecycle selected in ADR 0001 around the delivered one-artifact-at-a-time runner and API 23-33 constrained foreground [WorkManager long-running worker](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running) foundation: add Data-screen persist-before-enqueue/observation, API 34+ Android [user-initiated data transfer jobs](https://developer.android.com/develop/background-work/background-tasks/uidt), notification-permission UX and job navigation, late network-byte/checkpoint crash evidence, and process/reboot reconciliation, while preserving the dataset repository as the only provider-retry owner;
- CPU-only bounded COG block/window reads, content-addressed cropped grids, mosaicking, reprojection, pixel-level `NoData`, versioned DSM and categorical WorldCover adapters, HGT evaluation, and a bare-earth DTM decision;
- versioned interpreted-height rules and multipolygon relation/inner-ring support, only after cross-platform fixtures and a complete geometry contract; public Overpass remains tiny and user-triggered rather than a background service;
- dataset-backed map availability and pixel-level `NoData` visualization;
- census-sector polygon packaging, exact containment, map rendering, and population-by-coverage;
- current Anatel catalog-metadata and exact license resolution, conditional HTTP refresh, byte progress, background/process/reboot survival, automatic retention cleanup, snapshot diff, and project pin/application/contour integration; the current direct-source license remains `REVIEW_REQUIRED`;
- public redistribution approval for the embedded IBGE derivative.

**Delivered sub-slices:** (1) open offline coordinate viewport → fit/pan/zoom → select a site → inspect stored elevation availability → edit coordinates → persist after stale-safe durable commit; (2) open Data Catalog → install/verify the bundled IBGE database → search locally → inspect a municipality summary and explicit limitations; (3) acknowledge review of the official Anatel source/attribution → explicitly download the complete TV/FM archive → preserve its immutable raw/provenance evidence → atomically publish SQLite v1 → query the installed generation offline by service/state/channel/text; and (4) define a small envelope → review fixed sources/licenses/budget → acquire and validate raw regional data → inspect persistent hash/provenance/processing status while the app remains open.

These sub-slices do not complete the phase vertical slice because the map does not render an authorized basemap, sample a DEM, consume sector geometry, or calculate population by coverage.

The delivered semantic subset, remaining cross-platform golden fixtures, target sequence, and claim rules are specified in `docs/CROSS_PLATFORM_DATA_CONTRACT.md`. Scheduler selection and recovery are specified in `docs/adr/0001-android-regional-data-lifecycle.md` and remain **planned**; delivery of inventory schema 2 does not imply durable background work.

**Vertical slice:** open in airplane mode → view a real local map → select/move a site → inspect elevation availability → persist the edit.

**Exit gate:** G4.

**Definition of Done:**

- previously installed map data works in airplane mode;
- source attribution remains visible;
- no community tile server is used for unauthorized bulk download;
- hostile, truncated, or oversized files are rejected without corrupting the catalog;
- missing pixels remain NoData;
- disk cost is known before acquisition and cleanup respects active references.

### F4 — Antenna baseline, terrain, and project-linked link study

**State:** Manual free-space calculation and a bounded persisted project-linked P.525-5 slice are delivered; terrain-aware analysis, portable export, and the complete F4 workflow remain planned.

**Objective:** close the offline mobile engineering MVP.

**Delivered bounded foundation:**

- manual link parameter form;
- pure Kotlin input validation;
- searchable lazy stored project sector and network-compatible receiver selectors in the compact Studies screen;
- stale reviewed-project, missing-endpoint, incompatible-network, and ID-collision rejection before mutation;
- spherical mean-Earth great-circle endpoint geometry using a fixed 6,371,008.8 m radius, including horizontal distance, initial bearing, relative azimuth, and elevation angle;
- inclined distance computed as the hypotenuse of horizontal distance and the difference between the two antenna heights AGL over a flat reference; site ground elevation and terrain do not enter this value;
- FSPL using Recommendation ITU-R P.525-5 (11/2024);
- EIRP, received power, sensitivity margin;
- thermal noise floor and SNR;
- midpoint first Fresnel radius;
- immutable `ProjectLinkStudyRecord` snapshots of project/network/endpoints/effective RF inputs, geometry, result/provenance, warnings, and a canonical lowercase SHA-256 input/geometry fingerprint;
- persistence of the link record introduced by schema 5 and retained by current schema 6, appending it and its matching completed point-to-point summary through the latest-catalog transaction and publishing UI state only after durable commit;
- saved-result rendering for scalar terms, provenance, fingerprint, terrain `NoData`, permanent limitations, expandable complete persisted details, and lazy timestamp-ordered history;
- explicit disclosure that stored transmitter ground elevation is snapshotted but not evaluated and that DEM/terrain, Earth-curvature clearance/effective-Earth propagation, LOS, Fresnel clearance, diffraction, clutter, buildings, vegetation, atmospheric gas, rain, variability, and directional-pattern use in this P.525 result are absent;
- JVM tests for formulas, geometry, antimeridian handling, invalid inputs, compatibility profiles, fingerprints, strict round trips, antenna codecs/composition/catalog mapping, use-case outcomes, persistence failures, duplication history, migration through schema 6, WorkManager identity/races/cancellation, and the bounded pattern-aware broadcast-contour reference, plus compact Studies/antenna/map Compose cases and real-storage migration/job-store/runner evidence.

**Remaining scope:**

- apply the delivered canonical antenna model to the project-linked P.525 study with explicit azimuth/elevation/tilt conventions and provenance;
- DEM-backed ground elevations, geodesic path sampling, and terrain profile;
- Earth-curvature clearance/effective-Earth policy, LOS, and Fresnel clearance along the path;
- diffraction, clutter/building loss, and directional antenna-pattern loss;
- durable-job, progress, and cancellation policy for the full terrain workflow;
- terrain profile chart;
- artifact-backed request/execution/result packaging beyond the delivered in-project record;
- CSV/JSON export with versioned manifest and fingerprints;
- independent reference fixtures and a desktop/RadioPlanner parity bench for all new terms.

**Delivered sub-slice:** select a stored sector and compatible receiver → snapshot effective endpoint/RF inputs → derive mean-Earth horizontal geometry and AGL-only inclined distance → run P.525-5 → atomically persist → inspect/reopen the immutable fingerprinted result and explicit `NoData` warnings.

**Remaining phase vertical slice:** select sector/receiver → build a DEM-backed terrain profile → apply the Earth-curvature clearance/effective-Earth policy → run FSPL/LOS/Fresnel-clearance and approved loss terms → inspect intermediate terms → persist an artifact-backed execution → export → reopen and externally verify an identical result.

**Exit gate:** G5 plus the initial G6 resource budget.

**Definition of Done:**

- independent golden cases cover conversion, geodesy, FSPL, LOS, and Fresnel clearance;
- azimuth/elevation/tilt conventions are explicit and tested at boundaries;
- result records engine, version, effective inputs, units, datasets, warnings, and hashes;
- cancellation leaves the project consistent;
- exported package can be verified without internal app state;
- no RadioPlanner parity statement appears without a dedicated bench.

**Milestone:** F4—not the current bounded P.525-5 screen and in-project record—closes the offline mobile MVP.

### F5 — Scenarios and bounded local coverage

**State:** Planned. Coverage enums/demo summaries are only domain foundation. The dedicated transient broadcast-contour reference overlay is vector geometry from a radial table lookup, not a raster coverage engine and does not advance F5.

**Objective:** add scenario comparison and raster studies that respect device budgets.

**Scope:**

- immutable scenarios and result snapshots;
- Hata and/or 3GPP UMa only after validation;
- selected diffraction after numerical gates;
- tiled/cancelable small grid;
- power/field, best server, overlap, and C/(I+N);
- separate physical, classified, and rendered grids;
- point inspection and snapshot comparison;
- initial GeoTIFF export.

**Exit gates:** G6 and G8 for every engine beyond FSPL.

**Definition of Done:**

- app estimates cost and rejects over-budget configurations before allocation;
- work follows the documented pause/process-death policy;
- NoData never becomes zero, a class, or a best server;
- power aggregation uses the linear domain;
- GeoTIFF metadata passes an external reader;
- benchmark records time, peak memory, battery/thermal behavior, engine, and device.

### F6 — Interoperability, measurements, and reports

**State:** Planned for project/report interoperability; the strict ATX Planner desktop JSON v1 antenna-pattern subset is delivered, while `.atxp` work remains blocked by G7.

**Objective:** move supported work among Android, desktop, and open tools without silent loss.

**Scope:**

- project capability negotiation;
- a versioned portable ATX import package through SAF, bounded private staging, hashes, capability inspection, and an import-copy/loss report before any native `.rp3` parser;
- approved `.atxp` subset or documented portable ATX intermediate format;
- preserve and extend the delivered bounded desktop JSON v1 attenuation/phase antenna-pattern interchange without presenting it as project-format parity;
- read-only mode for unsupported project capabilities;
- CSV and GeoJSON import/export;
- field measurements with quality, timestamp, and source;
- broader GIS KMZ and PNG where prioritized; bounded service-contour KMZ is already delivered outside the antenna codec boundary;
- JSON/HTML report and sanitized diagnostics;
- searchable offline help.

**Exit gate:** G7.

**Definition of Done:**

- unsupported content is never silently discarded;
- every conversion reports read, unsupported, and transformed items;
- hostile-file and structural limits are tested;
- Android → desktop → Android fixtures preserve the declared subset;
- exports include schema, units, and version;
- portable ATX fixtures pass before native RadioPlanner work starts;
- `.rp3` remains blocked until its separate legal/provenance, hostile-input, restricted-parser, and approved-corpus gates pass.

### F7 — Selective advanced portfolio

**State:** Planned or Blocked per strict engine, dataset, and product use case. One bounded CPU-only Brazil broadcast-contour reference slice is delivered with `regulatory = false`; it does not clear the advanced-engine or regulatory gates.

**Candidate capabilities, not a commitment to deliver together:**

- the delivered packaged P.1546-6 land-table reference and pattern-shaped radial ERP must be hardened with independent vectors, a reproducible generation process plus independent hash/license review, terrain-derived HNMT, approved antenna-pattern/fallback policy, persisted executable-study export beyond the bounded KMZ, and cross-platform parity before any strict label;
- ITM, P.1812, general/terrain-aware P.1546, P.528, and FCC curves;
- clutter, buildings, census-sector geometry, and population-by-coverage;
- RSRP/RSRQ/EPRE/throughput, FWA, simulcast, and air-to-ground;
- measurement calibration;
- 3D antenna patterns and PDF reports;
- large-area coverage with native code or an optional service;
- current Brazilian protected/interference compliance, including point-to-point P.526 plus Assis and D/U evaluation.

**Entry gate per capability:** approved mobile use case, G8, device budget, license, and available data.

**Definition of Done per capability:**

- implementation and model edition are identifiable;
- official or independent vectors pass declared tolerance;
- local and remote backends share a contract and expose differences;
- remote use is explicit and lists data sent and retention policy;
- validity limits and warnings appear before and after execution;
- the delivered contour reference remains non-regulatory until formal numerical, data, legal, persistence, and export validation applies;
- unsupported `E(80,80)` and unavailable strict interference inputs remain `NoData` rather than inferred geometry or compliance.

### F8 — Hardening and release

**State:** Blocked by G0/G9 and unfinished product phases.

**Objective:** produce a secure, observable, upgradable, supportable Android distribution.

**Scope:**

- physical-device/API/form-factor matrix;
- accessibility, theme, font scale, orientation, language, and process-death tests;
- startup, memory, battery, thermal, storage, and adverse-network benchmarks;
- shrinker/obfuscation with verified rules;
- signing, key custody/rotation, and release provenance;
- SBOM, notices, license, privacy, backup, and retention policy;
- schema/dataset/engine update policy;
- crash recovery, diagnostics, and support;
- approved distribution channel.

**Exit gate:** G9.

**Definition of Done:**

- release candidate passes clean install, supported upgrade, and recovery path;
- migration preserves representative anonymized projects;
- no secret or unlicensed dataset enters APK/AAB;
- declared offline workflows pass in airplane mode;
- permissions are minimal and justified in context;
- the English-only policy passes automated and human review;
- store/distribution, privacy, and support checklists have owner approval.

## 6. Actionable backlog

| ID | Work | Status | Priority | Dependency | Completion evidence |
|---|---|---:|---:|---|---|
| MOB-001 | Establish production package and Android API range | Delivered | P0 | — | `com.gecesars.atxplan`, API 23–36.1 build. |
| MOB-002 | Establish debug build/lint/unit-test CI | Delivered | P0 | MOB-001 | GitHub Actions workflow and local reports/artifacts. |
| MOB-003 | Implement adaptive Compose shell | Delivered | P0 | MOB-001 | Five destinations on compact/expanded layout. |
| MOB-004 | Introduce Navigation 3 top-level display | Foundation | P0 | MOB-003 | Typed stable-ID save/restore and nested-editor tests exist; deep links and true system process-death/device flows remain. |
| MOB-005 | Introduce ViewModel/StateFlow/repository | Foundation | P0 | MOB-003 | Explicit actions/effects, structured recovery, injected use cases/dispatchers, transactional mutations, and ViewModel tests exist; feature splitting, DI/scoping, jobs, and observability remain. |
| MOB-006 | Implement versioned indexed project persistence | Delivered baseline | P0 | MOB-005 | Current project schema 6 uses a small store-schema-1 `AtomicFile` index and immutable SHA-256 project documents; legacy 1→2→3→4→5→6 migration, schema-5 link-study preservation, current-document promotion, size/hash checks, latest-catalog transactions, and failed-publication tests are delivered. |
| MOB-007 | Implement project create/select and demo | Delivered | P0 | MOB-006 | User project creation and synthetic seeded demo. |
| MOB-008 | Implement pure Kotlin RF baseline | Delivered | P0 | domain baseline | FSPL/EIRP/received/noise/SNR/Fresnel-radius tests. |
| MOB-009 | Enforce English-only product language | Delivered baseline | P0 | all layers | English production sources/tests plus automated regression scan. |
| MOB-010 | Harden restorable typed navigation | Foundation | P0 | MOB-004 | Stable-ID save/restore and malformed/nested route tests delivered; deep links, deleted IDs, process termination, and tablet matrix remain. |
| MOB-011 | Define durable schema evolution | Foundation | P0 | MOB-006 | Schema 6, non-destructive legacy migration through schema 6, schema-5 link-study history, the atomic index, and immutable project documents are delivered; garbage collection, portable ownership, backup, unreadable/future-index recovery/export, and multi-process ADR work remain. |
| MOB-012 | Complete RF value types and entity CRUD | Foundation | P0 | MOB-007/011 | Typed values, project lifecycle, Add RF Path, and independent network/site/sector/receiver create/edit/delete with stale and RF-reference checks are delivered. Bulk operations, undo, future-record dependency impact, hard-delete recovery/export, artifact policy, and lineage/provenance remain. |
| MOB-013 | Run geographic map spike | Foundation | P0 | license gate | Coordinate renderer, camera/gesture math, attribution boundary, selection, and durable site move are delivered and tested. Cartographic renderer/package lifecycle, authorized source, airplane-mode package proof, and performance report remain. |
| MOB-014 | Define offline catalog/package format | Foundation | P0 | MOB-013 | The bundled IBGE format and fixed-source regional inventory provide bounded install/acquisition, validation, hashes, provenance, and atomic publication. General packages, portable ownership, removal, and garbage collection remain. |
| MOB-015 | Implement DEM adapter and terrain profile | Planned | P0 | MOB-014 | Golden profile with NoData and provenance. |
| MOB-016 | Add geodesy/LOS/Fresnel clearance | Foundation | P0 | MOB-012/015 | Bounded mean-Earth great-circle endpoint distance/bearing and AGL-only inclined distance are delivered and unit-tested. DEM-backed ground elevations, Earth-curvature clearance/effective-Earth policy, LOS, Fresnel clearance, and independent numerical fixtures remain planned. |
| MOB-017 | Persist and export link study manifest | Foundation | P0 | MOB-011/016 | The project-link record introduced by schema 5 and retained by schema 6 preserves immutable endpoint/effective-input snapshots, geometry, P.525-5 result/provenance, warnings, and a canonical SHA-256 fingerprint; JSON round trip and failed-save behavior are tested. Artifact-backed packaging, portable manifest, export, and external verification remain planned. |
| MOB-018 | Close mobile MVP vertical slice | Planned | P0 | MOB-010–017 | Automated offline create→calculate→save→export→reopen flow. |
| MOB-019 | Define `.atxp` mobile/desktop contract | Blocked | P0 | desktop/mobile schemas | Capability matrix and fixtures. |
| MOB-020 | Benchmark small coverage | Planned | P1 | MVP/G5 | Per-device resource budget and engine decision. |
| MOB-021 | Compare Kotlin and native compute | Planned | P2 | MOB-020 | Numerical/performance report and ADR. |
| MOB-022 | Specify optional compute service | Planned | P2 | proven demand | Contract, privacy, authentication, retention, local fallback. |
| MOB-023 | Add connected test to release lane | Planned | P0 | device infrastructure | Android 16+ smoke result stored with release evidence. |
| MOB-024 | Validate compact phone information density | Delivered baseline | P0 | MOB-003 | One physical Android 16 phone has portrait evidence at approximately 394 dp, density 520, and font scales 1.15/1.30 plus a baseline landscape check. Separate API 36 emulator evidence covers Duplicate Project/Delete Project at font scales 1.0/1.30 in portrait and short landscape with Gboard open/closed, plus Archive Project/actions and the archived-project card in portrait at 1.0/1.30/2.0 and landscape at 1.30; the full device/accessibility matrix remains F8 work. |
| MOB-025 | Establish content-addressed artifact storage | Foundation | P0 | MOB-011 | Bounded staging, expected-hash validation, SHA-256 deduplication, availability/corruption checks, and copying are delivered. The antenna workflow consumes immutable source/canonical artifacts and performs SAF export; general attachment/packaging, ownership, deletion cleanup, and garbage collection remain. |
| MOB-026 | Implement regional inventory schema 2 and bounded v1 migration | Delivered bounded slice | P0 | MOB-014 | Path-keyed schema 2 now stores nested family/release/type/format/catalog/query/normalizer and license/provenance metadata plus requested/effective route/acquisition fields. A valid primary or atomic-backup v1 inventory migrates offline and atomically; a valid backup replaces an invalid primary, while unavailable effective URL/time remain unknown. Shared desktop fixtures and append-only history/pins are separate work. |
| MOB-027 | Implement durable regional job lifecycle | Foundation | P0 | MOB-026 | **Delivered MOB-027A:** passive canonical E6 plan; semantic and Android-execution SHA-256 goldens; exact license snapshots; strict state, monotonic mutation and CAS validation including future-artifact checkpoint rejection; bounded per-job private `AtomicFile` JSON; contextual terminal/nonterminal artifact-outcome auditing with a guarded non-mutating invalid-terminal report; and pure decisions whose record-generation guards are separate from concrete scheduler targets, whose bounded complete snapshots reject physical-target reuse and deterministically cancel extra stale/current entries, whose recordless cancels explicitly expect absence while unreadable IDs remain preserved, and whose exhausted recovery generation becomes typed orphaning. **Delivered MOB-027B:** a scheduler-neutral runner binds one previously persisted job to its exact scheduler ownership, rebuilds the canonical fixed-catalog plan, executes artifacts sequentially, persists provider-attempt permits before transport, preserves the dataset repository as the sole provider-retry owner, honors durable cancellation/system stop, and links only exact inventory-fingerprinted outcomes through CAS. **Delivered MOB-027C foundation:** fingerprint-bound deterministic WorkManager input/tags/UUID; API 23-33 gated constrained `KEEP` scheduling; active-only retained acknowledgement; finished-work race isolation; foreground `dataSync` worker and physical-request-derived compact notification; durable cancel-first exact-UUID action without premature terminalization; bounded fail-closed snapshots; and focused JVM/API 36 integration evidence. **Planned:** atomic reconciliation executor/drain completion, Data-screen persist-before-enqueue and observation, API 34+ UIDT, permission/denial UX, job-specific navigation, late network-byte/checkpoint crash evidence, production entry points, API 23/33 runtime proof, and process/reboot evidence on both API paths. |
| MOB-028 | Implement bounded CPU COG windows and data adapters | Planned | P0 | MOB-026/027 | Verified Copernicus/WorldCover block decode, cropped grids, pixel `NoData`, DSM/category sampling, malformed-input/resource tests, and cross-platform golden values. No DTM/RF-loss claim. |
| MOB-029 | Implement bounded current OSM snapshot cache and building metadata | Delivered foundation | P1 | MOB-026 | Ways-only `building`/`building:part` query; 24-hour verified reuse and force refresh; actual final endpoint; query/normalizer/source/acquisition time; bounded raw height/level/roof tags. Current-path replacement only; no polling, interpreted height, relations/holes, history, pins, or cleanup. |
| MOB-030 | Implement portable ATX import before native RP3 | Planned | P0 | MOB-019/025 | SAF import-copy, bounded/hash-verified package, capability/loss report, unknown preservation, and hostile-package tests. Native `.rp3` remains blocked. |
| MOB-031 | Add content-addressed regional snapshot history and pins | Planned | P0 | MOB-025/026/029 | Versioned migration from path-keyed v2; append-only raw/derived hash index; project/study/job pins; safe refresh history; reference-aware removal/recovery. |
| MOB-032 | Add versioned OSM geometry and height semantics | Planned | P1 | MOB-029/031 | Cross-platform units/levels/fallback fixtures and a complete multipolygon outer/inner-ring contract before any interpreted-height or obstruction claim. |
| MOB-033 | Add Brazil broadcast-contour reference plotting | Delivered bounded slice | P2 | MOB-012/013 | CPU-only source-pinned FM `E(50,50)` and derived first-generation DTV `E(50,90)` references, FM `E(50,10)` statistical screening, `E(80,80)` `NoData`, 72 radial pattern-shaped ERP with explicit fallback/zero-field behavior, packaged-table provenance, distinct compact map states, and deterministic bounded KMZ export with a radial-evidence manifest are delivered. Strict terrain/HNMT, approved pattern policy, independent parity, persisted executable studies, P.526+Assis/D-U interference, and regulatory approval remain blocked. |
| MOB-034 | Deliver bounded antenna pattern composer and interchange | Delivered bounded slice | P1 | MOB-012/025/033 | Canonical complex HRP/VRP, bounded convention-safe PRN/ADT/V-Soft/PAT/native ATX JSON v2 interchange, strict ATX Planner desktop JSON v1 attenuation/phase compatibility, deterministic two-file HRP/VRP pairing, complex-vector duplicate averaging, explicit-plane generic table import, KML/KMZ rejection, artifact-correlated and destination-verified SAF export with surfaced format-loss warnings, gain-bound normalized-content identity V2 plus exact canonical-artifact-aware deduplication, schema-6 source/canonical artifacts and fixed grids, and converged/budgeted CPU rectangular-array synthesis with spatially separated peak seeds and a 0.1 dB HRP-versus-3D peak representation gate are implemented. Desktop/PRN one-cut imports remain pairable; full desktop JSON export requires real HRP/VRP/frequency/gain, desktop three-column PRN phase/gain is retained or explicitly substituted with warning, and known V-Soft tilt is preserved on export. Full-wave/3D measurement, GPU solving, `.atxp`/`.rp3` project parity, ADT placement/power/phase-offset application, mutual coupling, P.525 integration, general artifact lifecycle, and regulatory validation remain. |
| MOB-035 | Add on-demand Anatel Basic Plan indexed catalog | Delivered bounded slice | P1 | MOB-014/027/031 | Delivered: pinned official Mosaico descriptor, explicit source/attribution review gate with license still `REVIEW_REQUIRED`, whole-archive allowlisted HTTPS acquisition, immutable SHA-256 raw/provenance snapshots, bounded hostile ZIP/XML parsing, staged and atomically published SQLite v1 generations, prior-current preservation, bounded core service/state/municipality/channel/frequency/text/Basic Plan ID queries, and a compact offline service/state/channel/text UI with explicit phases and replacing 25-record previous/next pages. No official archive is bundled. Remaining: current dados.gov.br metadata/license resolution, conditional GET, byte progress, background/process/reboot survival beyond the route-scoped ViewModel, automatic cleanup after the raw eight-archive/512 MiB or index eight-file/768 MiB ceilings, snapshot diff/update management, and exact project pin/application/contour integration. |

## 7. Definition of Ready

A product story may enter implementation when:

- user and expected outcome are defined in English;
- status and priority are recorded in `APPLICATION_MAP.md`;
- entry gates and dependencies are satisfied;
- domain model, units, errors, and unsupported behavior are specified;
- data source and license are known;
- offline, accessibility, privacy, and process-death behavior are considered;
- numerical work has fixtures and tolerance before implementation;
- schema and migration impact are evaluated;
- resource cost can be estimated for map, raster, or compute work.

## 8. Global Definition of Done

Every increment must:

1. compile in CI and preserve existing gates;
2. add tests at the appropriate layer;
3. avoid heavy CPU, file, database, or network work on the main thread;
4. expose loading, empty, error, retry, progress, and cancellation states where applicable;
5. preserve accessibility, adaptive layout, font scaling, and state restoration;
6. keep local data authoritative or document the exception;
7. version schema, engine, dataset, and output format where applicable;
8. record units, provenance, warnings, and validity limits for engineering results;
9. update status documentation without claiming future work;
10. pass security review for external input, network, files, and secrets;
11. measure performance when changing maps, rasters, persistence, or engines;
12. demonstrate the flow on a supported emulator/device;
13. contain only English user-facing product text and documentation.

## 9. Test strategy by milestone

| Milestone | Mandatory tests |
|---|---|
| Existing foundation | Domain/RF/persistence/use-case/form/ViewModel/language JVM suites, lint/debug artifacts, Dashboard-to-Studies smoke, and typed route saved-state instrumentation. |
| Architecture hardening | ViewModel/UDF, navigation restoration, process death, adaptive UI, accessibility. |
| Persistence | Repository, migration, failed write, full disk, concurrency, backup/restore policy. |
| RF entities | Unit value types, property tests, validation, serialization round trip. |
| GIS/data | Delivered Android tests cover provider contracts, half-open bounds, schema-2 route/source snapshots, bounded primary/atomic-backup-v1 migration and recovery, 24-hour/force-refresh cache, failed-refresh preservation, actual endpoint, OSM raw tags/source timestamp, `Retry-After`, redirect-origin security, and compact Catalog states. Shared desktop/Android identity/`NoData` goldens, append-only history/pins, COG block/pixel goldens, and broader hostile/recovery cases remain. Packaged IBGE extraction/hash/schema/query/storage-preflight/corruption-recovery tests remain separate. |
| Anatel Basic Plan catalog | Current domain/ViewModel tests cover bounded query validation, domain `NoData` to UI-phase mapping, the source-review gate, dirty filters, and replacing 25-record previous/next pages. Instrumented repository tests exercise a synthetic complete refresh through immutable raw/provenance storage and SQLite publication, offline reducing queries, and failed staged-parse preservation of both the prior current index and the newly verified raw evidence; compact UI instrumentation exercises the review gate and visible states. Live official-source behavior, metadata/license resolution, conditional requests, retention-full recovery, process/background survival, project pins/diffs, and regulatory integration remain outside that evidence. |
| Durable regional jobs | Delivered foundation evidence: canonical E6 semantic/execution goldens, exact accepted licenses, provider attempt ceilings, checkpoint promotion plus future-artifact rejection, record-and-canonical-artifact contextual auditing of terminal and nonterminal committed outcomes, guarded non-mutating invalid-terminal reports, scheduler generations, cancel-first revision/fingerprint/expected-record-generation guards separated from concrete target kind/plan-fingerprint/generation/identity, physical-target uniqueness, deterministic cancellation of extra stale/current targets in a bounded complete snapshot, unreadable-ID scheduler-entry preservation, expected-record-absent cancellation guards, typed generation-exhaustion orphaning, idempotent create, overlapping-path rejection, unreadable-record fail-closed ownership, single-winner CAS, real `AtomicFile` rollback/backup recovery, corrupt/future/oversized isolation, active/finished scheduler reconciliation, and no success inference. Snapshot-only cancellation never emits `MARK_CANCELED`. Focused runner evidence adds exact inventory-entry fingerprint goldens, stale scheduler rejection before dataset access, sequential artifact execution, provider-attempt permits before transport, remaining-attempt clamping, durable cancellation/system-stop behavior, committed-inventory adoption after outcome CAS conflict, and success only from complete inventory-backed outcomes. MOB-027C evidence adds strict three-field input mirrored in fingerprint-bound WorkInfo tags, recomputed deterministic UUIDv8, generation-scoped `KEEP`, connected/storage constraints, active-only retained acknowledgement, finished-work race isolation, a fingerprint-carrying bounded snapshot, foreground `dataSync` execution, physical-request notification identity, notification-visibility rechecks, and durable exact cancellation that remains pending until terminal evidence. Remaining exit evidence: atomic reconciliation executor and drain completion; collision-proof notification-ID allocation; Data-screen persist-before-enqueue and observation; API 34+ UIDT selection; notification-permission/denial UX and job navigation; late network-byte/checkpoint/outcome crash injection; API 23/33 scheduler runtime; process/reboot reconciliation; and strong-ETag partial recovery under the production scheduler path. |
| Antenna patterns | Current: cyclic/clamped complex interpolation, complex-vector duplicate-angle averaging, coordinate-frame and normalization validation, explicit `NoData`/`Unsupported`, converged/budgeted coherent-array steering/directivity, convention-safe PRN, ADT/V-Soft/PAT/ATX JSON v2 detection and round trips, deterministic HRP/VRP source pairing, explicit-plane generic table import, KML/KMZ rejection, hostile-input bounds, gain-bound normalized-content identity V2, schema-6 fixed-grid mapping, artifact-correlated export, stale-safe assignment/deletion, pattern-shaped ERP exactly once, explicit nominal fallback, and zero-field radial `NoData`. Generic tables intentionally remain import-only. Remaining: independent vendor corpus and broader structured-format fidelity evidence, ADT placement/power/phase-offset application, fuzz/property testing, wider convergence/performance characterization, process-death SAF evidence, broader device/accessibility evidence, P.525 pattern use, full 3D/full-wave comparison, and regulatory approval. |
| Link study | Current: mean-Earth geometry/antimeridian, AGL-only incline, P.525-5 terms, canonical fingerprint, strict round trip, stale/missing/incompatible inputs, persist-before-publish/failure rollback, and compact Compose behavior. Remaining: independent terrain/Earth-curvature-clearance/LOS/Fresnel-clearance goldens, cancellation, artifact packaging, export manifest, and parity bench. |
| Broadcast contour reference | Profile/band thresholds, DTV `E(50,90)` transform, packaged table identity and selected values, peak and pattern-shaped radial ERP conversion, boresight/periodic interpolation/no-double-gain fixtures, explicit fallback and zero-field `NoData`, 72 bearings, geodesic closure, deterministic fingerprint, model-boundary incomplete state, compact status rendering, and deterministic bounded KMZ evidence export. Independent official goldens, terrain HNMT, approved pattern/fallback policy, persisted executable study/export, external-reader evidence, P.526+Assis/D-U, performance, and regulatory approval remain exit evidence. |
| Coverage | Golden grids, edges/NoData, memory, time, cancellation, export. |
| Interoperability | Cross-schema fixtures, capability negotiation, parser fuzz/limits. |
| Release | Install/upgrade, offline, accessibility, performance, security, signing. |

## 10. Risks and mitigation

| Risk | Impact | Mitigation/gate |
|---|---|---|
| Later project schemas evolve without explicit migrations | Project loss or lockout | Fixture-backed legacy 1→2→3→4→5→6 migrations, schema-5 link-study preservation, schema-5 antenna-injection stripping, and versioned index/document envelopes are delivered; G3 still requires a published evolution, ownership, recovery, export, and backup policy plus fixtures for every future public schema. |
| Content-addressed files are mistaken for a complete artifact lifecycle | Orphaned storage, unsafe cleanup, or false import/export claims | Keep the store at Foundation: do not expose import or deletion until attachment ownership, reference discovery, export, recovery, and garbage-collection rules are implemented and tested. |
| Local archive is mistaken for backup or hard-delete recovery | Irrecoverable project loss | Keep archive/restore distinct from permanent deletion, which still requires exact `DELETE`; archive/restore preserve exact local aggregates transactionally but cannot recover permanent deletion and do not provide backup, export, synchronization, or external-asset recovery. |
| Catalog mutation policy diverges across processes or future stores | Newer state can be overwritten | Every in-process mutation now rebases on the latest catalog inside a serialized repository transaction and publish follows persistence; define multi-process/conflict policy before another writer or store is introduced. |
| Saved-instance-state route tests are treated as complete process recovery | Selected durable state or nested context can still be lost after system termination | G2 still requires true process-death, deleted-ID, rotation, and device-matrix flows. |
| Coordinate viewport or IBGE envelopes are mistaken for a basemap, boundary, or GIS result | Incorrect geographic expectations | Keep permanent no-basemap/no-geometry disclosures; G4 remains required before field-map, terrain, exact containment, or population-by-coverage claims. |
| Bounded free-space result is mistaken for terrain-aware engineering or full RadioPlanner parity | Invalid field decision | Keep the mean-Earth/AGL-only method and absent DEM, curvature clearance, LOS/Fresnel clearance, diffraction, clutter, patterns, coverage, `.rp3`, and parity terms explicit in UI/docs; G5 and the dedicated parity bench remain mandatory. |
| Packaged P.1546 contour reference is mistaken for a protected/interference regulatory result | Invalid filing, coordination, or service decision | Keep every overlay `regulatory = false`; expose the AGL proxy, applied pattern or explicit nominal fallback, unused VRP/tilt, table/ruleset identity, statistical basis, warnings, incomplete/`NoData`, and the absence of independent parity, P.526+Assis/D-U, immutable export, authoritative Basic Plan evidence, and legal approval. |
| In-project result is mistaken for a portable manifest or export | Evidence cannot be verified outside app state | Keep the link record introduced by schema 5 and retained by schema 6 labeled as local bounded persistence; F4 still requires artifact-backed packaging, app/build metadata, export schemas, and external verification. |
| Separable antenna cuts or CPU synthesis are mistaken for measured/full-wave truth | Invalid gain, coverage, or filing decision | Keep `E/Emax`, phase, coordinate frame, source hash, normalization, structured per-cut availability, display-only missing-plane placeholders, mutual-coupling/full-wave exclusions, and non-regulatory status visible; prevent placeholders and legacy-unverified hashes from reaching calculation/export, and require independent fixtures plus an approved pattern policy before strict use. |
| An installed Basic Plan catalog is mistaken for authorization evidence or a regulatory input | Stale, unlicensed, or unauditable channel decisions | Keep source review, verified raw acquisition, indexed generations, project pins, snapshot comparison, and regulatory use as separate gates. The delivered catalog preserves provenance and publishes only after validation, but its license remains `REVIEW_REQUIRED`, it does not resolve current official metadata, and no row is applied to a project, study, or contour. |
| Package/schema published too early | Expensive compatibility burden | G0/G3 before public release. |
| Dataset size causes storage/ANR failure | Abandonment or corruption | The bundled IBGE slice has a 67.6 MiB installed size plus 16 MiB preflight allowance. Anatel refresh streams a maximum 64 MiB archive and fails visibly instead of evicting when eight immutable raw generations/512 MiB or eight indexes/768 MiB are full; each index is capped at 256 MiB, and the path still lacks progress, background execution, and retention management. Regional plans are capped at 384 MiB with per-artifact/Overpass bounds, streaming I/O, disk preflight, and eligible GET resume. General packages, durable scheduling, ownership, reference-aware removal, and garbage collection remain. |
| Current catalog or endpoint evolution makes historical regional records unreadable | Cache loss or unverifiable provenance after upgrade | Delivered inventory schema 2 preserves nested source/license/provenance and requested/effective route metadata for new acquisitions and migrates a valid primary or atomic-backup v1 inventory offline/atomically while leaving absent effective URL/acquisition time unknown. A valid backup replaces an invalid primary. The inventory is still path-keyed and migrated fields absent from v1 come from the known legacy catalog mapping; shared evolution fixtures and append-only history/pins remain planned. |
| Raster computation overheats device | Process death or poor UX | G6, blocks, cancellation, benchmark, optional service. |
| JNI introduced prematurely | ABI crashes and maintenance load | Native only after Kotlin baseline and MOB-021. |
| Optional service becomes hidden dependency | Offline failure and privacy risk | Explicit consent, local result, documented fallback. |
| Tiles/data lack distribution rights | Release block | G0/G4; artifact-level license and attribution. IBGE public redistribution remains blocked until applicable terms and NOTICE retention are approved. |
| Import parser accepts hostile files | Corruption or resource exhaustion | Structural limits, defensive parsing, fuzz tests. |
| Mixed-language UI returns | Inconsistent product and tests | English-only check in CI and review checklist. |
| “Complete parity” becomes the milestone | Unbounded roadmap | P0/P1/P2 boundaries and explicit parity rule. |

## 11. Metrics

Record for each release candidate without turning unmeasured targets into claims:

- build, lint, unit, and instrumented-test success;
- cold/warm startup by reference device;
- frame time and jank on shell, forms, and map;
- time and peak memory for profile, link, and reference grid;
- cancellation acknowledgement time;
- battery and thermal behavior during sustained work;
- APK/AAB, database, cache, and offline-package sizes; this candidate records the IBGE asset at 22,133,986 bytes and installed database at 70,926,336 bytes;
- project migration success rate;
- numerical tolerance by engine and fixture;
- percentage of P0 workflow passing in airplane mode;
- crashes/ANRs only under an approved telemetry/privacy policy;
- English-only validation failures.

## 12. Replanning rule

At the end of each phase:

1. review evidence, metrics, and risks;
2. update `APPLICATION_MAP.md` statuses in the same change set;
3. decide whether the next gate remains valid;
4. reorder P1/P2 using observed mobile value without expanding the MVP automatically;
5. record irreversible or expensive decisions in ADRs;
6. preserve published data compatibility or provide an explicit migration;
7. verify that no completed phase is described more broadly than its tested behavior.
