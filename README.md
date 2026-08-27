# ATX Plan Android

Android RF-planning application for the ATX Plan ecosystem. The mobile foundation is designed to work **offline first**, preserve units and data provenance, and grow through verifiable numerical gates instead of screens that only imitate capabilities.

The product, source-facing messages, and project documentation use English as the canonical language. Proper nouns and official dataset names retain their original spelling.

## Current status

The foundation release delivers:

- a responsive Jetpack Compose and Material 3 shell for phones and tablets;
- a compact phone information-density pass with scalable typography, responsive feature layouts, denser cards and forms, and explicit 48 dp minimum targets for the controls changed by that pass;
- Navigation 3 with five areas plus typed, saveable nested RF-path, RF-assets, and project-name editor routes;
- a store-schema-1 indexed local store carrying project schema 5, with independently versioned immutable SHA-256 project documents, ordered legacy project migration through schema 5, strict UTF-8, and bounded reads;
- project creation, selection, and rename with stale competing-rename protection; transactional project duplication from the latest durable source; bounded transactional archive, restore, and logical hard deletion with complete-snapshot conflict protection; a combined Add RF Path flow; and independent create/edit/delete for networks, sites, sectors, and receivers with stale-snapshot and linked-deletion checks;
- a private content-addressed artifact-store foundation with streaming limits, SHA-256 verification, deduplication, and explicit available/missing/corrupt states;
- a synthetic demonstration project with networks, sites, sectors, and study summaries;
- an offline Web Mercator coordinate viewport with fit, pan, pinch zoom, metric scale, site selection, active azimuths, and stale-safe location-only site edits;
- a bundled, integrity-checked IBGE 2022 national attribute index with 468,099 sector records, 5,570 municipality summaries, offline normalized search, explicit `NoData`, and portable bounding envelopes;
- a local link budget with ITU-R P.525-5 FSPL, EIRP, received power, margin, noise floor, SNR, midpoint first-Fresnel-zone radius (not clearance), and explicit result provenance;
- a bounded project-linked point-to-point workflow that derives mean-Earth endpoint distance/bearing, uses an AGL-only inclined distance over a flat reference, and durably saves a fingerprinted scalar result with explicit terrain `NoData`;
- a UI-independent Kotlin domain/application model with validated engineering values, receiver/network references, deterministic use cases, and automated tests;
- a custom light/dark theme, API 23 minimum, and the `com.gecesars.atxplan` application ID;
- a GitHub Actions pipeline for build, tests, and lint.

This release does **not** claim to deliver a basemap, DEM, clutter, antenna patterns, `.atxp`/`.rp3` import, raster coverage, census-sector polygons, population-by-coverage, or advanced normative propagation engines. The **Data & capabilities** screen keeps those boundaries visible in the application.

Project duplication is a bounded local operation. It validates and normalizes the requested copy name, reads the latest durable source inside the repository transaction, assigns a fresh root project ID and fresh root creation/update timestamps, and preserves the complete project-scoped RF graph, nested IDs, references, data, order, demonstration flag, and study timestamps. Existing immutable link-study records remain unchanged, including the snapshotted source project identity from the original calculation; they are historical evidence and are not rebased to the copy's root ID. The source is left unchanged, the copy is appended to the catalog and selected on commit. The copied aggregate does not yet record a separate source-project lineage or duplication-provenance marker.

Project archiving is a bounded reversible operation inside the local store-schema-1 indexed store carrying project schema 5. The transaction compares the complete active project aggregate that was reviewed with the latest durable aggregate and rejects stale, repeated, or missing requests without writing. A successful commit retains the aggregate unchanged with an archive timestamp and its original active-list index, removes it from active selection and active project metrics, and selects the next active project, the previous one when the archived project was last, or no project when no active project remains. Restore compares the complete reviewed archive record, reinserts the unchanged aggregate at its original index clamped to the latest active catalog, and selects the restored project. The collapsible **Archived Projects** section exposes retained counts, archive time, and the restore action.

Project deletion remains a separate bounded logical hard-delete operation. The dialog reports the current catalog impact and requires the exact `DELETE` keyword. The transaction compares the complete project aggregate that was reviewed with the latest durable aggregate; a peer rename, RF change, or study-summary change rejects the attempt for review instead of deleting newer data. A successful commit removes that active project and its project-scoped records from the reachable catalog, then selects the next project in the original order, the previous project when the deleted project was last, or no project when the active catalog becomes empty. Archived projects must be restored before permanent deletion. No in-app backup or hard-delete undo is created. Immutable orphan files are intentionally retained until a separately verified cleanup policy exists.

All catalog mutations run against the latest durable catalog inside the repository transaction and publish state only after persistence succeeds. Rejected and no-op outcomes rebase the UI to that latest catalog without a write. The local archive is not backup, export, synchronization, or recovery for a permanently deleted project. Artifact attachment/import UI, reachability-based garbage collection, unreadable/future-store recovery, duplication lineage/provenance, a Room/SQLite project-store migration, multi-process coordination, portable containers, and file-ownership policy remain outside this slice.

The Engineering Map is a bounded offline geographic-coordinate tool, not a basemap claim. Pure Kotlin Web Mercator camera math drives project fitting, direct-manipulation pan/pinch zoom, antimeridian-aware projection, and a metric scale. Selecting a site exposes an accessible coordinate editor whose stale-safe transaction changes only latitude and longitude and publishes the new point only after durable storage succeeds. The screen permanently discloses that no third-party tiles, terrain, clutter, GIS features, or coverage are rendered. A non-null elevation is labeled as a stored project value and is not resampled when coordinates move; a missing value remains explicit `NoData`.

The project-linked P.525 study is also deliberately bounded. It snapshots one stored sector and compatible receiver, computes mean-Earth great-circle distance and bearing, uses only the endpoints' AGL antenna-height difference for inclined distance, and saves the complete reproducible scalar result. A stored transmitter ground elevation may be snapshotted but is not evaluated. Terrain remains `NoData`; Earth-curvature clearance, effective-Earth propagation, LOS, Fresnel clearance, diffraction, clutter, buildings, vegetation, atmospheric gas, rain, variability, and directional antenna-pattern attenuation are not calculated. See [Project-linked P.525 study](docs/PROJECT_LINK_STUDY.md) for formulas, persisted provenance, migration behavior, and exact exclusions.

## Embedded IBGE reference data

The APK includes a 21.1 MiB compressed, read-only IBGE 2022 Census Sector attribute package. First access extracts a 67.6 MiB SQLite database into private no-backup storage after disk preflight, bounded streaming, compressed and database SHA-256 checks, SQLite validation, and atomic promotion. The Data Catalog can search municipalities offline by normalized name or seven-digit code and inspect population, area, urban/rural/unspecified totals, missing-value counts, and bounding envelopes.

This is a bounded reference index, not a geometry or coverage-analysis engine. Sector polygons are not bundled, envelopes are not official boundaries, and the application cannot perform exact containment or population-within-coverage calculations. The immediate row source is a pinned desktop-derived index; the official IBGE archive is independently hash-pinned but is not parsed by the current transformer. Public redistribution remains blocked until the applicable IBGE terms are reviewed. See [Embedded IBGE dataset](docs/IBGE_DATASET.md) for exact hashes, provenance, lifecycle, rebuild instructions, and limitations.

## Documentation

- [Application map](docs/APPLICATION_MAP.md): desktop/RadioPlanner reference capabilities mapped to Android and their delivery state.
- [Roadmap](docs/ROADMAP.md): phases, priorities, gates, and Definition of Done.
- [Architecture](docs/ARCHITECTURE.md): UI/domain/data boundaries, offline-first behavior, persistence, and compute strategy.
- [Embedded IBGE dataset](docs/IBGE_DATASET.md): source boundary, exact package identity, Android lifecycle, rebuild, and capability limits.
- [Project-linked P.525 study](docs/PROJECT_LINK_STUDY.md): endpoint geometry, RF terms, immutable schema-5 record, tests, and explicit exclusions.

## Stack and compatibility

| Item | Baseline |
|---|---|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose 1.11 (BOM 2026.04.01) + Material 3 |
| Navigation | AndroidX Navigation 3 |
| Project persistence | atomic JSON index + immutable SHA-256 project documents/artifact blobs in private app storage |
| Bundled reference data | verified read-only SQLite extracted from a content-addressed IBGE asset into private no-backup storage |
| Gradle | Wrapper 9.3.1 |
| Android Gradle Plugin | 9.1.1 |
| Java | Java 17 bytecode; JDK 21 recommended for builds |
| Android | `minSdk 23`, `targetSdk 36`, `compileSdk 36.1` |

The store-schema-1 index and project-schema-5 document store, together with the content-addressed artifact store, are durable foundations, not a portable project package or a complete raster/dataset lifecycle. The separate embedded IBGE SQLite file is release-managed reference data and is not the project store. Room/SQLite remains an option for future granular project/job queries; Storage Access Framework import/export, artifact attachment, ownership, cleanup, and recovery policies are still required.

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

A separate manual API 36 check calculated and saved a project-linked study at font scale 1.30, force-stopped the application, relaunched it, and reopened the same endpoint, FSPL, received power, margin, SNR, provenance, and fingerprint. A real-storage instrumentation case also promotes an indexed schema-4 fixture to schema 5 and verifies integrity and reopen behavior. These are bounded local-storage observations; JVM fault injection remains the evidence for interrupted publication paths.

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

The current 68-test instrumented suite passes with no failures or skips on the Android 16/API 36 `Medium_Phone_API_36.1` emulator at 1080 × 2400 pixels and 420 dpi; the final connected run used system font scale 1.30. It covers project lifecycle and conflict behavior, saved navigation/state, RF asset management, Engineering Map editing, the real packaged IBGE extraction/query/recovery path, compact Catalog states, five project-link Studies cases, and one real-storage indexed schema-4-to-5 migration/integrity/reopen case. The Studies cases exercise 360 × 480 dp/font-scale-1.30 selector/action/limitation reachability, complete saved terms and warnings, lazy chronological history, collision-safe sector identity, and the explicit no-compatible-receiver state. The preceding 18-test revision also passed on the physical Android 16 reference phone; the current suite still needs a fresh physical rerun after the device was disconnected, and API 23 runtime proof remains open.

The current local verification evidence also includes 252 passing JVM tests, lint with 0 errors and 12 dependency/tooling warnings, and successful debug/test APK assembly.

## Code organization

```text
app/src/main/java/com/gecesars/atxplan/
|-- data/dataset/       # verified bundled IBGE SQLite repository
|-- data/project/       # storage and concrete repository
|-- domain/application/ # injected use cases and transactional commands
|-- domain/dataset/     # dataset identity, progress, query models, and contract
|-- domain/geo/         # pure Web Mercator camera, gesture, fit, and scale math
|-- domain/model/       # projects, RF entities, typed units, and studies
|-- domain/rf/          # pure Kotlin calculations
|-- ui/components/      # shared components
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

The repository is public, but the product license still requires an explicit decision before formal public distribution. Distribution of an APK/AAB containing the derived IBGE asset also requires owner review of the applicable IBGE redistribution terms and retention of its notice, attribution, source identity, and limitations. Proprietary materials used as behavioral references are not included in this repository.
