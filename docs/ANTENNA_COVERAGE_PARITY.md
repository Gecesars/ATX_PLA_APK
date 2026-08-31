# Antenna, Coverage, Interference, and Export Parity

> Audit date: August 31, 2026. Reference implementation: the sibling
> `eftx_antenna_pattern-main` repository. Android evidence is limited to code and tests in this
> repository. A desktop feature is not an Android result merely because its source was inspected.

## Status language

- **Delivered**: user-reachable end-to-end Android behavior with bounded inputs and tests.
- **Foundation**: reusable domain or storage behavior exists, but the complete user workflow does not.
- **Planned**: the reference behavior is mapped, but its Android implementation is not delivered.
- **Blocked**: a required model, dataset, license, validation corpus, or product decision is missing.

## Current parity matrix

| Capability | Android status | Evidence and boundary | Remaining work |
|---|---|---|---|
| Canonical HRP/VRP model | Delivered bounded slice | Fixed one-degree HRP `0..359` and VRP `-90..+90`, normalized linear field `E/Emax`, optional phase, availability state, coordinate convention, provenance, and content identity. | Preserve arbitrary source resolution in the canonical artifact while keeping the bounded project cache. |
| PAT import/export | Delivered bounded slice | Progira EDX PAT read/write with 360-point HRP, 1801-point VRP, linear field, explicit gain requirement on export, and disclosed phase loss. | Add independent external-reader fixture coverage for more producer variants. |
| PRN import/export | Delivered bounded slice | Positive field attenuation in dB, optional phase, explicit convention override for ambiguous input, frequency requirement, and bounded back-hemisphere `NoData`. | Add a larger legally distributable cross-vendor golden corpus. |
| Other antenna interchange | Delivered bounded slice | ADT HRP/VRP, V-Soft HRP/VRP, explicit-plane generic tables, native ATX JSON, desktop antenna JSON subset, and paired HRP/VRP source packages. | Full desktop project containers (`.atxp` and `.rp3`) are not supported. |
| Coherent array calculation | Foundation with delivered engine | CPU-only complex-field sum supports per-element position, pattern, power, feed phase, orientation, enable state, wavelength geometry, bounded adaptive 3D integration, gain/directivity, HRP, and VRP. | Arbitrary per-element editing and reusable configuration persistence are absent. |
| Forward synthesis UI | Delivered bounded slice | Single element, vertical stack, horizontal linear, planar, circular, and outward-oriented multipanel geometry; wavelength spacing/radius; horizontal/vertical scan; uniform/cosine/binomial taper where physically applicable; project attachment; and artifact-correlated export. | Add arbitrary per-element edit, explicit feed phase/delay, polarization/grouping, Chebyshev taper, null fill, tower/feed effects, batch workflow, and saved design configurations. |
| Inverse synthesis | Planned | Desktop goals, masks, HPBW, SLL, F/B, nulls, mechanical constraints, deterministic seed, cancellation, budgets, and Top-K behavior are mapped. | Implement a deterministic CPU solver, intermediate metrics, independent goldens, and a reviewable Android workflow. |
| Patch antenna and feed-network design | Planned | Desktop analytical rectangular/circular/stacked patch, inset/coax/aperture/proximity feeds, corporate/series networks, dividers, substrate/material, bandwidth, and return-loss features are mapped. | Port analytical models with units and validity ranges; add SVG/DXF geometry and state clearly that results are preliminary, not full-wave validation. |
| Full-wave or measured validation | Blocked | No Android full-wave solver or measured validation pipeline is delivered. GPU-only desktop paths are intentionally out of scope. | Requires approved solver/runtime, device budget, licensing, and independent measurement fixtures. |
| Digital-TV protected contour | Delivered bounded regulatory calculation | Independent project transmitter, terrain-derived radial HNMT, P.1546-6 land tables, derived `E(50,90)`, 72 radials, explicit `NoData`, dataset hashes, and filing gates. | A bare-earth DTM and current verified Basic Plan snapshot are still required for filing readiness. |
| FM protected/statistical overlays | Delivered reference slice | `E(50,50)` protected reference and a separately labeled non-regulatory `E(50,10)` screen. Unsupported `E(80,80)` remains explicit `NoData`. | Complete the current normative FM interference workflow and legal review; do not relabel the statistical screen as regulatory. |
| Coverage field surface | Delivered operational slice | CPU-only `181 x 181` Web Mercator-aligned grid, P.1546-derived field in `dBµV/m`, terrain/HNMT, verified HRP and VRP shaping, transparent `NoData`, cancellation, and bounded memory. | Persist executable scenarios/snapshots, add external numerical goldens, comparison, best-server, overlap, and C/(I+N). |
| Coverage rendering | Delivered operational slice | Basemap-first layer ordering, desktop-compatible broadcast bands, continuous interpolation, Turbo heatmap, 50% palette alpha, legend, range, units, and `NoData` disclosure. | Add export-quality PNG/GeoTIFF and performance evidence across the supported device matrix. |
| Protected-boundary D/U | Delivered bounded slice | Cochannel `19 dB` and first-adjacent `-36 dB`, desired/undesired fields, P.526-15 Deygout-Assis loss, pass/fail/`NoData` per protected radial, aggregate worst case, reference provenance, and map markers. | Add area/grid D/U and independent normative vectors. Boundary dots are not an interfering iso-field contour. |
| Interfering iso-field contours | Planned / blocked | No Android result is currently labeled as a regulatory interfering contour. | Requires an approved unwanted-field statistical model, normalized reference-station antenna patterns or an approved fallback, terrain treatment, threshold derivation, and goldens. |
| Basic Plan channel use | Delivered boundary | Anatel records are read-only reference inputs for viability and D/U. Project transmitter values remain independent. | Add automatic candidate discovery and review without copying catalog values into project-owned fields. |
| KMZ | Delivered bounded slice | Deterministic protected/service-contour geometry, metadata, radial evidence, manifest, explicit omissions, and destination read-back/hash verification. | Add coverage raster ground overlay and D/U evidence layers without conflating screen layers with regulatory contours. |
| HTML study report | Delivered bounded slice | Self-contained report with project inputs, methods, provenance, blockers, radials, D/U summary, and D/U point evidence. | Add optional map/coverage figures. |
| PDF study report | Delivered bounded slice | Dependency-free paginated PDF generated on device with project inputs, methods, provenance, blockers, radial table, D/U summary/points, fingerprint, and English-only text. | Add plotted map/coverage figures, PDF/A decision, and broader external-reader/device validation. |
| XLSX study data | Delivered bounded slice | Six-sheet OOXML workbook: Summary, Protected Radials, D-U Summary, D-U Points, Coverage Values, and Provenance. Numeric cells remain numeric, `NoData` is explicit, formula-like untrusted text is neutralized, and output is bounded. | Visual verification with `@oai/artifact-tool` is blocked in the current environment because the required package is absent; broaden external-reader validation. |
| Full antenna technical package | Planned | Desktop package contents are mapped: design, geometry, elements, feed, weights, pattern cuts, 3D pattern, PAT, PRN, HTML, PDF, index, manifest, and ZIP. | Implement an Android package manifest, per-file hashes, deterministic ZIP, report figures, and round-trip verification. |

## Numerical and coordinate contracts

- Geographic azimuth is north-clockwise. Antenna HRP is relative to sector boresight.
- Vertical angles are positive above the local horizon. Negative project electrical tilt points downward.
- Stored pattern values are linear field amplitude `E/Emax`; directional ERP applies the squared field ratio exactly once.
- Coverage and contour field strength uses `dBµV/m`. ERP is explicit in `kW`; heights are explicit as AGL, AMSL, or HNMT.
- A verified HRP/VRP may shape an operational coverage surface. A missing, placeholder, or identity-mismatched pattern uses a disclosed omnidirectional fallback.
- Separable HRP and VRP cuts do not reconstruct a measured full 3D pattern. Synthesized results are not measured, full-wave simulated, certified, or homologated results.
- `NoData` is transparent in rasters and remains textual `NoData` in reports/workbooks. It is never converted to zero.

## Coverage palette contract

Palette ID: `DISCRETE_BROADCAST_DBVM_45_80`; alpha: `128`; unit: `dBµV/m`.

| Range | RGB |
|---|---|
| `45 <= E < 50` | `#FFA500` |
| `50 <= E < 55` | `#87CDF9` |
| `55 <= E < 60` | `#FF00FF` |
| `60 <= E < 65` | `#A52929` |
| `65 <= E < 70` | `#0000FF` |
| `70 <= E < 75` | `#007F00` |
| `75 <= E < 80` | `#FF0000` |
| `E >= 80` | `#FFFF00` |
| below `45` or `NoData` | transparent |

Discrete rendering classifies before drawing and must not smooth class boundaries. Continuous mode
interpolates the same broadcast colors. Heatmap mode uses a bounded Turbo color function over
`45..80 dBµV/m`.

## Pre-installation gates

1. Complete the current pure/JVM test suite, lint, debug APK, and Android-test APK builds.
2. Run map, coverage-mode, PDF, XLSX, KMZ, PAT, and PRN flows on the emulator with compact and large font settings.
3. Verify PDF page rendering and text extraction; verify XLSX package structure, formulas, sheet bounds, and external rendering when the required artifact tool is available.
4. Keep interfering iso-field contours labeled Planned/Blocked until their model and validation gates close.
5. Do not claim complete antenna parity until the advanced topology UI, inverse synthesis, patch/feed design, batch mode, 3D review, and technical package are delivered.
6. Install on a physical device only after the owner requests the final installation and all applicable gates above pass.
