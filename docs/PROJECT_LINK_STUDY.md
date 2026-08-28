# Project-linked P.525 study

> Delivery state: bounded CPU-only Android slice, August 27, 2026.

ATX Plan Android can calculate and durably save a point-to-point scalar link study from a stored transmitter sector and a compatible stored receiver. The saved record is an immutable snapshot of the selected project sources, effective RF inputs, endpoint geometry, calculation result, provenance, and limitations. This feature is not a terrain-aware link analysis and does not establish RadioPlanner parity.

## Delivered workflow

1. Select a stored site/sector pair in the active project through the searchable, lazy endpoint selector.
2. Select a receiver whose primary network or compatibility profile matches the sector network.
3. Review the effective endpoint and RF-chain summary.
4. Calculate and save the study in one latest-durable catalog transaction.
5. Reopen the latest result, expand its complete persisted details, or inspect older results through the lazy saved-history list.

The transaction compares the complete project snapshot reviewed by the UI with the latest durable aggregate. A changed or deleted project, endpoint, network, or RF value rejects the request instead of calculating from stale data. UI state is published only after the current project-schema-6 document and store-schema-1 atomic index commit succeed.

The independent manual calculator remains available. Its result is held in memory and is not added to the project.

A compatibility profile can make the receiver eligible without changing its RF values. Only non-null profile values replace nominal receiver gain, system loss, or sensitivity. Receiver noise figure remains the stored receiver value and channel bandwidth remains the selected network value; the record separately preserves profile presence, whether any of those three overrides were applied, and all effective values.

## Endpoint geometry

The implementation deliberately follows the current desktop scalar baseline rather than claiming an ellipsoidal or terrain path:

- geodesy ID: `mean-earth-great-circle-v1`;
- mean-Earth radius: `6,371,008.8 m`;
- horizontal distance: `d_h = R * acos(clamp(u_tx dot u_rx, -1, 1))`, using endpoint unit vectors;
- initial bearing: the great-circle forward bearing, clockwise from true north;
- relative azimuth: initial bearing minus stored sector azimuth, normalized to `[0, 360)`;
- height difference: receiver antenna AGL minus transmitter antenna AGL;
- inclined distance: `d = hypot(d_h, height difference)`;
- elevation angle: `atan2(height difference, d_h)`.

Coincident endpoints and antipodal endpoints with an ambiguous initial bearing fail closed. The antimeridian uses the shortest longitude delta.

A stored transmitter-site ground elevation may be present and is snapshotted, but this engine does not evaluate it. Receiver ground elevation and a DEM-backed terrain profile remain unavailable. Both endpoint antenna heights are AGL values over a flat reference, and terrain state is always explicit `NoData`. The inclined distance must not be interpreted as a terrain profile, Earth-curvature-clearance result, or effective-Earth propagation path.

## RF calculation

The scalar engine records `Recommendation ITU-R P.525-5 (11/2024)` and the official reference URL in every result. Its free-space loss baseline is:

`FSPL(dB) = 32.447783 + 20 log10(f_MHz) + 20 log10(d_km)`

The saved result also contains EIRP, received power, receiver sensitivity margin, thermal noise floor, SNR, and first Fresnel-zone radius at the path midpoint. Thermal noise uses the explicit nominal-290 K approximation `-174 dBm/Hz + 10 log10(B_Hz) + NF_dB`. The midpoint Fresnel scalar uses `c = 299,792,458 m/s`. It is a radius only, not a path-clearance result. The effective input snapshot records transmitter power/gain/loss, receiver gain/loss/sensitivity/noise figure, channel bandwidth, frequency, distance, and exactly zero additional path loss.

Official reference: [Recommendation ITU-R P.525-5](https://www.itu.int/rec/R-REC-P.525-5-202411-I/en).

## Immutable record and storage

Project schema 5 introduced bounded `linkStudies` records, and current schema 6 retains them. Each record requires a matching completed point-to-point study summary and contains:

- project, network, transmitter, and receiver source IDs and names;
- endpoint coordinates, AGL heights, sector azimuth/tilt, active states, pattern-reference state, and network downlink frequency;
- all effective link-budget inputs;
- derived geometry and engine/geodesy identities;
- the complete recomputable RF result and model provenance;
- deterministic warnings and explicit terrain `NoData`;
- a lowercase SHA-256 fingerprint over a length-prefixed canonical input-and-geometry representation.

Strict deserialization recalculates the fingerprint, every derived geometry term, the full RF result, and the warning set. Changed or internally inconsistent records fail validation. The fingerprint is an integrity and reproducibility aid, not a digital signature or proof of external authenticity.

Project duplication copies existing immutable study records unchanged. Their snapshotted source project ID and name continue to identify the project in which each calculation originally ran; they are intentionally not rebased to the duplicate's new root ID. This historical identity is not a general duplication-lineage marker for the new aggregate.

Existing indexed project-schema-4 stores migrate by reading and validating all referenced project-schema-4 documents, stripping fields that did not exist in that schema, writing every required immutable schema-5 and current schema-6 document in order, and publishing a replacement store-schema-1 index declaring project schema 6 only after the current documents are durable. A document or index write failure leaves the previous store-schema-1 index declaring project schema 4 authoritative. Legacy monolithic project schemas follow their ordered migration chain through project schema 5 and finish at current project schema 6. Schema 5 remains the historical milestone that introduced link studies.

## Explicit exclusions

The delivered record permanently warns that the following are not evaluated:

- DEM-backed or evaluated endpoint ground elevation;
- terrain profile, Earth-curvature clearance, effective-Earth propagation, line of sight, or Fresnel clearance;
- diffraction, clutter, building, vegetation, atmospheric, rain, fading, or variability loss;
- directional antenna-pattern attenuation, despite preserving whether a pattern was referenced;
- Hata, 3GPP, ITM, P.526, P.1546, P.1812, P.528, ray tracing, or any GPU/Sionna path;
- raster coverage, population-by-coverage, regulatory conclusions, export packages, or `.rp3` import.

The offline IBGE attribute index is not used by this calculation. It contains no sector polygons or terrain elevation.

## Verification boundary

Pure JVM tests cover desktop-compatible distance vectors, cardinal bearings, antimeridian behavior, undefined endpoints, receiver-profile overrides and compatibility-only profiles, all scalar terms, strict JSON round trips, record tampering and numerical tolerances, stale source rejection, ID collision, schema migration, and interrupted publication. Five Compose instrumentation cases cover compact 360 x 480 dp operation at font scale 1.30, complete saved details, lazy chronological history, collision-safe structured sector identity, and the incompatible-receiver state. They are included in the latest green API 36 and JVM aggregate runs. A bounded manual force-stop/relaunch also reopened the same saved endpoint, scalar terms, provenance, and fingerprint. This is not Android Backup, physical-device, or broad process-death/device-matrix evidence.

Desktop behavior was mapped from:

- `src/atx_plan/application/link_budget.py`;
- `src/atx_plan/presentation/link_study_widget.py`;
- `src/atx_plan/application/terrain.py`;
- `tests/unit/test_link_budget.py`.

Those references define comparison inputs and behavior only; desktop code and proprietary RadioPlanner material are not included in this repository.
