# Android Roadmap

> Evidence baseline: August 24, 2026. The repository has moved beyond an Android template: it now contains an adaptive Compose/Navigation 3 shell, an atomic JSON project repository, a validated demonstration domain, five product areas, a bounded RF calculator, CI, unit tests, and an Android 16 instrumented smoke test. This roadmap does not treat those foundations as complete desktop or RadioPlanner parity.

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
| Build and lint | Delivered | Debug/test APKs built; latest lint has 0 errors. | Nine dependency-version warnings and no signed release. |
| CI | Delivered | GitHub Actions runs unit tests, lint, debug APK, and debug test APK builds. | Connected Android test is not in CI. |
| Compose theme and shell | Delivered | Custom light/dark ATX theme, Material 3, edge-to-edge, compact bottom bar, expanded navigation rail. | Full accessibility, locale enforcement, and device-matrix validation. |
| Navigation 3 | Foundation | Five top-level destinations render through `NavDisplay`. | Restorable typed back stack, deep links, internal destinations, and process-death tests. |
| UDF/ViewModel | Foundation | `AppUiState`, `StateFlow`, lifecycle collection, ViewModel coordination, repository callbacks, notice/error state. | Explicit actions/effects, use cases, DI, injected dispatchers, ViewModel tests. |
| Project persistence | Delivered baseline | Schema-1 typed JSON in private storage with `AtomicFile`, `fd.sync`, 5 MiB limit, future-schema rejection, and rollback on failed save. | Atomicity is per write; save ordering is not serialized. Migration, concurrency, failure-injection, and recovery tests are missing. |
| Project workflow | Foundation | Load, create, select, and display projects; seed synthetic demo when catalog is absent. | Rename/delete/duplicate/archive, RF entity CRUD, receivers, scenarios, imports. |
| Domain model | Foundation | Catalog, project, network, RF system, site, sector, coordinate, and study-summary models with validation. | Canonical unit value types, receiver, datasets, study requests/results, artifacts. |
| Engineering canvas | Foundation | Offline Canvas plots sites and active azimuths. | Real GIS/map renderer, projection, camera, offline source, editing, attribution. |
| RF calculation | Delivered | FSPL, EIRP, received power, fade margin, midpoint Fresnel radius, thermal noise, and SNR. | Geodesy, terrain, curvature, LOS, patterns, diffraction, persistence, manifest. |
| Unit tests | Delivered | Nine passing tests across project model/serialization, RF formulas, and English-only source hygiene. | Repository, ViewModel, migration, navigation, and export tests. |
| Instrumented test | Delivered | One passing Compose navigation smoke test on Android 16. | Broader flow, accessibility, restoration, and CI execution. |
| Product language | Delivered baseline | Production UI/errors/demo/tests and documentation are English; a unit test scans production sources for common Portuguese terms. | The blacklist is partial and must expand with new resource types. |
| Public release | Blocked | Debug baseline only; backup disabled. | License, signing, SBOM, privacy, shrinker, upgrade testing, release channel. |

## 3. Priority matrix

### P0 — Offline mobile MVP

Already delivered or founded:

- Android identity and API 23–36.1 compatibility;
- reproducible debug build and CI workflow;
- Compose/Material 3 shell and custom theme;
- basic Navigation 3 top-level navigation;
- ViewModel/repository state flow;
- schema-1 atomic JSON catalog;
- project create/select and synthetic demo;
- free-space RF calculator and numerical unit tests.

Still required to close P0:

- continued English-only enforcement and complete accessibility review;
- restorable typed Navigation 3 routes;
- tested persistence migrations and project lifecycle;
- canonical RF unit value types, receivers, and editable site/sector data;
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
| **G2 — Application architecture** | UDF, ViewModel, Navigation 3, dependency assembly, error model, restoration, and observability demonstrated. | **Foundation:** ViewModel/StateFlow/repository/Nav3 exist; restoration, DI, use cases, and tests remain. | Scaling feature count safely. |
| **G3 — Durable persistence** | Exported schema, migrations, safe writes, backup policy, recovery, and data ownership validated. | **Foundation:** atomic schema-1 JSON exists; migration/recovery tests and long-term store decision remain. | Real user projects and portable assets. |
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
- Navigation 3 dependency and top-level display;
- atomic JSON repository and demonstration project;
- free-space RF calculator;
- CI workflow, unit tests, lint, debug APK/test APK;
- Android 16 instrumented navigation smoke test;
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

- `AppUiState` and `StateFlow`;
- lifecycle-aware state collection;
- ViewModel factory and repository interface;
- storage-error rollback and notices;
- Navigation 3 `NavDisplay` with five top-level routes;
- compact/expanded navigation UI;
- shared screen components and custom design tokens.

**Remaining scope:**

- explicit `UiAction`/`UiEffect` contracts or documented equivalent;
- use-case boundary between ViewModel and domain/data;
- dependency injection/composition policy and injected dispatchers;
- typed feature route API and saved/restored back stack;
- process-death, invalid-route, and adaptive list/detail tests;
- structured problems, sanitized diagnostics, and durable job model;
- accessibility semantics, focus, contrast, and text-scaling validation;
- maintain English-only UI and diagnostics as features are added.

**Demonstrator:** kill and restore the process while a selected project and nested destination remain recoverable from IDs.

**Exit gate:** G2.

**Definition of Done:**

- no Composable accesses files, repository implementation, or calculator directly;
- no ViewModel owns an Activity or visual controller;
- navigation and selected durable state recover after process death;
- loading, empty, content, recoverable error, and retry states have tests;
- dispatchers and repositories are replaceable in tests;
- UI strings comply with the English-only policy.

### F2 — Complete project and RF entity lifecycle

**State:** Foundation implemented.

**Objective:** evolve the current catalog/demo into editable, durable engineering projects.

**Delivered foundation:**

- schema-1 `ProjectCatalog` and `PlannerProject`;
- validated `RfNetwork`, `RadioSite`, `Sector`, `GeoPoint`, and `StudySummary`;
- create/select/save project workflow;
- 5 MiB defensive limit, future-schema rejection, and atomic replacement;
- JSON serialization round-trip test;
- synthetic FM demo with one network, three sites, and two study summaries.

**Remaining scope:**

- typed value objects for coordinates, frequency, bandwidth, power, gain, loss, distance, and angle;
- receiver/CPE, scenario, dataset reference, study request/result, and artifact models;
- create/edit/delete sites, sectors, receivers, networks, and metadata;
- rename, duplicate, archive, and delete projects;
- conflict and referential-integrity diagnostics;
- schema migration fixtures from every public version;
- decision and transition plan for JSON catalog versus Room/SQLite and asset files;
- failure injection around atomic writes and storage exhaustion.

**Vertical slice:** create project → add site/sector/receiver → kill process → reopen with IDs, precision, units, and links preserved.

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
| MOB-004 | Introduce Navigation 3 top-level display | Foundation | P0 | MOB-003 | Current `NavDisplay`; restoration/deep-link work remains. |
| MOB-005 | Introduce ViewModel/StateFlow/repository | Foundation | P0 | MOB-003 | Current state flow and repository boundary; tests/use cases remain. |
| MOB-006 | Implement atomic schema-1 JSON catalog | Delivered | P0 | MOB-005 | `AtomicFile`, sync, size/schema checks, private storage. |
| MOB-007 | Implement project create/select and demo | Delivered | P0 | MOB-006 | User project creation and synthetic seeded demo. |
| MOB-008 | Implement pure Kotlin RF baseline | Delivered | P0 | domain baseline | FSPL/EIRP/received/noise/SNR/Fresnel-radius tests. |
| MOB-009 | Enforce English-only product language | Delivered baseline | P0 | all layers | English production sources/tests plus automated regression scan. |
| MOB-010 | Harden restorable typed navigation | Planned | P0 | MOB-004 | Process-death, invalid-route, and tablet tests. |
| MOB-011 | Define durable schema evolution | Planned | P0 | MOB-006 | Persistence ADR and non-destructive migration fixture. |
| MOB-012 | Complete RF value types and entity CRUD | Planned | P0 | MOB-007/011 | Site/sector/receiver lifecycle and round trip. |
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
| Existing foundation | Unit, lint, debug APK, instrumented Dashboard→Studies smoke. |
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
| Current JSON schema evolves without migration | Project loss or lockout | G3; fixtures and non-destructive migration policy. |
| Concurrent catalog saves complete out of order | Newer project/selection state can be overwritten | Serialize repository writes and add concurrency/rollback tests in G3. |
| Basic Nav3 stack is treated as complete | Lost navigation after process death | G2; typed/restorable routes and tests. |
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
