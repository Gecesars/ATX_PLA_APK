# Android Roadmap

> Evidence baseline: August 27, 2026. The repository now contains an adaptive Compose shell, typed and saveable Navigation 3 routes, explicit UDF/application boundaries, a schema-4 project model stored through a small atomic index and immutable SHA-256 project documents, explicit legacy 1→2→3→4 migrations, transactional project lifecycle operations, independent network/site/sector/receiver CRUD, a compact Manage RF Assets UI, a content-addressed artifact-store foundation, a bounded RF calculator, and a verified offline IBGE 2022 national attribute/municipality index, plus CI, JVM tests, and Android instrumentation. This roadmap does not treat those foundations as complete desktop or RadioPlanner parity.

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
| Build and lint | Delivered | Debug/test APKs built; latest lint has 0 errors and 12 dependency/tooling warnings. | Dependency/toolchain update advisories and no signed release. |
| CI | Delivered | GitHub Actions runs unit tests, lint, debug APK, and debug test APK builds. | Connected Android test is not in CI. |
| Compose theme and shell | Delivered | Custom light/dark ATX theme, Material 3, edge-to-edge, compact bottom bar, expanded navigation rail. | Full accessibility, locale enforcement, and device-matrix validation. |
| Compact phone information density | Delivered baseline | Scalable compact typography and shared components, 12–16 dp feature gutters, responsive metrics/fields/cards/map height, a short-height rail, IME resizing for the project-name editor, and explicit 48 dp minimums on changed controls have manual evidence on one physical Android 16 phone: 1280 × 2772 pixels, density 520, portrait at font scales 1.15/1.30 and landscape at 1.15. Duplicate Project and Delete Project were separately validated on an Android 16/API 36 emulator at 1080 × 2400 pixels and 420 dpi, in portrait and short landscape at font scales 1.0/1.30 with Gboard open and closed. Archive Project, its actions, and the archived-project card were reachable in portrait at font scales 1.0/1.30/2.0 and in landscape at 1.30. Manage RF Assets and the IBGE Data Catalog each have a 360 × 480 dp/font-scale-1.30 automated reachability check; the Catalog test covers Ready state, search, selection, and permanent limitations plus preparation, failure/retry, and query-failure states. | These bounded physical-device and emulator observations are not a complete OEM/API/aspect-ratio/theme/font-scale/accessibility matrix; the app does not clamp or override system font scale. |
| Navigation 3 | Foundation | Serializable stable-ID route keys, a saveable typed `NavBackStack`, bounded unknown-route fallback, and nested RF-path, project-name, and project-scoped RF-asset routes are implemented. Saved-instance-state restoration tests cover all three nested route families. | Deep links, deleted-ID recovery UX, adaptive list/detail, and true system process-death/rotation testing across the device matrix remain. |
| UDF/ViewModel | Foundation | Explicit `AppUiAction`/`AppUiEffect`, structured problem/recovery values, injected use cases/dispatchers, cancellation-aware calculation, and serialized catalog mutations exist. The Data Catalog has a separate feature ViewModel with preparation and debounced query states. Every project-catalog mutation is evaluated against the latest durable catalog inside the repository transaction; state is published only after persistence, while rejected/no-op outcomes rebase to the latest catalog without writing. | Additional feature-level ViewModels, cross-instance catalog observation, the DI/scoping decision, durable jobs, broader observability, accessibility, and system recovery evidence remain. |
| Project persistence | Delivered baseline | Strict UTF-8 JSON now stores project schema 4 through a small `AtomicFile` index and immutable SHA-256-addressed project documents. Legacy schemas 1–3 migrate before the index becomes authoritative; document length/hash checks, failed document/index publication, no-op reuse, and single-project document updates have JVM coverage. | Recovery/export UX for unreadable/future indexes, garbage collection, multi-process policy, Android storage-exhaustion/interruption evidence, backup, portable ownership, and the long-term indexed-files-versus-Room decision remain. |
| Artifact store | Foundation | A private content-addressed store stages, operation-bounds, SHA-256-verifies, deduplicates, checks, and copies immutable bytes. Schema 4 can persist bounded artifact references. | There is no import or attachment workflow, artifact-management UI, garbage collection, export package, project-deletion cleanup, or user/regional dataset acquisition. The separate release-managed IBGE package does not deliver the engineering workflows represented by artifact roles. |
| Project workflow | Foundation | Load, create, select, rename, duplicate, archive, restore, hard-delete, and display projects; seed the synthetic demo; add one linked RF path; and independently create/edit/delete networks, sites, sectors, and receivers through latest-durable transactions. Exact expected-entity checks reject stale RF edits/deletes. Network deletion is blocked while sectors or receivers reference it, and deleting a non-empty site explicitly includes its sectors. | Hard-delete recovery/undo/export, artifact ownership/cleanup, scenario/import workflows, dependencies from future study/GIS/antenna records, bulk edits, and duplication lineage/provenance remain. |
| Domain model | Foundation | Schema-4 active/archive invariants, `ArchivedProject` lifecycle metadata, validated engineering value types, richer RF records, typed coordinates, explicit references, and bounded structural records for antenna patterns, GIS layers, scenarios, coverage snapshots, regulatory studies, artifacts, and import provenance are persisted and validated. | Existing legacy entity primitives still need staged migration. The non-RF records are structural foundations, not functional antenna, GIS, scenario, coverage, regulatory, import, or report workflows; full study requests/results remain. |
| Engineering canvas | Foundation | An offline Compose Web Mercator coordinate grid plots sites and active azimuths with project fitting, pan/pinch camera controls, a metric scale, touch and accessible-list selection, explicit local-grid attribution, and durable coordinate-only site editing. | A cartographic GIS renderer, authorized basemap source/package lifecycle, DEM/terrain, IBGE geometry/map integration, GIS features, receiver editing, drag placement, and map performance evidence remain. |
| IBGE Data Catalog | Delivered bounded slice | A 21.1 MiB content-addressed asset installs to a verified 67.6 MiB read-only SQLite database after disk preflight, staged extraction, dual hashes, schema/content validation, and atomic promotion. The compact UI exposes offline normalized municipality search, attributes, `NoData`, CRS, attribution, source hashes, and limitations for 468,099 sectors and 5,570 municipalities. | No user import/download/removal, sector polygons, exact containment, map rendering, population-by-coverage, API 23 runtime proof, or approved public redistribution exists. |
| RF calculation | Delivered | FSPL, EIRP, received power, fade margin, midpoint Fresnel radius, thermal noise, and SNR. | Geodesy, terrain, curvature, LOS, patterns, diffraction, persistence, manifest. |
| JVM tests | Delivered baseline | The current 233 tests pass and cover domain values/references, RF formulas, geographic viewport math, schema migration, indexed documents, artifact integrity, latest-catalog mutation rebasing/concurrency, project lifecycle conflict/no-op policies, independent RF CRUD/reference impact including location-only site moves, dataset model/search normalization, Data Catalog state, form parsing, ViewModel transitions, per-ViewModel mutation-session identity, and English-only Kotlin/XML/JSON/TXT hygiene. | Property/numerical golden, accessibility, performance, export, transformer fault automation, and complete system-flow coverage remain. |
| Instrumented tests | Delivered baseline | The current 62 tests pass with no failures or skips on the Android 16/API 36 `Medium_Phone_API_36.1` emulator; the final connected run used font scale 1.30. Existing coverage includes typed routes/drafts, mutation recovery, project lifecycle/durable state, Activity recreation, Add RF Path, compact RF assets, Engineering Map, inaccessible targets, validation, draft protection, and save retry. The added real-asset repository tests cover IBGE extraction, hashes/schema/content, offline query/wildcard handling, corruption recovery, low-storage failure, update cleanup, and reopen; four Catalog tests cover compact Ready/search/selection/limitations and progress/failure/query states. The preceding 18-test revision passed on the physical Android 16 reference phone. | A fresh physical run of the current suite, API 23 dataset execution, true system-reclaim process termination, rotation/device matrix, broader accessibility automation, and CI execution remain. |
| Product language | Delivered baseline | Production UI/errors/demo/tests and documentation are English; a unit test scans Kotlin, XML, JSON, and text production resources for common Portuguese terms while allowing pinned official identifiers. | The blacklist is partial and must expand with each new user-visible resource type. |
| Public release | Blocked | Debug baseline only; backup disabled. | Product license, IBGE redistribution review/NOTICE retention, signing, SBOM, privacy, shrinker, upgrade testing, and release channel. |

## 3. Priority matrix

### P0 — Offline mobile MVP

Already delivered or founded:

- Android identity and API 23–36.1 compatibility;
- reproducible debug build and CI workflow;
- Compose/Material 3 shell and custom theme;
- typed, saveable Navigation 3 routes including nested RF-path, project-name, and project-scoped RF-asset editors;
- explicit action/effect ViewModel flow, use cases, injected dispatchers, and structured recovery;
- schema-4 indexed project persistence with explicit legacy 1→2→3→4 migration, immutable SHA-256 project documents, and defensive commit/integrity tests;
- a content-addressed artifact-store foundation with bounded staging, hash verification, deduplication, availability checks, and copying, but no import/attachment UI or garbage collection;
- project create/select/rename/duplicate/archive/restore/hard-delete, synthetic demo, combined Add RF Path, and independent network/site/sector/receiver create/edit/delete slices;
- typed engineering values, receiver/CPE, and network references;
- free-space RF calculator and numerical unit tests.

Still required to close P0:

- continued English-only enforcement and complete accessibility review;
- true process-death/system-flow and broad device restoration evidence;
- remaining project lifecycle work including hard-delete recovery/undo/export, artifact ownership/cleanup, and lineage/provenance policy;
- durable jobs, recovery/export UX, garbage collection, and the long-term operational-store decision;
- real offline geographic map;
- local DEM, terrain profile, geodesy, curvature, LOS, and Fresnel clearance;
- project-linked persisted study request/result;
- reproducible export manifest;
- connected smoke test in release validation.

### P1 — Capable mobile product

- **Delivered bounded slice:** release-managed national IBGE sector attributes and municipality summaries with offline verification/search; geometry and population-by-coverage are not delivered;
- networks, scenarios, and immutable study snapshots;
- HRP/VRP library and import;
- validated Hata/3GPP and selected diffraction methods;
- resource-bounded local coverage, best server, overlap, and C/(I+N);
- controlled regional dataset acquisition;
- lightweight import/export, JSON/HTML report, GeoTIFF, and KMZ;
- field measurements and basic comparison;
- offline help and sanitized diagnostics.

### P2 — Selective advanced capabilities

- ITM, P.1812, P.1546, P.528, and FCC curves;
- clutter, buildings, census-sector geometry, and population-by-coverage;
- LTE/5G indicators, FWA, simulcast, and air-to-ground;
- calibration, PDF reports, and 3D antenna patterns;
- large-area coverage through native code or an optional service;
- mobile regulatory screening after legal and numerical approval.

### P3 — Later exploration

- antenna-array synthesis on-device;
- complex GIS editing;
- real-time collaboration;
- urban ray tracing;
- `.rp3` binary round trip.

## 4. Mandatory gates

| Gate | Required output | Current state | Blocks |
|---|---|---|---|
| **G0 — Product and identity** | Product name, package, repository, license, privacy policy, English-only policy, and supported API range approved. | **Foundation:** package/API/repository and English-only baseline exist; license/privacy/release approval remains blocked. | Public release and irreversible contracts. |
| **G1 — Reproducible build** | Clean checkout runs unit tests, lint, debug APK, and test APK in CI with documented JDK/SDK. | **Delivered for debug baseline:** workflow and local artifacts exist. | Functional milestones if regression occurs. |
| **G2 — Application architecture** | UDF, ViewModel, Navigation 3, dependency assembly, error model, restoration, and observability demonstrated. | **Foundation:** explicit actions/effects, structured recovery, use cases, injected dispatchers, and typed saved-state route tests exist. Feature splitting, DI/scoping policy, durable jobs/observability, accessibility, and true process-death/device flows remain. | Scaling feature count safely. |
| **G3 — Durable persistence** | Exported schema, migrations, safe writes, backup policy, recovery, and data ownership validated. | **Foundation:** project schema 4, explicit legacy 1→2→3→4 migrations, a small atomic index, immutable verified project documents, serialized latest-catalog transactions, and a content-addressed artifact-store foundation exist. Unreadable/future-index recovery/export, garbage collection, attachment/ownership policy, backup, jobs, multi-process policy, Android interruption evidence, and the long-term store decision remain. | Real user projects and portable assets. |
| **G4 — Map and data** | Renderer, offline format, attribution, license, NoData, disk budget, and lifecycle approved. | **Foundation:** the tested coordinate viewport remains non-cartographic. One bounded national IBGE attribute package now has a strict inventory, known disk budget, source/CRS/attribution/license caveat, staged extraction, dual hashes, SQLite validation, update cleanup, and offline queries. G4 remains open for an authorized basemap and general package lifecycle, DEM, renderer decision, map/IBGE geometry integration, performance evidence, and IBGE redistribution approval. | Terrain, field-map, polygon, and population-by-coverage claims. |
| **G5 — Numerical core** | Units, geodesy, FSPL, LOS, and Fresnel pass independent golden cases with tolerances. | **Foundation:** FSPL/noise/Fresnel-radius baseline is tested; geodesy/terrain/LOS are missing. | Terrain-aware engineering label. |
| **G6 — Mobile compute** | Memory/time/battery budget, cancellation, blocking strategy, and device benchmarks. | Planned. | Raster coverage and heavy engines. |
| **G7 — Interoperability** | Android/desktop contract, capability negotiation, fixtures, and read/write matrix. | Blocked pending contract. | `.atxp` support. |
| **G8 — Advanced engines** | License, edition, runtime, validity domain, and reference vectors for each engine. | Blocked per engine. | Stable exposure of each advanced model. |
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
- schema-4 indexed JSON repository and demonstration project;
- free-space RF calculator;
- CI workflow, 233 passing JVM tests, lint with 0 errors and 12 dependency/tooling warnings, and debug APK/test APK;
- 62 passing Android 16/API 36 emulator instrumented navigation, saved-state, mutation-completion, accessibility, draft-protection, persisted project lifecycle, deterministic selection, persisted Add RF Path, compact RF-assets, Engineering Map, real IBGE package, and compact Data Catalog tests; the final connected run used font scale 1.30, with only the preceding 18-test revision also proven on the physical reference phone;
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
- durable job model, sanitized diagnostics, correlation, and broader observability;
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

**State:** Foundation implemented; schema-4 indexed persistence, the project lifecycle slices, and independent network/site/sector/receiver CRUD are delivered as bounded Phase 1 capabilities.

**Objective:** evolve the current catalog/demo into editable, durable engineering projects.

**Delivered foundation:**

- schema-4 `ProjectCatalog`, `ArchivedProject`, and `PlannerProject`, with explicit legacy 1→2→3→4 migration;
- a small strict-UTF-8 atomic index backed by immutable SHA-256-addressed project documents; the index remains limited to 5 MiB and each project document to a conservative 8 MiB;
- commit ordering that makes project documents durable and verifies length/hash before publishing the index, preserving the previous reachable catalog when a document or index write fails;
- tests for legacy migration, successful and failed index publication, missing/corrupt/future documents or indexes, no-op document reuse, single-project document replacement, size limits, failed writes, and concurrent repository instances;
- a private content-addressed artifact-store foundation with bounded staging, optional expected SHA-256, immutable deduplication, availability/corruption checks, and bounded copying;
- validated engineering value objects for coordinates, frequency, bandwidth, power, gain, loss, distance, height, azimuth, and tilt with primitive JSON representation;
- receiver/CPE model plus backward-compatible receiver collection and nullable sector network reference;
- aggregate duplicate/reference validation for RF records plus bounded structural schema records for antenna patterns, GIS layers, study scenarios, coverage snapshots, regulatory studies, artifact references, and import provenance;
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

- functional scenario, dataset, antenna, GIS, coverage, regulatory, import, and study request/result workflows beyond their schema-4 structural records;
- artifact attachment/import UI, project ownership rules, project-deletion cleanup, export packaging, and garbage collection;
- staged migration of remaining legacy primitive entity fields to canonical unit types;
- hard-delete recovery/undo/export and source-project lineage and duplication-provenance policy;
- editing/removal of individual per-network receiver compatibility profiles, dependency impact from future study, scenario, antenna, GIS, and artifact records, richer conflict diagnostics, bulk edits, and undo;
- long-term decision for indexed JSON files versus Room/SQLite, including multi-process behavior and portable ownership;
- recovery/export workflow for preserved unreadable/future indexes and project documents;
- durable jobs, multi-process policy, Android `AtomicFile` interruption, and storage-exhaustion system evidence.

**Delivered vertical slices:** rename, duplicate, archive/restore, and hard-delete exact project snapshots through the latest durable transaction; migrate a legacy monolithic catalog into a schema-4 atomic index plus immutable project documents; update one project without rewriting peer documents; open Manage RF Assets and independently create/edit/delete networks, sites, sectors, and receivers; block a referenced-network deletion and disclose contained-sector deletion impact; and continue to create one linked network/site/sector/receiver through Add RF Path. Reopen/round-trip preserves IDs, precision, units, and links. The artifact store is currently an internal byte-storage foundation only: there is no attachment/import UI, ownership cleanup, garbage collection, or delivered engineering workflow using those bytes. The duplicated project does not yet carry source-project lineage or a duplication-provenance marker. Local archive cannot recover permanent deletion and is not backup, export, synchronization, or artifact recovery.

**Bounded manual archive evidence:** on the Android 16/API 36 emulator, archive UI/actions and the archived-project card were reachable in portrait at font scales 1.0, 1.30, and 2.0 and in landscape at 1.30. A force-stop/relaunch retained the archived record; after restore, a second force-stop/relaunch retained that project as active and selected. This does not prove Android Backup, system-reclaim restoration, all process-death timings, or a device support matrix.

**Remaining phase demonstrator:** cover project creation and independent RF CRUD through true process termination/relaunch, then define and demonstrate artifact attachment, ownership, export, and safe cleanup before enabling any import workflow.

**Exit gate:** G3.

**Definition of Done:**

- round trip preserves IDs, precision, units, and references;
- migrations never rely on destructive fallback;
- interrupted writes preserve the last valid catalog/project;
- linked deletions show impact before confirmation;
- domain remains independent of Android, Compose, and persistence models;
- demonstration data stays explicitly synthetic.

### F3 — Geographic map and offline data

**State:** Geographic coordinate viewport, durable site move, and bounded offline IBGE attribute catalog delivered; offline basemap, DEM, general packages, and sector geometry remain planned.

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
- real-asset Android integration coverage for extraction, hashes, schema, counts, corruption recovery, low-storage rejection, update cleanup, reopen, and offline queries.

**Remaining scope:**

- cartographic renderer/package adapter decision and performance spike;
- drag placement and receiver editing on the map;
- authorized offline basemap format;
- general/user package inventory beyond the pinned IBGE package;
- Storage Access Framework import;
- optional regional download with preflight, resume, validation, and atomic promotion;
- initial GeoTIFF/HGT elevation adapter;
- dataset-backed map availability and pixel-level `NoData` visualization;
- census-sector polygon packaging, exact containment, map rendering, and population-by-coverage;
- public redistribution approval for the embedded IBGE derivative.

**Delivered sub-slices:** (1) open offline coordinate viewport → fit/pan/zoom → select a site → inspect stored elevation availability → edit coordinates → persist after stale-safe durable commit; and (2) open Data Catalog → install/verify the bundled IBGE database → search locally → inspect a municipality summary and explicit limitations.

These sub-slices do not complete the phase vertical slice because the map does not render an authorized basemap, sample a DEM, consume sector geometry, or calculate population by coverage.

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

**State:** Free-space calculation foundation delivered; terrain-aware workflow planned.

**Objective:** close the offline mobile engineering MVP.

**Delivered foundation:**

- manual link parameter form;
- pure Kotlin input validation;
- FSPL/P.525;
- EIRP, received power, sensitivity margin;
- thermal noise floor and SNR;
- midpoint first Fresnel radius;
- explicit disclosure that terrain, curvature, clutter, patterns, and variability are absent;
- numerical tests for formulas, signs, and invalid inputs.

**Remaining scope:**

- canonical HRP/VRP and directional-gain lookup;
- sector/receiver endpoint selection;
- geodesic path and elevation sampling;
- Earth-curvature policy, LOS, and Fresnel clearance along the path;
- asynchronous, cancelable execution;
- terrain profile chart;
- persisted study request, execution, result, and immutable artifact references;
- CSV/JSON export with versioned manifest and fingerprints;
- independent reference fixtures for all new terms.

**Vertical slice:** select sector/receiver → build local terrain profile → run FSPL/LOS/Fresnel → inspect terms → persist → export → reopen identical result.

**Exit gate:** G5 plus the initial G6 resource budget.

**Definition of Done:**

- independent golden cases cover conversion, geodesy, FSPL, LOS, and Fresnel clearance;
- azimuth/elevation/tilt conventions are explicit and tested at boundaries;
- result records engine, version, effective inputs, units, datasets, warnings, and hashes;
- cancellation leaves the project consistent;
- exported package can be verified without internal app state;
- no RadioPlanner parity statement appears without a dedicated bench.

**Milestone:** F4—not the current free-space screen—closes the offline mobile MVP.

### F5 — Scenarios and bounded local coverage

**State:** Planned. Coverage enums/demo summaries are only domain foundation.

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

**State:** Planned; `.atxp` work is blocked by G7.

**Objective:** move supported work among Android, desktop, and open tools without silent loss.

**Scope:**

- project capability negotiation;
- approved `.atxp` subset or a documented intermediate format;
- read-only mode for unsupported project capabilities;
- CSV and GeoJSON import/export;
- field measurements with quality, timestamp, and source;
- KMZ/PNG where prioritized;
- JSON/HTML report and sanitized diagnostics;
- searchable offline help.

**Exit gate:** G7.

**Definition of Done:**

- unsupported content is never silently discarded;
- every conversion reports read, unsupported, and transformed items;
- hostile-file and structural limits are tested;
- Android → desktop → Android fixtures preserve the declared subset;
- exports include schema, units, and version;
- `.rp3` remains blocked until its separate legal/security gate passes.

### F7 — Selective advanced portfolio

**State:** Planned or Blocked per engine, dataset, and product use case.

**Candidate capabilities, not a commitment to deliver together:**

- ITM, P.1812, P.1546, P.528, and FCC curves;
- clutter, buildings, census-sector geometry, and population-by-coverage;
- RSRP/RSRQ/EPRE/throughput, FWA, simulcast, and air-to-ground;
- measurement calibration;
- 3D antenna patterns and PDF reports;
- large-area coverage with native code or an optional service;
- mobile regulatory screening.

**Entry gate per capability:** approved mobile use case, G8, device budget, license, and available data.

**Definition of Done per capability:**

- implementation and model edition are identifiable;
- official or independent vectors pass declared tolerance;
- local and remote backends share a contract and expose differences;
- remote use is explicit and lists data sent and retention policy;
- validity limits and warnings appear before and after execution;
- regulatory output remains screening until formal validation applies.

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
| MOB-006 | Implement versioned indexed project persistence | Delivered baseline | P0 | MOB-005 | Project schema 4 uses a small `AtomicFile` index and immutable SHA-256 project documents; legacy 1→2→3→4 migration, size/hash checks, latest-catalog transactions, and failed-publication tests are delivered. |
| MOB-007 | Implement project create/select and demo | Delivered | P0 | MOB-006 | User project creation and synthetic seeded demo. |
| MOB-008 | Implement pure Kotlin RF baseline | Delivered | P0 | domain baseline | FSPL/EIRP/received/noise/SNR/Fresnel-radius tests. |
| MOB-009 | Enforce English-only product language | Delivered baseline | P0 | all layers | English production sources/tests plus automated regression scan. |
| MOB-010 | Harden restorable typed navigation | Foundation | P0 | MOB-004 | Stable-ID save/restore and malformed/nested route tests delivered; deep links, deleted IDs, process termination, and tablet matrix remain. |
| MOB-011 | Define durable schema evolution | Foundation | P0 | MOB-006 | Schema 4, non-destructive legacy migration, the atomic index, and immutable project documents are delivered; garbage collection, portable ownership, backup, unreadable/future-index recovery/export, and multi-process ADR work remain. |
| MOB-012 | Complete RF value types and entity CRUD | Foundation | P0 | MOB-007/011 | Typed values, project lifecycle, Add RF Path, and independent network/site/sector/receiver create/edit/delete with stale and RF-reference checks are delivered. Bulk operations, undo, future-record dependency impact, hard-delete recovery/export, artifact policy, and lineage/provenance remain. |
| MOB-013 | Run geographic map spike | Foundation | P0 | license gate | Coordinate renderer, camera/gesture math, attribution boundary, selection, and durable site move are delivered and tested. Cartographic renderer/package lifecycle, authorized source, airplane-mode package proof, and performance report remain. |
| MOB-014 | Define offline catalog/package format | Planned | P0 | MOB-013 | Safe fixture install, validation, ownership, and removal. |
| MOB-015 | Implement DEM adapter and terrain profile | Planned | P0 | MOB-014 | Golden profile with NoData and provenance. |
| MOB-016 | Add geodesy/LOS/Fresnel clearance | Planned | P0 | MOB-012/015 | Independent numerical fixtures. |
| MOB-017 | Persist and export link study manifest | Planned | P0 | MOB-011/016 | Restart round trip and verifiable export. |
| MOB-018 | Close mobile MVP vertical slice | Planned | P0 | MOB-010–017 | Automated offline create→calculate→save→export→reopen flow. |
| MOB-019 | Define `.atxp` mobile/desktop contract | Blocked | P0 | desktop/mobile schemas | Capability matrix and fixtures. |
| MOB-020 | Benchmark small coverage | Planned | P1 | MVP/G5 | Per-device resource budget and engine decision. |
| MOB-021 | Compare Kotlin and native compute | Planned | P2 | MOB-020 | Numerical/performance report and ADR. |
| MOB-022 | Specify optional compute service | Planned | P2 | proven demand | Contract, privacy, authentication, retention, local fallback. |
| MOB-023 | Add connected test to release lane | Planned | P0 | device infrastructure | Android 16+ smoke result stored with release evidence. |
| MOB-024 | Validate compact phone information density | Delivered baseline | P0 | MOB-003 | One physical Android 16 phone has portrait evidence at approximately 394 dp, density 520, and font scales 1.15/1.30 plus a baseline landscape check. Separate API 36 emulator evidence covers Duplicate Project/Delete Project at font scales 1.0/1.30 in portrait and short landscape with Gboard open/closed, plus Archive Project/actions and the archived-project card in portrait at 1.0/1.30/2.0 and landscape at 1.30; the full device/accessibility matrix remains F8 work. |
| MOB-025 | Establish content-addressed artifact storage | Foundation | P0 | MOB-011 | Bounded staging, expected-hash validation, SHA-256 deduplication, availability/corruption checks, and copying are delivered. Attachment/import UI, ownership, export packaging, deletion cleanup, and garbage collection remain. |

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
| GIS/data | Provider contracts, CRS, NoData, hostile files, lifecycle, screenshots; packaged IBGE extraction/hash/schema/query/storage-preflight/corruption-recovery tests and compact Catalog state/reachability tests. |
| Link study | Independent golden cases by term, cancellation, persistence, manifest. |
| Coverage | Golden grids, edges/NoData, memory, time, cancellation, export. |
| Interoperability | Cross-schema fixtures, capability negotiation, parser fuzz/limits. |
| Release | Install/upgrade, offline, accessibility, performance, security, signing. |

## 10. Risks and mitigation

| Risk | Impact | Mitigation/gate |
|---|---|---|
| Later project schemas evolve without explicit migrations | Project loss or lockout | Fixture-backed legacy 1→2→3→4 migrations and the versioned index/document envelopes are delivered; G3 still requires a published evolution, ownership, recovery, export, and backup policy plus fixtures for every future public schema. |
| Content-addressed files are mistaken for a complete artifact lifecycle | Orphaned storage, unsafe cleanup, or false import/export claims | Keep the store at Foundation: do not expose import or deletion until attachment ownership, reference discovery, export, recovery, and garbage-collection rules are implemented and tested. |
| Local archive is mistaken for backup or hard-delete recovery | Irrecoverable project loss | Keep archive/restore distinct from permanent deletion, which still requires exact `DELETE`; archive/restore preserve exact local aggregates transactionally but cannot recover permanent deletion and do not provide backup, export, synchronization, or external-asset recovery. |
| Catalog mutation policy diverges across processes or future stores | Newer state can be overwritten | Every in-process mutation now rebases on the latest catalog inside a serialized repository transaction and publish follows persistence; define multi-process/conflict policy before another writer or store is introduced. |
| Saved-instance-state route tests are treated as complete process recovery | Selected durable state or nested context can still be lost after system termination | G2 still requires true process-death, deleted-ID, rotation, and device-matrix flows. |
| Coordinate viewport or IBGE envelopes are mistaken for a basemap, boundary, or GIS result | Incorrect geographic expectations | Keep permanent no-basemap/no-geometry disclosures; G4 remains required before field-map, terrain, exact containment, or population-by-coverage claims. |
| Free-space result is mistaken for terrain-aware engineering | Invalid field decision | Explicit limits in UI/docs; G5 before terrain-aware label. |
| Study result is not persisted | Lost evidence on restart | F4 immutable request/result/artifact model. |
| Package/schema published too early | Expensive compatibility burden | G0/G3 before public release. |
| Dataset size causes storage/ANR failure | Abandonment or corruption | The bundled IBGE slice has a 67.6 MiB installed size plus 16 MiB preflight allowance, bounded streaming extraction, hashes, and exact-pattern cleanup. General/regional packages still require catalog budgets, resume, ownership, and reference-aware cleanup. |
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
