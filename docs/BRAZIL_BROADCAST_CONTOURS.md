# Brazil Broadcast Service Contours

> Evidence baseline: September 1, 2026. Android provides bounded CPU-only reference overlays and a separate terrain-backed bidirectional FM/digital-TV D/U workflow. Historical statistical overlays remain `regulatory = false`; the current-rule result independently fails closed when a required input or filing gate is incomplete.

## 1. Status and purpose

The current slice makes the Brazilian FM and digital-TV contour rules visible on the existing offline Engineering Map without hiding missing engineering inputs. It provides:

- deterministic protected-contour **reference** geometry for eligible active FM and digital-TV sectors;
- explicitly non-regulatory FM and digital-TV `E(50,10)` cochannel and first-adjacent envelopes reconstructed from revoked rules;
- explicit `NoData` for the requested but unsupported FM `E(80,80)` profile;
- compact provenance, threshold, status, model, ruleset, and warning text next to the map;
- pure Kotlin/CPU computation with no GPU, native runtime, remote service, or network dependency.

The word **legacy** is essential for each standalone `E(50,10)` envelope. Current Acts 8104/2022 and 9751/2022 use point-to-point ITU-R P.526 associated with Assis (1971), not a standalone E(50,10) interfering contour. The separate current-rule study evaluates FM/FM and digital-TV/digital-TV pairs at both wanted protected contours. On-demand terrain, catalog, urban-coverage, licensed-baseline, and completeness gates are delivered; filing readiness remains fail-closed pending independent numerical validation and qualified professional, legal, and source-license review.

## 2. Current Brazilian rule profiles

The rule catalog was checked on August 31, 2026 and is pinned in code by Act or revoked-resolution ID and source URL.

The Ministry of Communications was checked as a separate authority boundary. Its current regulatory-fiscalization page states that channel-distribution plans and station technical oversight are Anatel responsibilities. The MCom consolidation therefore does not supply a different field-strength plotting formula for this implementation; the technical formulas remain pinned to the Anatel acts, while MCom rules govern their own administrative and service processes.

The current identifiers are `ANATEL-ACT-8104-2022` for FM and `ANATEL-ACT-9751-2022` for digital TV. Historical envelopes use `ANATEL-RESOLUTION-67-1998-REVOKED` and `ANATEL-RESOLUTION-398-2005-REVOKED`. Unsupported FM `E(80,80)` uses `UNSUPPORTED-E80-80`.

| Purpose | Service and band | Field-strength threshold | Statistical basis | Android behavior |
|---|---|---:|---|---|
| Protected reference | FM | 66 dBµV/m | `E(50,50)` | Computed as a non-regulatory planning reference when the stored inputs fit the packaged model domain. |
| Protected reference | Digital TV, channels 7–13 | 43 dBµV/m | `E(50,90) = 2 × E(50,50) − E(50,10)` | Computed as a non-regulatory planning reference. A raw 90% time request is not made. |
| Protected reference | Digital TV, channels 14–51 | 51 dBµV/m | Same Anatel `E(50,90)` transform | Computed as a non-regulatory planning reference. |
| Legacy interfering envelope | FM cochannel | 32 dBµV/m | `E(50,10)` | Protected 66 dBµV/m minus the revoked 34 dB D/U ratio; red dash-dot and non-regulatory. |
| Legacy interfering envelope | FM first adjacent, ±200 kHz | 60 dBµV/m | `E(50,10)` | Protected 66 dBµV/m minus the revoked 6 dB D/U ratio; red dash-dot and non-regulatory. |
| Legacy interfering envelope | Digital TV cochannel, channels 7–13 / 14–51 | 24 / 32 dBµV/m | `E(50,10)` | Protected 43 / 51 dBµV/m minus 19 dB; revoked method and non-regulatory. |
| Legacy interfering envelope | Digital TV first adjacent, channels 7–13 / 14–51 | 79 / 87 dBµV/m | `E(50,10)` | Protected 43 / 51 dBµV/m minus −36 dB; revoked method and non-regulatory. |
| Requested unsupported profile | FM | `NoData` | `E(80,80)` | Never computed or drawn. No current Anatel FM rule defines it, and P.1546 does not permit a direct 80% time prediction. |

The first percentage is the percentage of locations and the second is the percentage of time. Digital-TV `E(50,90)` is the transform required by Act 9751; it is not an out-of-domain `timePercent = 90` P.1546 call. E(50,10) is also a normative operand inside that protected-contour transform. That fact does not make the standalone historical envelopes current regulatory interference results.

Digital-TV frequencies that do not resolve to channels 7–51 produce `NoData`. `TV_BROADCAST` is accepted only as digital TV when its stored channel and frequency form a consistent channel 7–51 pair; analog TV and retransmission records are excluded.

Authoritative sources:

- [Anatel Act 8104/2022 — FM, RTR, and Radiovias](https://informacoes.anatel.gov.br/legislacao/component/content/article/159-atos-de-requisitos-tecnicos-de-gestao-do-espectro/2022/1687-ato-8104)
- [Anatel Act 9751/2022 — TV and RTV](https://informacoes.anatel.gov.br/legislacao/component/content/article/159-atos-de-requisitos-tecnicos-de-gestao-do-espectro/2022/1688-ato-9751)
- [Revoked Anatel Resolution 67/1998 — historical FM E(50,10)](https://informacoes.anatel.gov.br/legislacao/resolucoes/2004/resolucoes/13-1998/168-resolucao-67)
- [Revoked Anatel Resolution 398/2005 — historical TV E(50,10)](https://informacoes.anatel.gov.br/legislacao/resolucoes/resolucoes/20-2005/288-resolucao-398)
- [MCom regulatory-fiscalization authority boundary](https://www.gov.br/mcom/pt-br/assuntos/radio-e-tv-aberta/fiscalizacao_regulatoria)
- [MCom SECOE Consolidation Ordinance 2/2023](https://www.gov.br/mcom/pt-br/assuntos/radio-e-tv-aberta/portaria_consolidacao)
- [Recommendation ITU-R P.1546-6](https://www.itu.int/rec/R-REC-P.1546/en)

The checked source date is not a promise that the rules will never change. A later application release must revalidate the acts, preserve the ruleset used by old results, and never silently reinterpret saved evidence under a newer rule.

## 3. Delivered reference-planner contract

`BrazilBroadcastContourPlanner` scans active sectors whose linked network is active and whose system is FM or TV broadcast. For an eligible sector it currently:

1. uses the sector frequency and the applicable rule profile;
2. derives nominal ERP in kilowatts from stored transmit power, stored dBi gain, feeder loss, and an explicit 2.15 dB isotropic-to-dipole conversion;
3. for each of 72 true-north, clockwise radials at 5-degree intervals, resolves an assigned calculation-ready HRP at `wrap360(trueBearing − sectorAzimuth)`, periodically interpolates its linear `E/Emax` amplitude, and applies `ERP_radial = ERP_peak × (E/Emax)^2` exactly once; a missing calculation-ready cut uses the explicitly warned nominal omnidirectional fallback, while zero or nonfinite assigned field produces radial `NoData` without fallback;
4. uses stored sector antenna height AGL as an effective-height proxy because radial HNMT terrain samples are unavailable;
5. finds the outer threshold crossing inside the packaged 1–1,000 km model domain, then generates WGS 84 points with destination geodesy on a fixed 6,371.0088 km mean-Earth sphere;
6. closes complete protected and legacy interfering rings, marks a threshold still exceeded at 1,000 km as `INCOMPLETE`, and returns `NoData` when no valid crossing or model input exists;
7. attaches service, purpose, curve basis, threshold, ruleset, warnings, per-radial values, and a versioned SHA-256 input fingerprint to the transient overlay. The fingerprint covers the model/table hash, ruleset/source, site coordinates, raw or effective RF inputs, and solver step/bound as applicable.

An assigned calculation-ready HRP can therefore produce noncircular reference geometry. Only the explicit missing-pattern fallback applies the same nominal ERP to every radial and tends toward a circular result because the same AGL height proxy is also used everywhere. Neither path is a model of a licensed regulatory contour. A strict result requires radial terrain-derived HNMT, authoritative pattern/source evidence, and the exact approved fallback policy.

The threshold solver evaluates the 78 packaged distances, retains the outermost above-to-below bracket, and performs 48 logarithmic-midpoint bisection iterations. A field still above threshold at 1,000 km becomes an open `INCOMPLETE` model-boundary result; a field already below threshold at 1 km becomes `NoData`. No distance extrapolation is used.

The planner does not decode the downloaded Copernicus DSM, WorldCover data, building GeoJSON, or the bundled IBGE package. Those datasets do not change a contour result in this slice.

## 4. Packaged P.1546 reference data

The planner consumes a bounded P.1546-6 land-path reference table through `P1546LandReference`. The packaged data is a modified subset derived from [javaP1546 commit `4d570c2de2d9cb8b27d36b5aefab03c229b5de9d`](https://github.com/eeveetza/javaP1546/commit/4d570c2de2d9cb8b27d36b5aefab03c229b5de9d):

| Property | Packaged contract |
|---|---|
| Path type | Land only |
| Location percentage | 50% base curves |
| Time percentages | 10% and 50% |
| Nominal frequencies | 100, 600, and 2,000 MHz |
| Nominal effective heights | 10, 20, 37.5, 75, 150, 300, 600, and 1,200 m |
| Nominal distances | 78 P.1546 distances from 1 to 1,000 km |
| Stored values | 3,744 signed 16-bit values, quantized to 0.01 dB |
| Decoded payload | 7,488 bytes |
| Upstream `P1546.java` SHA-256 | `7ecf708a2d693fbde7a5651184820dbd35f0e7cffa6bbae53d64ef7234128925` |
| Packaged table SHA-256 | `47db8b26cb88efab38d872622a8a08450728dce2b335b365b170b247a999992b` |

Distance is logarithmically interpolated within the 1–1,000 km table boundary. Frequency is accepted from 30 to 3,000 MHz and height from 10 to 3,000 m under the packaged logarithmic interpolation/extrapolation policy around the nominal axes. The field value is capped by the P.1546 maximum land field and adjusted by `10 log10(ERP_kW)`. Mixed/sea paths, clutter correction, terrain-clearance correction, and other probability dimensions are excluded.

The payload is decoded lazily and its byte count and full packaged SHA-256 are checked before use. The [modified-source notice](../third_party/javaP1546/NOTICE.md) and [upstream license](../third_party/javaP1546/LICENSE) are retained in the repository. The UI includes the packaged-table hash prefix in its model identity and identifies the output as a land-table reference rather than a complete terrain-aware P.1546 implementation.

Packaging the values inside the APK removes a runtime download and keeps this reference plot offline; it does not establish independent numerical parity or regulatory fitness. A public strict-result claim still requires an independently reproducible table-generation process, independent golden values, edge-domain tests, complete notice/SBOM review, and a cross-platform comparison against the approved desktop implementation.

## 5. Map behavior

`EngineeringMapScreen` accepts a list of `ServiceContourOverlay` values and only renders supplied results; it does not recalculate them. The existing offline Web Mercator coordinate grid remains the display surface:

- protected contours use a teal solid stroke and a faint fill only when `COMPLETE`;
- legacy FM/TV `E(50,10)` interfering envelopes use a red dash-dot stroke with no fill;
- other statistical screening geometry uses an amber dashed stroke with no fill;
- `INCOMPLETE` geometry is never filled or closed for display; the renderer defensively removes a duplicated terminal point equal to the first point;
- `NoData` has no geometry and is never drawn;
- **Fit** includes complete and incomplete contour points as well as sites;
- sites and active-sector azimuths render above contour geometry;
- the compact legend shows styling and complete/incomplete/`NoData` counts, while its collapsed-by-default details expose service, purpose, statistical basis, threshold or `Threshold NoData`, status, model, ruleset, and every warning;
- accessibility semantics summarize protected, legacy interfering, screening, complete, incomplete, and `NoData` counts.

This remains a coordinate-grid overlay, not a basemap or general GIS renderer. The transient contour plan is not a persisted `RegulatoryStudyRecord` or immutable filing snapshot. A separate bounded SAF action can export the supplied overlays as a deterministic KMZ containing `doc.kml` and an evidence `manifest.json`; complete protected geometry becomes a polygon, statistical screening and incomplete geometry remain lines, and `NoData` is omitted from KML but retained with an explicit manifest reason. The destination is reopened and its exact bytes/SHA-256 are verified. That KMZ neither recalculates nor approves the result and is not an antenna source, executable study, or regulatory filing package.

## 6. Current regulatory interference contract and remaining work

Current Anatel interference compliance is not an `E(50,10)` polygon. Acts 8104 and 9751 require point-to-point ITU-R P.526 associated with Assis and service/channel-relation D/U evaluation on the wanted protected contour. Android now delivers a bounded current-rule engine for both FM/FM and digital-TV/digital-TV pairs. Every non-colocated candidate is evaluated in both directions: Basic Plan station into the project protected contour, then the project station into the class-aware Basic Plan protected contour. A missing direction is `NoData`, never a pass.

Basic Plan discovery queries the complete nationwide service/channel partitions for the project channel and first adjacencies. The licensed baseline separately uses a verified current MCom snapshot and a 500 km exact-distance query around the independent site. There is no nearest-48 calculation cap: every calculation-ready bounded candidate is evaluated. Raw catalog antenna text is not treated as a normalized radial pattern. The engine applies the current Acts' ideal-reference fallback using no less than the class maximum or disclosed source ERP, a minimum 40 m transmit height on P.526 paths, and the class reference HNMT for the external protected contour. An unresolvable fallback, a discovery-only coordinate, or a same-channel licensed row with no usable coordinate blocks the engineering result.

For adjacent digital-TV stations separated by at most 5 km, the engine applies the desired/interfering ERP ratio in both directions rather than a boundary field ratio. The selected official IBGE municipality resolves the same-municipality colocation check. Analog TV/RTV is excluded by product decision. Digital TV remains limited to channels 7–51. The spectrum formerly used by television channels 5 and 6 is accepted only through consistent extended-FM channel/frequency pairs 141–197 (76.1–87.3 MHz), never as television or an inferred cross-service case. Receiver-pattern exceptions and other-service compatibility remain outside this result scope and must not be inferred from it.

For the same-technology relationships currently in scope, the pinned acts specify these minimum desired-to-undesired ratios:

| Wanted service | Channel relationship | Minimum D/U |
|---|---|---:|
| FM | Cochannel | +30 dB |
| FM | First adjacent, ±200 kHz | +6 dB |
| Digital TV | Cochannel | +19 dB |
| Digital TV | First adjacent, channel ±1 | −36 dB |

These are comparison margins at locations on the wanted protected contour, not thresholds for standalone interfering polygons. Other technology pairings, channel relations, and regulatory footnotes must be modeled explicitly before the workflow can claim completeness beyond its declared same-service scope.

Strict protected or interference results additionally require:

- decoded, provenance-pinned terrain and per-radial HNMT using the mandated terrain interval and sampling policy;
- approved horizontal antenna-pattern source, coordinate convention, calculation-ready cut, and auditable radial ERP, including approved fallback behavior;
- explicit licensed station/service subtype, class, channel-plan, authorized ERP, and Basic Plan provenance instead of relying only on the generic project network and nominal RF chain;
- an explicit TV generation/channel model instead of inference from the generic `TV_BROADCAST` enum;
- approved P.526 edition and Assis algorithm variant, reference vectors, intermediate terms, and D/U rules;
- independently sourced P.1546 numerical fixtures and declared tolerances;
- model/dataset licensing and a reproducible packaged-table generation process;
- antimeridian-safe polygon handling, geodesic area, resource limits, cancellation, and device benchmarks;
- immutable persistence of the complete input/engine/table fingerprint, a portable executable-study schema, and independent external verification beyond the delivered bounded visualization/evidence KMZ;
- legal and professional review plus an inconclusive-result policy.

Standalone historical contour overlays remain planning references with `regulatory = false`. The FM/digital-TV D/U workflow reports pass/fail only for complete evaluated boundary points or the explicit colocated ERP comparison, and reports `NoData` or not-filing-ready when a required gate is missing; no legacy E(50,10) curve can satisfy those gates. The delivered 50% FM and 70% digital-TV gate rasterizes all official urban-sector area in the selected municipality and evaluates cells inside the protected contour with P.526-15 Deygout-Assis over on-demand ANADEM DTM ranges. The delivered licensed comparison separates unchanged existing conflicts from new or aggravated individual-signal D/U margins. Independent numerical parity and qualified professional/legal/source-license review remain external filing gates.

## 7. Automated evidence

Focused JVM cases cover current FM and digital-TV D/U constants, bidirectional protected-boundary paths, the colocated digital-TV ERP method, revoked FM/TV E(50,10) threshold derivation, rule/band selection, table hashes and reference values, class-distance fallback, the DTV transform, protected/legacy/unsupported states, 72-radial evidence, directional ERP, `NoData`, model boundaries, geodesy, deterministic fingerprints, and distinct KMZ styling/provenance.

The targeted 10-case `EngineeringMapScreenTest` suite and the full 99-case Android aggregate passed on the Android 16/API 36 `Medium_Phone_API_36.1` emulator with no failures or skips. The contour case exercises a 360 × 560 dp host at font scale 1.30 and verifies protected/legacy/screening labels, geometry-state counts, collapsed details for information density, expanded provenance, and `NoData`. These cases are not independent P.1546 regulatory parity, a general accessibility/device matrix, map performance evidence, or an end-to-end filing workflow.

Separate pure JVM KMZ cases cover byte determinism across overlay order, fixed stored entries/timestamps, XML escaping and coordinate order, protected/screening geometry classification, complete radial and warning evidence, explicit `NoData` omission, exact write summary/hash, and fail-closed output/identity/text bounds. They do not establish conformance across external KMZ readers or convert the package into regulatory evidence.

## 8. Maintenance rules

The implementation and tests must continue to prove:

- exact FM and digital-TV profile and threshold selection;
- the digital-TV transform from separate `E(50,50)` and `E(50,10)` values;
- 72 radials at 5-degree intervals and a closed ring only for complete geometry;
- boresight-relative periodic HRP interpolation and `ERP_peak × (E/Emax)^2` applied exactly once;
- the explicit nominal fallback only when a calculation-ready assigned cut is unavailable, and radial `NoData` rather than fallback for zero or nonfinite assigned field;
- deterministic threshold crossing and stable input fingerprints;
- fingerprint changes for assigned-pattern identity, artifact/source/canonical hashes, or calculation-ready horizontal-cut content;
- `NoData` for unsupported bands, invalid/out-of-range inputs, absent crossings, and `E(80,80)`;
- renderer distinctions among protected, legacy interfering, screening, incomplete, and `NoData` states;
- deterministic KMZ classification, complete manifest evidence, bounded output, explicit `NoData` omission, and byte/hash read-back without RF recalculation;
- compact layout and accessibility at the tested phone density and font scale.

When the engine, tables, rules, or assumptions change, update this document, the in-app provenance/warnings, numerical fixtures, and roadmap status in the same change. Never convert `NoData` to zero, extrapolate outside the declared model domain, silently change interpolation policy, or relabel a reference overlay as a regulatory result.
