# ATX Plan Android

Android RF-planning application for the ATX Plan ecosystem. The mobile foundation is designed to work **offline first**, preserve units and data provenance, and grow through verifiable numerical gates instead of screens that only imitate capabilities.

The product, source-facing messages, and project documentation use English as the canonical language. Proper nouns and official dataset names retain their original spelling.

## Current status

The foundation release delivers:

- a responsive Jetpack Compose and Material 3 shell for phones and tablets;
- a compact phone information-density pass with scalable typography, responsive feature layouts, denser cards and forms, and explicit 48 dp minimum targets for the controls changed by that pass;
- Navigation 3 with five areas plus typed, saveable nested RF-path and project-name editor routes;
- a local schema-2, strict UTF-8, size-limited project catalog with explicit v1 migration and transactional atomic mutation;
- project creation, selection, and rename with stale competing-rename protection; transactional project duplication from the latest durable source; bounded transactional project deletion with complete-snapshot conflict protection; and a combined Add RF Path flow that persists one linked network, transmitter site/sector, and receiver;
- a synthetic demonstration project with networks, sites, sectors, and study summaries;
- an offline engineering canvas showing site positions and active azimuths;
- a local link budget with FSPL/P.525, EIRP, received power, margin, noise floor, SNR, first Fresnel-zone calculations, and explicit in-memory result provenance;
- a UI-independent Kotlin domain/application model with validated engineering values, receiver/network references, deterministic use cases, and automated tests;
- a custom light/dark theme, API 23 minimum, and the `com.gecesars.atxplan` application ID;
- a GitHub Actions pipeline for build, tests, and lint.

This release does **not** claim to deliver a basemap, DEM, clutter, antenna patterns, `.atxp`/`.rp3` import, raster coverage, or advanced normative propagation engines. The **Data & capabilities** screen keeps those boundaries visible in the application.

Project duplication is a bounded local operation. It validates and normalizes the requested copy name, reads the latest durable source inside the repository transaction, assigns a fresh root project ID and fresh root creation/update timestamps, and preserves the complete project-scoped RF graph, nested IDs, references, data, order, demonstration flag, and study timestamps. The source is left unchanged, the copy is appended to the catalog and selected on commit. The copied project does not yet record source-project lineage or a duplication-provenance marker.

Project deletion is a separate bounded hard-delete operation. The dialog reports the current catalog impact and requires the exact `DELETE` keyword. The transaction compares the complete project aggregate that was reviewed with the latest durable aggregate; a peer rename, RF change, or study-summary change rejects the attempt for review instead of deleting newer data. A successful commit removes that project and its current metadata, networks, sites, sectors, receivers, and study summaries from the schema-2 catalog, then selects the next project in the original order, the previous project when the deleted project was last, or no project when the catalog becomes empty. No in-app backup or undo is created. Project archive, recovery, export, and project-owned external assets are not implemented, so this slice performs no external-asset cleanup.

## Documentation

- [Application map](docs/APPLICATION_MAP.md): desktop/RadioPlanner reference capabilities mapped to Android and their delivery state.
- [Roadmap](docs/ROADMAP.md): phases, priorities, gates, and Definition of Done.
- [Architecture](docs/ARCHITECTURE.md): UI/domain/data boundaries, offline-first behavior, persistence, and compute strategy.

## Stack and compatibility

| Item | Baseline |
|---|---|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose 1.11 (BOM 2026.04.01) + Material 3 |
| Navigation | AndroidX Navigation 3 |
| Current persistence | typed JSON in private app storage + `AtomicFile` |
| Gradle | Wrapper 9.3.1 |
| Android Gradle Plugin | 9.1.1 |
| Java | Java 17 bytecode; JDK 21 recommended for builds |
| Android | `minSdk 23`, `targetSdk 36`, `compileSdk 36.1` |

The file catalog is the first durable boundary, not the final solution for rasters and datasets. Room/SQLite, Storage Access Framework support, and immutable hash-addressed artifacts are introduced only after their data models stabilize.

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

Separately, the compact adaptive Duplicate Project and Delete Project dialogs were manually validated on the Android 16/API 36 emulator at 1080 × 2400 pixels and 420 dpi, in portrait and short landscape at font scales 1.0 and 1.30 with Gboard open and closed. The exact `DELETE` field remained fully visible. At font scale 1.30, portrait stacked the project actions; short landscape used the compact dialog, required one scroll to reach the field, and exposed the actions after the IME was hidden. These dialogs remain usable without reducing or overriding the system font scale.

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

The current 33-test instrumented suite passes on the Android 16/API 36 `Medium_Phone_API_36.1` emulator at 1080 × 2400 pixels and 420 dpi. It includes project-duplication draft, durable-selection, recreation, rejection, and mutation-completion coverage plus exact delete confirmation, changed-snapshot restoration, saved-state recovery, rejected-attempt recovery, durable absence, deterministic fallback selection, and Activity recreation after deletion. The preceding 18-test revision also passed on the physical Android 16 reference phone; the current suite still needs a fresh physical rerun after the device was disconnected.

The current local verification evidence also includes 125 passing JVM tests and lint with 0 errors and 12 dependency/tooling warnings.

## Code organization

```text
app/src/main/java/com/gecesars/atxplan/
|-- data/project/       # storage and concrete repository
|-- domain/application/ # injected use cases and transactional commands
|-- domain/model/       # projects, RF entities, typed units, and studies
|-- domain/rf/          # pure Kotlin calculations
|-- ui/components/      # shared components
|-- ui/forms/           # saveable RF-path draft and parsing boundary
|-- ui/navigation/      # typed stable-ID Navigation 3 routes
|-- ui/screens/         # Compose flows
|-- ui/theme/           # design system
|-- ui/AppViewModel.kt  # UDF state and coordination
`-- MainActivity.kt     # Android composition root
```

Extraction into separate Gradle modules will be incremental, after the boundaries and build benefit are demonstrated.

## Data safety

- External files will be treated as untrusted input.
- An invalid or future-schema catalog is never overwritten automatically.
- Project hard deletion is one atomic catalog replacement: a failed write preserves the previous catalog. The successful operation has no in-app backup or undo and does not claim external-asset cleanup.
- Missing physical data remains missing; `NoData` never silently becomes zero.
- Future datasets and services must record license, attribution, version, and hash.
- Keystores, local properties, secrets, APKs, and AABs are excluded from Git.

## Repository safety

The canonical working copy is a standalone repository. Before any `git add`, `commit`, remote, or push operation, verify that:

```powershell
git rev-parse --show-toplevel
```

returns the Android project directory itself and does not resolve to an enclosing desktop repository. The canonical remote supplied for this project is `https://github.com/Gecesars/ATX_PLA_APK.git`; local directory spelling does not change the ATX Plan product name or the `com.gecesars.atxplan` application ID.

## License and distribution

The repository is public, but the product license still requires an explicit decision before formal public distribution. Proprietary materials used as behavioral references are not included in this repository.
