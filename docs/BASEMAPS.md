# Interactive basemap providers

## Delivered scope

The Engineering Map can render HTTPS XYZ raster tiles below local sites and RF contour geometry. The provider catalog is fixed in source, supports at most 10 entries, and currently contains the six remote providers exposed by the desktop application:

| Provider | XYZ host | Zoom | Terms boundary |
|---|---|---:|---|
| OpenStreetMap | `tile.openstreetmap.org` | 0–19 | Interactive visible-view requests only; offline-area and bulk downloads are prohibited. |
| OpenTopoMap | `a.tile.opentopomap.org` | 0–17 | Best-effort interactive use subject to the provider's current terms and capacity. |
| CyclOSM | `a.tile-cyclosm.openstreetmap.fr` | 0–20 | Interactive use; review the project/provider terms before production use. |
| Humanitarian HOT | `a.tile.openstreetmap.fr` | 1–19 | The public service is limited to free, public, non-profit apps with moderate traffic. |
| OpenStreetMap France | `a.tile.openstreetmap.fr` | 0–20 | The public service is limited to free, public, non-profit apps with moderate traffic. |
| OpenStreetMap Deutschland | `tile.openstreetmap.de` | 0–19 | Non-commercial interactive display; bulk/offline downloads are prohibited. |

Provider selection never changes RF calculations. Tiles are a presentation layer; sites, contours, thresholds, warnings, provenance, and `NoData` continue to come from local engineering state.

## Request and cache contract

- Only tiles intersecting the current human-visible viewport are planned. There is no bounding-box download, multi-zoom prefetch, background scraper, archive builder, or offline-region API.
- Raster zoom is selected from the fractional Web Mercator camera and physical display density. A single request is capped at 48 unique XYZ tiles.
- Every request uses HTTPS, a fixed provider host allowlist, same-origin redirects, bounded URL/header values, a stable ATX Plan user agent, and explicit raster `Accept` types.
- Each response must be HTTP 200, at most 2 MiB, have a supported PNG/JPEG/WebP signature, decode to a supported raster MIME type, and remain at or below 1,024 pixels on each axis.
- Valid files are promoted from a private random `.part` file. An older valid tile remains available if refresh fails.
- Tiles are stored under `noBackupFilesDir/basemap-tiles-v1`, retained for at least seven days before refresh, and bounded to 128 MiB with oldest-first eviction outside the active viewport.
- The selected provider attribution remains visible on the map. The compact control card exposes the provider usage notice, cache/tile state, and current terms link.

The cache permits repeat viewing and opportunistic disconnected reuse of tiles already fetched during normal interactive use. It is not an offline map package and does not promise complete coverage in airplane mode.

## Desktop parity boundary

The Android catalog matches the desktop application's six remote XYZ choices. The desktop `Natural Earth (offline)` fallback is not yet ported because its installed-vector package and lifecycle are separate from XYZ viewport caching. The Android catalog limit leaves room for four additional reviewed providers without widening the provider trust boundary.

## Verification

Pure JVM tests cover provider/template validation, the 10-provider ceiling, density-aware visible-tile planning, XYZ matrix bounds, response validation injection, private cache reuse, and the 48-tile request ceiling. Android lint and debug builds include the renderer and controls.

On August 31, 2026, the debug build was installed with data preservation on the connected Android 16/API 36 physical device (`25080RABDG`, 1280 × 2772, density 520). Opening the persisted independent São Paulo channel 42 study requested and rendered 35 OpenStreetMap z11 tiles below the complete protected contour. The canvas reported camera z8.8 and retained visible provider attribution. This is bounded device evidence for that provider, project, viewport, orientation, and network state; it is not a provider SLA, full device matrix, offline-package proof, or evidence for all six services.

## Remaining work

- Revalidate every provider's eligibility before public or commercial distribution and remove providers whose terms do not match the product model.
- Add conditional revalidation from `ETag`/`Last-Modified` and provider cache headers; the current cache uses the documented minimum seven-day lifetime.
- Port an authorized local Natural Earth or other offline package with integrity, version, storage, update, removal, and airplane-mode evidence.
- Add network-metering policy, provider-specific load shedding, cache inspection/removal UI, performance/memory benchmarks, and a broader physical-device matrix.
- Keep terrain, clutter, building, IBGE geometry, coverage rasters, and regulatory evidence as independent versioned layers rather than implying they are supplied by a visual basemap.
