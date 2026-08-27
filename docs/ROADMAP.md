# Android Roadmap

> Evidence baseline: August 27, 2026. The repository now contains an adaptive Compose shell, typed and saveable Navigation 3 routes, explicit UDF/application boundaries, a transactional schema-2 JSON repository with v1 migration, transactional project rename, duplication, and bounded hard deletion, a persisted combined RF-path editor, a bounded RF calculator, CI, JVM tests, and Android instrumentation. This roadmap does not treat those foundations as complete desktop or RadioPlanner parity.

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
| Compact phone information density | Delivered baseline | Scalable compact typography and shared components, 12–16 dp feature gutters, responsive metrics/fields/cards/map height, a short-height rail, IME resizing for the project-name editor, and explicit 48 dp minimums on changed controls have manual evidence on one physical Android 16 phone: 1280 × 2772 pixels, density 520, portrait at font scales 1.15/1.30 and landscape at 1.15. The compact adaptive Duplicate Project and Delete Project dialogs were separately validated on an Android 16/API 36 emulator at 1080 × 2400 pixels and 420 dpi, in portrait and short landscape at font scales 1.0/1.30 with Gboard open and closed. At 1.30, portrait stacks the project actions; short landscape required one scroll to the delete field and exposed dialog actions after the IME was hidden. | These bounded physical-device and emulator observations are not a complete OEM/API/aspect-ratio/theme/font-scale/accessibility matrix; the app does not clamp or override system font scale. |
| Navigation 3 | Foundation | Serializable stable-ID route keys, a saveable typed `NavBackStack`, bounded unknown-route fallback, and the nested RF-path and project-name editors have saved-instance-state restoration tests. | Deep links, deleted-ID recovery UX, adaptive list/detail, and true system process-death/rotation testing across the device matrix remain. |
| UDF/ViewModel | Foundation | Explicit `AppUiAction`/`AppUiEffect`, structured problem/recovery values, injected use cases/dispatchers, serialized catalog mutations, cancellation-aware calculation, and ViewModel transition tests exist. | Feature-level ViewModels, cross-instance catalog observation, the DI/scoping decision, durable jobs, broader observability, accessibility, and system recovery evidence remain. |
| Project persistence | Delivered baseline | Schema-2 strict UTF-8 JSON uses `AtomicFile`, `fd.sync`, a 5 MiB limit, explicit v1 migration, and a mutex-protected read-transform-write transaction. Tests cover migration, corruption, future schema, malformed UTF-8, failed writes, and concurrency. | Recovery/export UX, multi-process policy, storage-exhaustion evidence against Android storage, asset ownership, backup, and the long-term JSON-versus-Room decision remain. |
| Project workflow | Foundation | Load, create, select, rename, duplicate, hard-delete, and display projects; seed the synthetic demo; and add one linked network/site/sector/receiver through the persisted Add RF Path flow. Duplication copies the latest durable source and selects a fresh-root copy. Deletion compares the complete reviewed aggregate with the latest durable version, rejects stale changes, atomically removes the current catalog aggregate, and leaves a deterministic valid next/previous selection or an empty catalog. | Archive, recovery/undo/export, independent create/edit/delete for every RF entity, scenarios, imports, impact-aware linked deletion, project-owned asset policy, and duplication lineage/provenance remain. |
| Domain model | Foundation | Validated engineering value types, receiver/CPE, typed coordinates, and explicit receiver/sector network references extend the catalog/network/site/sector/study foundation. | Existing legacy entity primitives still need staged migration; scenarios, datasets, study requests/results, and artifacts are not modeled. |
| Engineering canvas | Foundation | Offline Canvas plots sites and active azimuths. | Real GIS/map renderer, projection, camera, offline source, editing, attribution. |
| RF calculation | Delivered | FSPL, EIRP, received power, fade margin, midpoint Fresnel radius, thermal noise, and SNR. | Geodesy, terrain, curvature, LOS, patterns, diffraction, persistence, manifest. |
| JVM tests | Delivered baseline | The current 125 tests pass and cover domain values/references, RF formulas, schema migration and storage faults, transactional concurrency, duplication, deletion selection/conflict/no-op policies, form parsing, ViewModel transitions, and English-only source hygiene. | Property/numerical golden, accessibility, performance, export, and complete system-flow coverage remain. |
| Instrumented tests | Delivered baseline | The current 33 tests pass on an Android 16/API 36 emulator, covering top-level/nested typed routes, RF-path and project-name draft protection, explicit mutation-completion and transient pending-save recovery, legacy/normalized/competing rename behavior, accessibility behavior, persisted rename, project duplication, exact hard-delete confirmation, changed-snapshot and deletion draft/rejection/durable-absence restoration, deterministic fallback selection, and Activity recreation after deletion and RF-path persistence. The preceding 18-test revision passed on the physical Android 16 reference phone. | A fresh physical run of the current suite, true process termination, rotation/device matrix, broader accessibility automation, and CI execution remain. |
| Product language | Delivered baseline | Production UI/errors/demo/tests and documentation are English; a unit test scans production sources for common Portuguese terms. | The blacklist is partial and must expand with new resource types. |
| Public release | Blocked | Debug baseline only; backup disabled. | License, signing, SBOM, privacy, shrinker, upgrade testing, release channel. |

## 3. Priority matrix

### P0 — Offline mobile MVP

Already delivered or founded:

- Android identity and API 23–36.1 compatibility;
- reproducible debug build and CI workflow;
- Compose/Material 3 shell and custom theme;
- typed, saveable Navigation 3 routes including nested RF-path and project-name editors;
- explicit action/effect ViewModel flow, use cases, injected dispatchers, and structured recovery;
- schema-2 transactional JSON catalog with explicit v1 migration and defensive tests;
- project create/select/rename/duplicate/hard-delete, synthetic demo, and a combined persisted RF-path creation slice;
- typed engineering values, receiver/CPE, and network references;
- free-space RF calculator and numerical unit tests.

Still required to close P0:

- continued English-only enforcement and complete accessibility review;
- true process-death/system-flow and broad device restoration evidence;
- remaining project lifecycle work (archive, recovery/undo/export, and lineage/provenance policy) and independent RF-entity create/edit/delete;
- durable jobs, recovery/export UX, and the long-term operational-store decision;
- real offline geographic map;
- local DEM, terrain profile, geodesy, curvature, LOS, and Fresnel clearance;
- project-linked persisted study request/result;
- reproducible export manifest;
- connected smoke test in release validation.

### P1 — Capable mobile product

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
- clutter, buildings, and population;
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
| **G3 — Durable persistence** | Exported schema, migrations, safe writes, backup policy, recovery, and data ownership validated. | **Foundation:** schema 2, explicit v1 migration, strict decoding, serialized transactions, and corruption/future-schema/concurrency tests exist. Recovery/export, backup/data ownership, assets/jobs, multi-process policy, and the long-term store decision remain. | Real user projects and portable assets. |
| **G4 — Map and data** | Renderer, offline format, attribution, license, NoData, disk budget, and lifecycle approved. | **Foundation:** technical Canvas only. | Terrain and field-map claims. |
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
- compact responsive feature information density with bounded physical Android 16 portrait checks at approximately 394 dp and font scales 1.15/1.30 plus a baseline landscape check, and separate API 36 emulator validation of the compact Duplicate Project and Delete Project dialogs at font scales 1.0/1.30 in portrait and short landscape with Gboard open and closed;
- Navigation 3 dependency and top-level display;
- atomic JSON repository and demonstration project;
- free-space RF calculator;
- CI workflow, 125 passing JVM tests, lint with 0 errors and 12 dependency/tooling warnings, and debug APK/test APK;
- 33 Android 16/API 36 emulator instrumented navigation, saved-state, mutation-completion, accessibility, draft-protection, persisted rename, project-duplication, project-deletion, and persisted Add RF Path flow tests, with only the preceding 18-test revision also proven on the physical reference phone;
- English production strings plus `EnglishOnlySourceTest` regression guard;
- application backup disabled while the data policy is incomplete.

**Remaining:**

- approve license, privacy, signing, and formal distribution model;
- extend English-only checks when new resource/file types are introduced;
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
- cancellation-aware calculation and serialized durable catalog mutations;
- ViewModel tests for loading, create/select/rename/duplicate/delete, mutation-completion accounting, save failure, retry, stale aggregate rejection, concurrent deletion, invalid mutations, cancellation, and stale calculation results;
- serializable stable-ID `AtxRoute` keys and a saveable typed Navigation 3 back stack;
- safe unknown/malformed route fallback and nested `RfPathEditorRoute(projectId)`;
- saved-instance-state instrumentation for top-level, unknown, and nested editor routes;
- ViewModel factory and repository interface;
- storage recovery banner, retry action, and one-time notice effect;
- Navigation 3 `NavDisplay` with five top-level routes plus nested RF-path and project-name editors;
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

**Delivered component evidence:** serialized saved-instance-state restoration preserves stable top-level, nested RF-path, and nested project-name route IDs and safely handles unknown/malformed routes.

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

**State:** Foundation implemented; the first project-update, project-duplication, project-deletion, and combined persisted RF-entity slices are delivered.

**Objective:** evolve the current catalog/demo into editable, durable engineering projects.

**Delivered foundation:**

- schema-2 `ProjectCatalog` and `PlannerProject` with an explicit schema-1 fixture migration;
- strict UTF-8 parsing, 5 MiB limit, future-schema/corruption preservation, atomic replacement, and serialized read-transform-write mutation;
- tests for successful and failed migration promotion, malformed UTF-8, malformed/invalid/future documents, size limits, failed writes, and concurrent repository instances;
- validated engineering value objects for coordinates, frequency, bandwidth, power, gain, loss, distance, height, azimuth, and tilt with primitive JSON representation;
- receiver/CPE model plus backward-compatible receiver collection and nullable sector network reference;
- aggregate duplicate/reference validation for receivers and linked sectors;
- create/select/rename/delete project workflow, including a saveable project-name editor and transactional rename use case;
- compact adaptive project-duplication dialog and transactional use case that read the latest durable source, assign a fresh route-safe root project ID and fresh root timestamps, preserve the project-scoped nested graph/IDs/references/data/order, demonstration flag, and study timestamps, leave the source unchanged, append the copy, and select it;
- compact adaptive project-deletion dialog with exact `DELETE` confirmation and impact counts, plus a transactional use case that compares the complete reviewed aggregate with the latest durable aggregate, rejects stale or already-removed targets without a write, atomically removes the current project aggregate, preserves other projects and order, and selects the next project, previous project, or none deterministically;
- combined Add RF Path editor/use case that atomically adds one network, one site/sector, and one receiver with injected IDs and clock;
- JSON round trips preserve IDs, `Double` precision, explicit units, and network references;
- synthetic FM demo with one network, three sites, and two study summaries.

**Remaining scope:**

- scenario, dataset reference, study request/result, and artifact models;
- independent create/edit/delete flows for sites, sectors, receivers, networks, and metadata;
- staged migration of remaining legacy primitive entity fields to canonical unit types;
- project archive, recovery/undo/export, and source-project lineage and duplication-provenance policy;
- impact-aware linked deletion and richer conflict diagnostics;
- decision and transition plan for JSON catalog versus Room/SQLite and asset files;
- recovery/export workflow for preserved unreadable/future catalogs;
- durable jobs, multi-process policy, Android `AtomicFile` interruption, and storage-exhaustion system evidence.

**Delivered vertical slices:** rename an existing project through one repository transaction while preserving its identity and RF graph and rejecting a stale competing name; duplicate the latest durable source aggregate through one repository transaction with a fresh root ID/timestamps, unchanged source, preserved project-scoped nested IDs/references/data, and durable selection of the appended copy; compare a reviewed project aggregate with the latest durable version and atomically hard-delete only an unchanged target while preserving every other project and selecting a deterministic neighbor; open an existing project → enter a network, transmitter site/sector, and receiver → commit one repository transaction → reopen/round-trip with IDs, precision, units, and links preserved. The duplicated project does not yet carry source-project lineage or a duplication-provenance marker. Hard deletion has no in-app backup, undo, archive, recovery, export, or external-asset cleanup.

**Remaining phase demonstrator:** cover project creation and the same persisted entity flow through true process termination/relaunch, then exercise independent edits and linked deletion impact.

**Exit gate:** G3.

**Definition of Done:**

- round trip preserves IDs, precision, units, and references;
- migrations never rely on destructive fallback;
- interrupted writes preserve the last valid catalog/project;
- linked deletions show impact before confirmation;
- domain remains independent of Android, Compose, and persistence models;
- demonstration data stays explicitly synthetic.

### F3 — Geographic map and offline data

**State:** Technical Canvas foundation delivered; GIS is planned.

**Objective:** make the selected project geographically useful without a network in a prepared region.

**Delivered foundation:**

- Canvas-based local site and azimuth visualization;
- coordinate validation in the domain;
- semantic description and site coordinate list;
- clear in-app disclosure that no tiles or basemap exist.

**Remaining scope:**

- renderer spike and adapter decision;
- geographic camera, gestures, scale, attribution, and selection;
- site/receiver editing on the map;
- authorized offline basemap format;
- dataset inventory with edition, extent, CRS, license, hash, and size;
- Storage Access Framework import;
- optional regional download with preflight, resume, validation, and atomic promotion;
- initial GeoTIFF/HGT elevation adapter;
- explicit availability and NoData visualization.

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
- clutter, buildings, and population;
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
| MOB-006 | Implement atomic schema-2 JSON catalog | Delivered baseline | P0 | MOB-005 | `AtomicFile`, strict UTF-8, size/schema checks, explicit v1 migration, serialized transactions, and defensive tests. |
| MOB-007 | Implement project create/select and demo | Delivered | P0 | MOB-006 | User project creation and synthetic seeded demo. |
| MOB-008 | Implement pure Kotlin RF baseline | Delivered | P0 | domain baseline | FSPL/EIRP/received/noise/SNR/Fresnel-radius tests. |
| MOB-009 | Enforce English-only product language | Delivered baseline | P0 | all layers | English production sources/tests plus automated regression scan. |
| MOB-010 | Harden restorable typed navigation | Foundation | P0 | MOB-004 | Stable-ID save/restore and malformed/nested route tests delivered; deep links, deleted IDs, process termination, and tablet matrix remain. |
| MOB-011 | Define durable schema evolution | Foundation | P0 | MOB-006 | Schema 2 and non-destructive v1 migration fixture delivered; long-term store/asset/backup/recovery ADR remains. |
| MOB-012 | Complete RF value types and entity CRUD | Foundation | P0 | MOB-007/011 | Typed values, receiver/references, project rename/duplication/hard-delete, and combined Add RF Path round trip delivered; independent RF-entity edit/delete, project archive/recovery, external-asset policy, and lineage/provenance remain. |
| MOB-013 | Run geographic map spike | Planned | P0 | license gate | Lifecycle, offline, attribution, and performance report. |
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
| MOB-024 | Validate compact phone information density | Delivered baseline | P0 | MOB-003 | One physical Android 16 phone has portrait evidence at approximately 394 dp, density 520, and font scales 1.15/1.30 plus a baseline landscape check. Separate API 36 emulator evidence covers the compact Duplicate Project and Delete Project dialogs at 1080 × 2400 pixels and 420 dpi, font scales 1.0/1.30, portrait and short landscape, with Gboard open/closed; the full device/accessibility matrix remains F8 work. |

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
| GIS | Provider contracts, CRS, NoData, hostile files, lifecycle, screenshots. |
| Link study | Independent golden cases by term, cancellation, persistence, manifest. |
| Coverage | Golden grids, edges/NoData, memory, time, cancellation, export. |
| Interoperability | Cross-schema fixtures, capability negotiation, parser fuzz/limits. |
| Release | Install/upgrade, offline, accessibility, performance, security, signing. |

## 10. Risks and mitigation

| Risk | Impact | Mitigation/gate |
|---|---|---|
| Later JSON schemas evolve without explicit migrations | Project loss or lockout | Schema 1→2 fixture is delivered; G3 still requires a published evolution/asset/backup policy and fixtures for every future public schema. |
| Hard delete is mistaken for archive or recovery | Irrecoverable project loss | Require exact `DELETE`, show current aggregate impact, reject stale snapshots, and commit atomically; G3 still requires archive, recovery/export, backup, and project-owned asset policy. |
| Catalog mutation policy diverges across processes or future stores | Newer state can be overwritten | In-process read-transform-write is serialized and concurrency-tested; define multi-process/conflict policy before another writer or store is introduced. |
| Saved-instance-state route tests are treated as complete process recovery | Selected durable state or nested context can still be lost after system termination | G2 still requires true process-death, deleted-ID, rotation, and device-matrix flows. |
| Technical Canvas is mistaken for a map | Incorrect geographic expectations | Keep Foundation label; G4 before map claims. |
| Free-space result is mistaken for terrain-aware engineering | Invalid field decision | Explicit limits in UI/docs; G5 before terrain-aware label. |
| Study result is not persisted | Lost evidence on restart | F4 immutable request/result/artifact model. |
| Package/schema published too early | Expensive compatibility burden | G0/G3 before public release. |
| Dataset size causes storage/ANR failure | Abandonment or corruption | Regional catalog, preflight, staging, limits, safe cleanup. |
| Raster computation overheats device | Process death or poor UX | G6, blocks, cancellation, benchmark, optional service. |
| JNI introduced prematurely | ABI crashes and maintenance load | Native only after Kotlin baseline and MOB-021. |
| Optional service becomes hidden dependency | Offline failure and privacy risk | Explicit consent, local result, documented fallback. |
| Tiles/data lack distribution rights | Release block | G0/G4; artifact-level license and attribution. |
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
- APK/AAB, database, cache, and offline-package sizes;
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
