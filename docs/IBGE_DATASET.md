# Embedded IBGE 2022 Census Sector Index

## Delivered scope

ATX Plan Android bundles a bounded national IBGE reference dataset for offline inspection. The delivered package supports:

- accent-insensitive municipality search by name and exact/prefix search by seven-digit IBGE code;
- the largest municipalities by resident population when the search field is empty;
- municipality totals for sectors, resident population, area, and urban, rural, unspecified, and `NoData` records;
- state identity and a municipality bounding envelope; and
- retention of all sector attribute rows and source bounding boxes for later, explicitly scoped work.

This package does **not** contain census-sector polygon geometry. It cannot render official sector or municipality boundaries, determine exact point containment, calculate population inside a coverage contour, provide a basemap or DEM, or support a regulatory conclusion. A stored envelope is only a rectangular extent, not an official boundary.

## Packaged release identity

| Property | Value |
|---|---|
| Dataset ID | `ibge-census-sectors-2022-brazil` |
| Census year | 2022 |
| Source CRS | SIRGAS 2000 geographic (`EPSG:4674`) |
| Sector rows | 468,099 |
| Municipality summaries | 5,570 |
| Unassigned sector rows | 2 |
| Missing population rows in this edition | 0 |
| Resident population sum (`v0001`) | 203,080,756 |
| Packaged asset size | 22,133,986 bytes (21.1 MiB) |
| Packaged asset SHA-256 | `0769c067211bb872871064e80ed2f2cf2a0d042b3f9c1f236517852d2b301112` |
| Installed SQLite size | 70,926,336 bytes (67.6 MiB) |
| Installed SQLite SHA-256 | `fd116b30b8d95abd7203ec5f013f820ea6bbd33022d2f979de7b8892f925d22b` |
| Database application ID | `0x41545849` (`ATXI`) |
| Database schema | 1 |

The two unassigned rows have no valid municipality code and remain `NULL`; they are not attached to a fabricated municipality. Population is nullable in the schema even though this pinned source edition contains no missing population values. `NULL` and zero therefore remain distinct.

## Source and provenance boundary

Attribution: **Source: IBGE — 2022 Census Sector Mesh and sector aggregates.**

- Official product page: <https://www.ibge.gov.br/geociencias/organizacao-do-territorio/malhas-territoriais/26565-malhas-de-setores-censitarios-divisoes-intramunicipais.html>
- Official national archive: <https://geoftp.ibge.gov.br/organizacao_do_territorio/malhas_territoriais/malhas_de_setores_censitarios__divisoes_intramunicipais/censo_2022/setores/shp/BR/BR_setores_CD2022.zip>
- Official archive size: 784,726,998 bytes
- Official archive SHA-256: `2674870a37718df4418f93dcca7d6931783f7b03f59562de82c7402324350750`
- Source accessed: 2026-08-27

The transformer's **immediate row source** is the desktop-derived read-only index `ibge-census-2022-01751dfb92b0b37a5b73.sqlite`:

- immediate-source size: 72,863,744 bytes;
- immediate-source SHA-256: `fe8b789027d54de02de5fd1ddac7c77325657ee09721672008cb6227009a91a7`; and
- recorded source signature: `01751dfb92b0b37a5b73f874b0f8a6e79165ab4242dd1a77e0cfc3526d2f2141`.

The transformer pins and verifies both the immediate index and the official archive, but it currently reads rows only from the immediate index. These independent hashes make input identity auditable and the logical Android derivation reproducible with the pinned transformer/toolchain; by themselves they do not prove the upstream row-by-row archive-to-index derivation. A release-grade upstream recipe or full archive-to-index cross-check remains required before claiming that stronger chain of custody.

The official archive contains no machine-readable license file. Its files are publicly downloadable from IBGE, but the applicable redistribution terms must be reviewed before a public APK/AAB release. Keep `NOTICE.txt`, attribution, URLs, access date, transformations, and hashes with every distributed package.

## Android storage and validation lifecycle

The gzip payload uses an app-owned, content-addressed `.ibgedata` filename and is opened as a streaming asset. On first Data Catalog access, the repository:

1. reads a strict UTF-8 manifest with a 64 KiB limit;
2. validates schema, identifiers, counts, sizes, hashes, source metadata, CRS, and the explicit no-geometry flag;
3. requires the 67.6 MiB installed size plus a 16 MiB safety allowance before extraction;
4. removes only recomputable files whose names exactly match the private content-addressed database pattern;
5. streams gzip extraction to a unique `.part` file while bounding output and hashing compressed and uncompressed bytes;
6. syncs the staged file, opens it read-only, and verifies the application ID, schema, `quick_check`, metadata, table counts, `NoData` count, sector and municipality population sums, and a known municipality row;
7. promotes the verified file atomically to `<database-sha256>.sqlite`; and
8. removes superseded content-addressed database versions while preserving unrelated files.

The installed database lives below `noBackupFilesDir/datasets/ibge`; application backup is disabled. Municipality queries use only the installed SQLite file and the IBGE repository performs no network request. The app-level `INTERNET` permission is used only by the separate, explicit regional-data acquisition flow.

The Android schema uses ordinary tables supported by framework SQLite rather than desktop `STRICT` or `RTree` tables. The tables are `metadata`, `state`, `municipality`, `sector`, and `sector_bounds`. The bounds table is portable storage for rectangles, not a spatial extension or polygon layer.

## Rebuild

Run the checked-in transformer from the repository root:

```powershell
py -3 tools\prepare_ibge_android_dataset.py `
  --source-index '<path-to-pinned-desktop-index.sqlite>' `
  --source-archive '<path-to-BR_setores_CD2022.zip>' `
  --output-directory 'app\src\main\assets\datasets\ibge'
```

The transformer fails closed on an unexpected input hash, archive size, schema, signature, row count, municipality count, population sum, invalid code, invalid bound, or non-finite value. It removes the desktop build path, converts the schema to the portable Android form, orders emitted data and metadata, vacuums the database, and writes gzip with timestamp zero. The generated asset name contains its full compressed SHA-256; the transformer publishes that immutable asset first and replaces `manifest.json` last as the commit point, then removes superseded generated assets.

The current asset was generated with Python's SQLite 3.50.4. Exact database bytes can vary if the SQLite writer version or page-layout behavior changes, so a toolchain change requires review and publication of new asset and database hashes even when logical rows remain identical.

## Verification boundary

Two repository instrumentation tests use the real packaged asset and cover fresh extraction, integrity/schema checks, offline municipality queries, exact-code lookup, literal wildcard handling, same-length corruption recovery, storage preflight failure, superseded-version cleanup, and reopen without reinstalling. Four IBGE-focused compact Catalog tests cover Ready/search/selection/limitations plus preparation, failure/retry, and query-failure behavior; three additional Catalog tests cover regional-data plan/license/start, explicit live-snapshot refresh, and running/cancel/limitation states. All nine pass as part of the 72-test connected suite. The current runtime evidence is the Android 16/API 36.1 emulator. Avoid claiming API 23 runtime proof until the same integration test runs on an API 23 device or emulator.
