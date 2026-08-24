# ATX Plan Android

Android RF-planning application for the ATX Plan ecosystem. The mobile foundation is designed to work **offline first**, preserve units and data provenance, and grow through verifiable numerical gates instead of screens that only imitate capabilities.

The product, source-facing messages, and project documentation use English as the canonical language. Proper nouns and official dataset names retain their original spelling.

## Current status

The foundation release delivers:

- a responsive Jetpack Compose and Material 3 shell for phones and tablets;
- Navigation 3 with five areas: dashboard, projects, map, studies, and data;
- a local, versioned, size-limited project catalog saved with atomic writes;
- project creation and selection;
- a synthetic demonstration project with networks, sites, sectors, and study summaries;
- an offline engineering canvas showing site positions and active azimuths;
- a local link budget with FSPL/P.525, EIRP, received power, margin, noise floor, SNR, and first Fresnel-zone calculations;
- a UI-independent Kotlin domain model with numerical tests;
- a custom light/dark theme, API 23 minimum, and the `com.gecesars.atxplan` application ID;
- a GitHub Actions pipeline for build, tests, and lint.

This release does **not** claim to deliver a basemap, DEM, clutter, antenna patterns, `.atxp`/`.rp3` import, raster coverage, or advanced normative propagation engines. The **Data & capabilities** screen keeps those boundaries visible in the application.

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

## Code organization

```text
app/src/main/java/com/gecesars/atxplan/
|-- data/project/       # storage and concrete repository
|-- domain/model/       # projects, networks, sites, sectors, and studies
|-- domain/rf/          # pure Kotlin calculations
|-- ui/components/      # shared components
|-- ui/screens/         # Compose flows
|-- ui/theme/           # design system
|-- ui/AppViewModel.kt  # UDF state and coordination
`-- MainActivity.kt     # Android composition root
```

Extraction into separate Gradle modules will be incremental, after the boundaries and build benefit are demonstrated.

## Data safety

- External files will be treated as untrusted input.
- An invalid or future-schema catalog is never overwritten automatically.
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
