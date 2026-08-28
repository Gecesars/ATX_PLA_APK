# Antenna Pattern Composer and File Interchange

> Evidence baseline: August 28, 2026. ATX Plan Android delivers a bounded,
> CPU-only antenna-pattern workflow. Imported or synthesized patterns are
> planning inputs, not measured truth, a full-wave solution, or a regulatory
> approval.

## 1. Delivery boundary

The project-scoped **Antenna Pattern Lab** currently supports:

- a canonical complex-field HRP/VRP model with deterministic interpolation;
- bounded import detection and decoding for explicit PRN, ADT HRP/VRP,
  V-Soft HRP/VRP, Progira/EDX PAT, Android canonical ATX Antenna JSON v2
  with v1 read compatibility, ATX Planner desktop JSON v1, and generic
  CSV/TXT-compatible HRP or VRP numeric tables with exactly one explicit plane;
- a review-before-commit Storage Access Framework (SAF) import flow;
- deterministic pairing of exactly one independent HRP and one independent
  VRP, preserving both original files and a hash manifest in one source bundle;
- immutable storage of the exact imported source, or the deterministic paired
  source bundle, plus a canonical normalized artifact, with SHA-256 identity;
- a compact project library with a horizontal polar plot;
- deterministic CPU synthesis of rectangular arrays with uniform or binomial
  taper, explicit scan phasing, nested-grid directivity convergence, and local
  refinement from as many as 12 spatially separated grid-local peak seeds;
- assignment of one calculation-ready transmit pattern to a project sector;
- SAF export to PRN, ADT HRP, ADT VRP, V-Soft HRP, V-Soft VRP,
  Progira/EDX PAT, Android canonical ATX Antenna JSON v2, or ATX Planner
  desktop JSON v1;
- directional radial ERP in the bounded Brazil FM/DTV reference-contour
  planner.

A separate GIS/export component can package already calculated service
contours as KMZ. It does not decode KML or KMZ into an antenna pattern and does
not participate in pattern synthesis.

The antenna workflow explicitly rejects KML/KMZ as antenna-pattern input and
does not export its bounded generic CSV/TXT dialect because no unique source
dialect exists to reproduce. It also does not deliver an arbitrary or
ambiguous table importer, a full three-dimensional measured pattern, mutual
coupling, a full-wave electromagnetic solver, terrain-aware propagation, or a
regulatory result. The project-linked P.525 study still records
pattern-reference state but does not apply directional loss. Only the Brazil
broadcast-contour reference consumes the assigned horizontal cut in this
slice.

## 2. Canonical field and angle contract

`PatternSample.normalizedFieldAmplitude` is linear field amplitude
`A = E/Emax`, not power and not gain. It must be finite and in `[0, 1]`.
Each canonical cut has a unit peak within the declared tolerance. Optional
phase is in degrees. A sample becomes the complex field

```text
F = A * (cos(phi) + j sin(phi))
```

The general cut evaluator uses `phi = 0` when phase is absent so magnitude-only
patterns remain usable by non-coherent consumers. That compatibility convention
is not evidence of source phase. Coherent array synthesis therefore requires an
explicit phase for every non-zero source field sample. Phase at an exact field
null is irrelevant; any other phase `NoData` returns explicit `NoData` before
field summation, directivity integration, or output-cut construction.

The canonical physical frame is `APERTURE_XY_BORESIGHT_Z`:

- boresight is `+Z`;
- the horizontal aperture axis is `+X`;
- positive elevation is toward `+Y`;
- a direction at horizontal angle `theta` and elevation `epsilon` is

```text
u(theta, epsilon) =
  (cos(epsilon) sin(theta), sin(epsilon), cos(epsilon) cos(theta))
```

Angles in that equation are evaluated in radians after conversion from the
public degree values. Thus HRP is the XZ plane and VRP at horizontal angle
zero is the YZ plane. Conversion helpers retain the historical physical-angle
mapping:

```text
physical horizontal = wrap360(geographic azimuth - 90 degrees)
geographic azimuth   = wrap360(physical horizontal + 90 degrees)
```

Project calculation records use the more direct
`RELATIVE_AZIMUTH_CLOCKWISE_ELEVATION_UP` convention: stored HRP angle zero is
the sector boresight, positive HRP angles are clockwise offsets, and positive
VRP angles are above the local horizon. Import conversion must make the source
frame explicit before a cut enters that project contract.

Canonical HRP angles are strictly increasing in `[0, 360)` and interpolate
cyclically across north. Canonical VRP angles are strictly increasing in
`[-90, +90]` and evaluation outside the available source interval clamps to
the nearest endpoint. Both cuts interpolate the real and imaginary complex
components, not magnitude and wrapped phase independently:

```text
F(t) = (1 - t) * F0 + t * F1,  0 <= t <= 1
```

The separable pattern evaluation is

```text
F(theta, epsilon) = H(theta) * V(epsilon)
```

and horizontal attenuation relative to peak is

```text
correction_dB = 20 log10(|H(theta)|)
```

with a configurable finite floor, `-120 dB` by default. This separable
construction is a bounded two-cut approximation. Independently normalized
HRP/VRP cuts do not reconstruct the full three-dimensional or absolute field,
and must not be presented as if they did.

## 3. CPU coherent-array composer

For frequency `f`, wavelength `lambda = c/f`, wave number
`k = 2*pi/lambda`, element position `r_i`, normalized power fraction `p_i`,
feed phase `phi_i`, and observation direction `u`, the engine evaluates

```text
F_array(u) = sum_i [
  sqrt(p_i) * F_i(R_i^-1 u) * exp(j * (phi_i + k * dot(r_i, u)))
]
```

`c = 299,792,458 m/s`. `R_i^-1 u` denotes evaluation in the element's local
orientation. Power fractions are converted to field amplitude exactly once by
the square root. The composer does not silently normalize input power before
summation; the current rectangular UI constructs normalized fractions from
its selected taper.

Every powered element must provide explicitly available HRP and VRP cuts and
known phase at every non-zero sample. A magnitude-only source is not promoted
to a phase-bearing synthesized artifact: composition returns `NoData` with a
structured `SOURCE_PHASE_NO_DATA` warning identifying the first affected
element, plane, and angle. The direct coherent-field evaluator enforces the
same precondition. Inactive and zero-power elements do not participate in this
check.

The UI creates a centered rectangular aperture. For column/row positions in
wavelengths, steering direction `u_s`, and coordinates `x_i`, `y_i`, it uses

```text
feedPhase_i_degrees = -360 * (x_i * u_s.x + y_i * u_s.y)
```

Uniform taper uses unit amplitude. Binomial taper multiplies the horizontal
and vertical binomial coefficients, then converts each combined amplitude
weight `w_i` to

```text
p_i = w_i^2 / sum_j(w_j^2)
```

The delivered editor accepts 1-32 rows and columns, at most 512 elements,
spacing from `0.05` through `5.0` wavelengths on each aperture axis, and
horizontal/vertical scan entries from `-60` through `+60` degrees. The domain
engine independently enforces at most 512 elements and a frequency range of
`1 kHz` through `1 THz`.

Synthesis produces a 360-sample HRP at one-degree steps and a 181-sample VRP
from `-90` through `+90` degrees. Each output cut is independently normalized
to its own peak while retaining calculated phase only after the source-phase
precondition succeeds. Complete cancellation in either canonical plane is
explicit `NoData`.

Directivity uses a bounded spherical numerical integration. With radiation
power proportional to `|F|^2`, the engine computes

```text
D = 4*pi*U_max / integral[-90..90, 0..360](U(theta,epsilon)
    * cos(epsilon) dtheta depsilon)
directivity_dBi = 10 log10(D)
gain_dBi        = 10 log10(D * efficiency)
gain_dBd        = gain_dBi - 2.15
```

The default nested grids use `2`, `1`, `0.5`, and `0.25` degree steps. Each
step must tile both 180 and 360 degrees, and the initial-to-minimum ratio must
be a power of two. At each level, up to 12 deterministic grid-local maxima,
separated on the sphere by at least two grid steps, seed local refinement to
an angular step no larger than `1e-4` degrees. Pole handling collapses the
equivalent azimuth samples at each pole rather than treating them as distinct
peaks. An available result requires both refined peak power and directivity to
change by no more than the default relative tolerance `5e-4` between
successive levels, after reaching the grid resolution required by the array
aperture and the source pattern.

The resolution preflight limits inter-element spatial phase advance to
`pi/4` per grid step and source-cut complex-field change to `0.5` per step.
The nested cache is limited to 1,100,000 spherical points, and the complete
cut, cache, peak-refinement, and integration work is limited to 20,000,000
element-field evaluations. Resolution below `0.25` degrees, cache or CPU
budget exhaustion, peak-refinement failure, and failure to converge within the
configured levels return explicit `Unsupported`; invalid or zero integrated
power returns `NoData`. After convergence, the composer compares the sampled
canonical HRP peak with the refined 3D peak. If the HRP peak is more than
`0.1 dB` below that 3D peak, composition is `Unsupported` and scalar gain is
not stored because attaching it to the independently normalized HRP would
misrepresent the absolute field. Every available composition also retains an
explicit provenance limitation that its separable cuts do not reconstruct the
full 3D or absolute field. Warnings identify spacing above `0.5 lambda`,
unmodeled coupling risk below `0.45 lambda`, scan beyond 45 degrees, non-unit
input power totals, and source-pattern frequency mismatch greater than five
percent.

## 4. File-format matrix

All formats enter the same normalized field-amplitude model. A recognized
extension alone never overrides structurally inconsistent content.

| Format | Detection and input convention | Metadata retained | Export and loss boundary |
|---|---|---|---|
| PRN | Requires one or two explicit `HORIZONTAL count` / `VERTICAL count` sections; duplicate planes fail, and HORIZONTAL must precede VERTICAL when both are present. Samples have two columns (`angle value`) or three (`angle value phase_degrees`), and supplied phase is retained. `VALUE_CONVENTION` can explicitly declare positive field attenuation, normalized linear `E/Emax`, or relative field dB. Native attenuation markers also identify attenuation. Without a declaration, any cut wholly confined to `[0,1]` is semantically ambiguous even when it has the same `NAME`/`FREQUENCY`/`GAIN`/three-column grammar as the desktop exporter, so automatic decoding fails closed. The Android review flow retains the already bounded bytes and requires an explicit token-bound choice between desktop positive attenuation and normalized linear `E/Emax`; that choice is retained as a warning/limitation. For a pair, the single disclosed choice applies to every ambiguous unmarked PRN, and the user must cancel if the source conventions differ. Otherwise non-negative values above `1` are inferred as attenuation with a warning. Attenuation uses `E/Emax = 10^(-A_dB/20)` after normalizing its minimum to zero, while relative field dB normalizes its peak to zero. | Optional frequency and declared gain. A `dBd` declaration is converted to `dBi` by adding 2.15 dB. | Export requires a complete two-cut pattern and nominal frequency, then writes both cuts at one-degree spacing as three-column angle/positive-attenuation/phase rows. A known gain is emitted in dBi; no gain is invented. Missing canonical phase is exported as zero with a warning. Canonical VRP has no back-hemisphere data, so wrapped angles 91-269 use the explicit 300 dB `NoData` floor, not predicted field. |
| ADT HRP / VRP | Requires the ADT title, frequency, pattern-count, placement header, `voltage`, and two- or three-column samples. Plane resolution uses `pattern_type`, an explicit HRP/VRP suffix, delimited filename/header terms, and only then unequivocal geometry. Ambiguous `.pat` input fails closed. A declared vertical circular source is cropped to `[-90,+90]` with an omitted-sample warning. Voltage is normalized linear `E/Emax`; the third column is phase in degrees. | Nominal frequency. Placement, power, mechanical tilt, and phase-offset header values are reported but not applied to the normalized cut. | One ADT file represents one cut. Export writes phase, substituting zero only where phase is absent and warning about it. The facade adds a clearly warned display placeholder for the missing plane; it cannot be installed, assigned, composed, exported, deduplicated with genuine isotropic data, or used in an engineering calculation. |
| V-Soft HRP / VRP (`.vep`) | HRP requires the normalized `360,0,1` header and at least 360 finite linear field magnitudes; the first 360 map to `0..359` degrees and any trailing numeric values are ignored with a warning. VRP requires the desktop-compatible V-Soft elevation producer header and at least two finite angle/magnitude rows in `[-90,+90]`; one optional bounded `Beam Tilt` declaration is retained. | Optional VRP beam tilt. V-Soft contains one magnitude-only cut and carries neither nominal frequency nor phase. The facade adds the same explicitly marked non-engineering display placeholder for the absent companion plane. | Both cuts export as `.vep` only when both source cuts are genuinely available. HRP writes 360 one-degree magnitudes after `360,0,1`; VRP writes 1,801 values from `-90` through `+90` degrees at 0.1-degree spacing. A known imported beam tilt is preserved without recomputing it; otherwise tilt is derived from the exported VRP maximum and that derivation is warned. Magnitudes use four decimal places. Export drops phase with an explicit warning. |
| Generic HRP / VRP numeric table | Accepts bounded comma-, semicolon-, or whitespace-delimited CSV/TXT-compatible numeric tables only when exactly one plane is explicit in an `.hrp`/`.hup`/`.vrp`/`.vup` suffix, an unambiguous filename token such as azimuth/HPOL or elevation/VPOL, or a Horizontal/Azimuth versus Vertical/Elevation header. Bounded desktop-compatible heuristics select one numeric block and infer unique angle/magnitude columns; an optional phase column must be labeled explicitly. Linear `E/Emax` and relative field dB are distinguished from headers and bounded values; relative dB converts with `E/Emax = 10^((dB - peak_dB)/20)`. HRP accepts bounded signed/wrapped degree input, while supported signed, `0..180`, or wrapped VRP angles resolve into `[-90,+90]`. | Optional phase and nominal frequency from an explicit frequency line or constant frequency column. `Hz`, `kHz`, `MHz`, and `GHz` are supported; a unitless constant frequency uses the documented desktop-compatible MHz assumption with a warning. | Import only. HRP is resampled to 360 one-degree samples and VRP to 1,801 0.1-degree samples. The source represents one cut and receives a disclosed placeholder for the missing plane. Ambiguous/conflicting plane or column candidates fail closed; the codec will not invent a generic export dialect. |
| Progira/EDX PAT | Requires a quoted `By ADT`, gain, version header, an HRP block, exactly one `999` separator, and a declared VRP block. Values are normalized linear `E/Emax`. Source VRP elevation sign is inverted into the canonical positive-up convention. | Declared gain in dBi and the azimuth of the single vertical cut. Neither changes normalized field samples. | Exports 360 HRP samples and 1,801 VRP samples at 0.1-degree spacing. PAT cannot carry phase or a full 3D surface. Export requires both a real gain and a known vertical-cut azimuth; the codec invents neither. Synthesized patterns explicitly carry azimuth zero because their canonical VRP is calculated in that plane. |
| Android canonical ATX Antenna JSON v2, with v1 read compatibility | Strict Android canonical JSON object with explicit format/schema, `Hz`/degree units, normalized amplitude convention, both cuts, optional phase, explicit structured cut availability, and full pattern/cut provenance. New exports write schema v2. Schema v1 remains readable; a v1 cut without an availability field becomes `LEGACY_UNSPECIFIED` and stays review-only. This is distinct from the parent desktop schema below. | Canonical IDs, name, nominal frequency, coordinate frames, engine/source fields, warnings, limitations, embedded source hash, nullable peak gain in dBi, nullable vertical-cut azimuth in degrees, and nullable beam tilt in degrees. Schema v2 preserves those source-format metadata values exactly when available and uses explicit null as `NoData`; schema v1 has no metadata envelope, so peak gain, vertical-cut azimuth, and beam tilt decode as `NoData`. | Deterministic lossless round-trip for the represented Android canonical pattern and source-format metadata. Embedded provenance and metadata remain declarative rather than independently authenticated; the SHA-256 of the imported JSON bytes is recorded separately. Unknown keys, unsupported schema versions, missing v2 metadata or cut-availability fields, invalid enum values, nonfinite numbers, and out-of-range metadata fail closed. Export still requires genuinely available HRP and VRP cuts. |
| ATX Planner desktop JSON v1 | Strict compatibility with the parent desktop schema identified by `format = "atx-antenna-pattern"` and `version = 1`. It accepts one or both uniquely named `horizontal` / `vertical` cuts, their exact desktop angle conventions, positive field `attenuation_db`, and `phase_deg`; unknown keys, duplicate planes, invalid conventions, and invalid bounds fail closed. One-cut input remains reviewable and can participate in explicit HRP/VRP pairing, but is not calculation-ready alone. | Name, nominal frequency in Hz, declared gain in dBi, and declarative embedded source format/SHA-256. Polarization is validated source text but is not retained by the Android canonical cut model; the exact imported byte hash remains the local identity. Desktop's geographic-north HRP label is retained as source metadata, while Android keeps the numeric clockwise samples without rotation and interprets angle zero as sector boresight; this is explicitly warned, not claimed as a verified geodetic transform. | Deterministic desktop-v1 export writes attenuation and phase for both cuts. It requires complete genuinely available HRP and VRP cuts, nominal frequency, and declared gain; none is invented. Missing phase is emitted as zero with a warning, and Android-absent polarization is emitted as `unknown` with a warning. This is a compatibility artifact, not the lossless Android canonical JSON v2 artifact. |

Vendor formats without one of these bounded structures remain unsupported.
Generic CSV/TXT is not a permissive fallback: it requires an explicit single
plane and unambiguous numeric columns, remains import-only, and fails closed on
conflict. Display names ending in `.kml` or `.kmz` are rejected explicitly as
geospatial exchange formats before generic-table interpretation. Every
one-cut PRN, ADT, V-Soft, desktop JSON, or generic-table import remains visibly
limited even after any display-only compatibility placeholder is added. Such a
cut can be reviewed and explicitly paired with its independently supplied
companion plane, but it cannot be committed alone as calculation-ready project
data.

## 5. Untrusted-input and mobile resource limits

The pure codecs and SAF boundary enforce the following current limits:

| Boundary | Current limit or rule |
|---|---|
| Imported or exported payload | 16 MiB maximum; empty input rejected |
| Paired HRP/VRP source | Exactly two files, 15 MiB combined raw bytes, and a 16 MiB deterministic source-bundle maximum |
| Text decoding | Strict UTF-8; optional BOM removed; unsupported control characters rejected |
| Legacy text lines | 20,050 maximum; JSON is bounded by bytes, tokens, nesting, strings, numbers, and sample declarations instead of pretty-print line count |
| Legacy text line | 4,096 characters maximum |
| Samples | 2-10,000 source/canonical samples per cut |
| Generic numeric table | 3-10,000 consistent rows, 2-32 columns, finite values with absolute magnitude at most `1e12`, exactly one explicit HRP/VRP plane, and unique inferred angle/magnitude columns |
| Numeric input | Finite only; `NaN` and infinities rejected |
| HRP domain | At least two unique canonical angles in `[0, 360)` after wrapping |
| VRP domain | At least two unique canonical angles in `[-90, +90]` |
| Duplicate canonical angle | Source values first convert to complex normalized field; duplicate vectors are averaged, the canonical peak is renormalized, and a warning is retained |
| Linear field | Non-negative, nonzero peak, normalized to unit peak with a warning when needed |
| Positive attenuation | Non-negative, minimum normalized to 0 dB before conversion with a warning when needed |
| JSON pre-materialization | Before Kotlin serialization allocates typed objects/lists: nesting depth 32, 400,000 lexical tokens, 4,096 raw characters per string token, 64 characters per numeric token, and 20,000 total canonical `angleDegrees` or desktop `angle_deg` sample declarations; escaped sample keys are counted |
| JSON schema | Strict typed canonical-v1/v2 or desktop-v1 schema, unknown keys rejected, no lenient or special-float decoding |
| Array | 512 elements, 20,000,000 element-field evaluations, 1,100,000 cached spherical points, and `0.25` degree minimum grid step |
| Provenance text | 512 characters per engine-domain text value; bounded warning/limitation collections |

The SHA-256 always covers the exact imported bytes. It detects local identity
and later corruption; it does not authenticate a manufacturer, prove a
measurement, or validate embedded claims. Format detection rejects malformed,
duplicate, or value-ambiguous PRN sections, ambiguous ADT,
conflicting/ambiguous generic tables, and `.kml`/`.kmz` input instead of
guessing. JSON lexical bounds run before typed materialization; strict schema
validation follows. V-Soft headers/counts, generic row/column structure, count
declarations, angular ranges, sample ordering after canonicalization,
normalization, filename/control characters, artifact length, and stored hashes
are validated before commit.

These codecs do not open files, URIs, archives, or network connections. The
SAF/ViewModel layer performs bounded streaming before invoking them.

## 6. SAF review, artifacts, and project schema 6

Single-file import uses Android `OpenDocument`; paired import uses
`OpenMultipleDocuments` and requires exactly two distinct selections. The
application reads each document through the same bounded SAF path, detects and
parses off the UI thread, and accepts a pair only when the files provide one
explicitly available independent HRP and one explicitly available independent
VRP with compatible frequency, gain, and tilt metadata. A complete two-cut
file, duplicate plane, conflicting metadata, or placeholder cut fails closed.
The review step shows the component names, detected format, exact preserved
byte count, source or bundle SHA-256, HRP/VRP sample counts, available
frequency/gain metadata, and every retained warning. Nothing is added to the
project until the user confirms that preview.

Confirmation creates two independently addressable immutable artifacts when a
source file was imported:

1. `IMPORT_SOURCE`: either the exact selected bytes or a deterministic stored
   ZIP containing the exact HRP and VRP files plus their names, formats,
   planes, byte counts, and SHA-256 values in `manifest.json`;
2. `ANTENNA_PATTERN`: deterministic ATX Antenna JSON v2 and its normalized
   content SHA-256.

A synthesized pattern creates the canonical `ANTENNA_PATTERN` artifact but has
no invented import-source artifact. When a stored pattern is selected as an
array element, synthesis reopens and verifies that canonical artifact because
the fixed-grid project projection cannot represent per-sample phase `NoData`
losslessly. It never composes from that lossy phase projection. Artifact
staging, length limits, hash
verification, sync, immutable promotion, and deduplication use the existing
private content-addressed project store.

Normalized-cut identity remains an engineering-integrity check, but duplicate
reuse additionally requires the same verified canonical `ANTENNA_PATTERN`
artifact SHA-256. Therefore two imports with identical normalized fixed-grid
cuts but different canonical artifacts, metadata, or retained provenance stay
as distinct variants instead of silently discarding an interchange source.
The incoming artifacts are staged and verified before the transactional
duplicate decision. Before a duplicate result is accepted, every canonical and
import-source artifact referenced by the retained record must still report
`AVAILABLE`; missing, corrupt, or byte-count-mismatched blobs fail without
clearing the reviewed import.

Project schema 6 adds the calculation-ready antenna fields: source/canonical
artifact references, canonical version, origin, coordinate convention,
fixed-grid HRP/VRP, normalized-content SHA-256, and warnings. Its project copy
contains 360 one-degree HRP amplitudes and 181 one-degree VRP amplitudes so
bounded calculations remain available without reopening a provider URI.
Optional phase arrays align one-for-one with those samples. Schema 5 remains
the historical milestone that introduced immutable project-linked P.525
records; ordered migration now continues from schema 5 to schema 6. A legacy
schema-5 payload cannot inject schema-6 antenna calculation fields: those
fields are removed before migration and receive current defaults.

Normalized-content identity V2 binds the canonical data version, coordinate
convention, nominal frequency, nullable peak gain, structured availability for
both cuts, and fixed-grid complex HRP/VRP samples while excluding names, IDs,
source containers, and provenance.
Project rehydration recalculates that SHA-256 before exposing a pattern to an
engineering consumer. A mismatched or malformed record, including a historical
gain-unbound V1 digest, remains schema-readable but fails closed as calculation
`NoData`; it is not silently re-signed with its stored gain.

Normalized-content hash `V2` and ATX Antenna JSON schema v2 are independent
version domains. The hash version does not change project schema 6 or
`canonicalDataVersion = 1`.

`Sector.transmitAntennaPatternId` assigns one project pattern. Aggregate
validation requires every non-null assignment and source/canonical artifact
reference to resolve inside the same project. Mutation use cases reject stale
sector/pattern snapshots. Deletion is blocked while sectors reference a
pattern. A successful deletion removes the project record and its project
artifact references; physical content-addressed blobs can remain until the
separate reachability garbage collector is designed and verified.

Export uses Android `CreateDocument`. Before opening the system destination
picker, the app reopens the
content-addressed canonical artifact, verifies its project role, byte/hash
identity, supported ATX JSON schema, explicit cut availability, stored source
correlation, and gain-bound normalized-content identity against the project
record. It then encodes from that verified artifact rather than trusting only
the project copy. If the selected format lacks required metadata, preparation
fails before any destination document can be created. A compact review shows
the exact media type, byte count, SHA-256, and an expandable list of every
format-loss warning. The verified payload and bounded metadata are also placed
in a short-lived, app-private, no-backup envelope with an unguessable token,
hash correlation, one-hour expiry, eight-entry/64 MiB aggregate ceilings, and
atomic staging. A recreated process can therefore recover the exact prepared
bytes when the SAF callback returns; verified success, explicit cancellation,
corruption, or expiry removes the entry. After approval, the app writes bounded bytes through the returned URI,
reopens the document, compares the exact bytes, and reports the output SHA-256
only after successful read-back verification. All format-specific warnings, such
as phase substitution, attenuation floors, derived beam tilt, or an
unrepresentable source field, are surfaced in the verified-export notice after
that SAF write/read-back succeeds. This is local integrity evidence, not proof
that another application implements the same vendor convention.

## 7. Directional Brazil broadcast reference contours

The broadcast planner resolves the active sector's assigned schema-6 HRP. For
true bearing `beta` and stored sector azimuth `alpha`, it evaluates

```text
relativeAzimuth = wrap360(beta - alpha)
```

against the one-degree periodic `E/Emax` grid using linear amplitude
interpolation. Peak ERP is derived once from the stored transmit chain:

```text
ERP_peak_kW = 10^((P_tx_dBm + G_peak_dBi - L_feeder_dB - 2.15 - 60) / 10)
ERP_radial_kW = ERP_peak_kW * |H(relativeAzimuth)|^2
```

The stored sector gain is already the peak gain; it is not added again after
the horizontal field ratio. All 72 five-degree radials solve their threshold
distance independently. Pattern ID, origin, coordinate convention,
source/canonical hashes, artifact reference, and a derived horizontal-cut hash
participate in the contour input fingerprint.

If no calculation-ready assigned HRP resolves, the planner applies nominal ERP
to every radial and emits an explicit omnidirectional-fallback warning. If an
assigned cut returns zero or non-finite field on a radial, that radial is
`NoData`; the planner does not replace it with nominal ERP. Complete geometry
is closed. Incomplete geometry remains an open longest contiguous run and is
never filled by the map renderer.

This directional shape remains `regulatory = false`. Sector AGL is still used
as a proxy for radial HNMT; no downloaded terrain is sampled; VRP, electrical
tilt, mounting, polarization, and frequency-dependent pattern changes are not
applied; the bundled P.1546 land subset is still a bounded reference; and the
current P.526 plus Assis/D-U interference workflow is absent. Pattern-aware
radials remove the old circular-only limitation but do not clear any strict
Anatel gate.

## 8. Numerical and physical limitations

The current engine deliberately excludes:

- mutual coupling, active impedance, impedance matching, feed-network loss,
  cable phase tolerance, enclosure/radome effects, mast/tower scattering,
  ground interaction, polarization and cross-polar discrimination;
- a full-wave method of moments, finite-element, FDTD, physical optics, or
  GPU electromagnetic solver;
- arbitrary measured 3D samples or spherical-harmonic reconstruction;
- frequency interpolation between measured cuts;
- external cross-implementation parity and sensitivity evidence for the
  converged directivity engine, extreme array configurations, stored one-degree
  cut resolution, and cross-runtime floating-point behavior;
- manufacturer calibration, uncertainty, measurement conditions, and
  laboratory traceability unless present only as unverified source text;
- mechanical mounting and vertical-pattern use in the current broadcast
  contour calculation;
- regulatory pattern masks, licensed ERP reconciliation, or approved fallback
  policy.

Because the model is separable, `H(theta)*V(epsilon)` cannot represent an
elevation pattern that changes with azimuth. Array synthesis evaluates a 3D
field for its numerical integral, but the stored reusable result remains two
normalized cuts plus scalar gain metadata. Each output cut is normalized
independently, so normalized samples alone cannot recover absolute gain. The
`0.1 dB` HRP-versus-3D peak gate prevents a known incompatible scalar-gain
attachment; it does not turn two cuts into a full 3D or absolute-field model.
Magnitude-only source cuts remain valid for non-coherent pattern consumers but
cannot enter coherent synthesis unless every non-zero source sample has phase.
PAT and V-Soft magnitude-only exports lose phase. PRN and desktop JSON preserve
available phase, but must substitute zero for absent canonical phase and warn
about that substitution. Every such loss or assumption must stay visible in
preview, stored provenance, and export behavior.

No antenna result in this slice is a regulatory filing result. Use `NoData` or
`Unsupported` when required inputs or a model are absent; never replace them
with an isotropic assumption without the explicit ADT compatibility warning or
the explicit broadcast-contour fallback warning described above.

## 9. KMZ is a separate GIS/export concern

KMZ is a ZIP container for KML geospatial presentation. It is not an HRP/VRP
antenna pattern format. The delivered `ServiceContourKmzExporter` packages only
already calculated `ServiceContourOverlay` values; it does not calculate,
approve, import, or reinterpret RF or antenna data.

The Engineering Map exposes this exporter through SAF `CreateDocument`, then
reopens the destination, compares the exact bytes, and verifies the SHA-256.
The deterministic archive contains exactly `doc.kml` and `manifest.json` as
uncompressed entries with a fixed ZIP timestamp. It writes EPSG:4326
longitude/latitude/altitude coordinates, uses a polygon only for a complete
protected contour, uses a line for statistical screening or incomplete
geometry, and omits `NoData` geometry from KML while retaining its evidence and
omission reason in the manifest. The manifest preserves each overlay's
classification, ruleset/source/model, regulatory flag, fingerprint, warnings,
and radial evidence.

The current hard bounds are 16 MiB output, 256 overlays, 360 radials per
overlay, 20,000 total radials, 4,096 points per overlay, 100,000 total points,
and bounded metadata/warning collections. Duplicate overlay IDs, duplicate
radial azimuths, invalid XML 1.0 text, oversized output, and malformed complete
geometry fail closed.

This is a deterministic visualization/export artifact, not an immutable
regulatory filing package. It has no general KMZ importer, external-resource
resolver, raster ground-overlay support, project-pattern artifact attachment,
or external-reader conformance evidence. A colored image, legend, or contour
polygon must never be reverse-engineered into continuous `E/Emax` samples.
Selections named `.kml` or `.kmz` are explicitly rejected by the antenna codec
before generic-table interpretation.

## 10. Delivered Anatel Basic Plan on-demand catalog boundary

Android now delivers a bounded, user-triggered catalog for the official Anatel
TV/FM `Canais.zip` structure. The production UI disables acquisition until the
user acknowledges review of the official source and attribution. That
acknowledgement is not license approval: the direct-source descriptor remains
`REVIEW_REQUIRED`. No official archive is bundled, and the delivered action is
one complete foreground HTTPS acquisition followed by a local index build. It
is not silent synchronization, a background transfer, or a regulatory import.

Official discovery points reviewed for this plan are:

- [Anatel Basic Channel Distribution Plans](https://www.gov.br/anatel/pt-br/regulado/radiodifusao/planos-basicos-de-distribuicao-de-canais),
  which directs public consultation/download to Mosaico without prior
  registration;
- [Mosaico public broadcast-channel search](https://sistemas.anatel.gov.br/se/public/view/b/srd.php),
  which currently exposes filtered channel data and CSV/XLSX download;
- [Radiodifusao - Plano Basico on dados.gov.br](https://dados.gov.br/dados/conjuntos-dados/radiodifusao---plano-basico),
  the official machine-readable catalog entry;
- [Anatel announcement of the open dataset](https://www.gov.br/anatel/pt-br/assuntos/noticias/anatel-divulga-nova-base-de-dados-no-portal-brasileiro-de-dados-abertos-3),
  which identifies `PBTVD.csv`, `PBFM.csv`, `PBOM.csv`, `PBOT.csv`, and
  protected-contour data in the archive;
- [Anatel Open Data policy](https://www.gov.br/anatel/pt-br/dados/dados-abertos).

At the evidence date, the dados.gov.br catalog labels the dataset version as
`Vigente`, update cadence as daily, organization as Anatel, responsible unit as
`Gerencia de Espectro, Orbita e Radiodifusao`, and license as **Creative
Commons Attribution**. The catalog does not expose a license version in that
label, so Android must not invent one. The delivered direct-source descriptor
therefore keeps its license status at `REVIEW_REQUIRED` and uses an explicit
Anatel attribution; it does not infer redistribution permission from public
access or silently equate a website-footer license with the dataset resource
license. Before public distribution or each new accepted resource generation,
the app must retain the exact catalog license label, license URL or text when
supplied, attribution requirement, catalog metadata snapshot, and source URL.

### Delivered acquisition, parsing, index, and query boundary

`OfficialAnatelBasicPlanSource` pins the public Mosaico landing page and the
HTTPS [`Canais.zip` endpoint](https://sistemas.anatel.gov.br/se/public/file/b/srd/Canais.zip),
with `sistemas.anatel.gov.br` as the only current host. The production UI—not
the storage interface itself—provides the explicit source/attribution review
gate. `AndroidAnatelBasicPlanCatalog.refresh()` performs a complete GET through
the allowlisted HTTPS transport only when called. It does not resolve current
dados.gov.br metadata, negotiate a service subset, or send ETag/Last-Modified
conditional requests. The requested/effective URL, acquisition time, byte
count, SHA-256, and any returned ETag or Last-Modified values are retained as
provenance.

`ImmutableAnatelRawArchiveStore` streams at most 64 MiB into a private synced
`.part` file, checks a supplied HTTP Content-Length, hashes the complete bytes,
and promotes them to an immutable `canais-<sha256>.zip` name. Bounded atomic
JSON preserves the source and license-review descriptor with the acquisition
evidence. Identical verified bytes reuse the existing raw artifact; a new raw
artifact remains evidence even if parsing or indexing subsequently fails. The
store accepts at most eight raw generations and 512 MiB total. It performs no
eviction, so a distinct refresh fails visibly when that retention ceiling is
full.

`AnatelBasicPlanArchiveParser` requires the three exact official entry names
`plano_basicoTVFM.xml`, `secudariosTVFM.xml`, and `solicitacoesTVFM.xml` and
maps them to Basic Plan, secondary-channel, and request origins. Unexpected
entries are bounded, drained, counted, and warned; a required missing entry is
a format failure.

The parser verifies the stored archive byte count and SHA-256 over the complete
ZIP and attaches archive, entry, generation date, and source-row provenance to
every record. It preserves unassigned raw status and regulatory text instead of
inventing meanings. Frequency comes from a valid source attribute, a bounded
service/channel mapping, or explicit `NoData`, with the origin recorded.

The default security envelope is:

- 64 MiB compressed archive, 32 ZIP entries, 128 MiB per expanded entry, and
  256 MiB total expanded data;
- maximum verified compression ratio 100:1, 1,000,000 source rows, 128 XML
  attributes per element, 16,384 characters per attribute, 64 KiB aggregate
  row attributes, and 256-character ZIP entry names;
- strict UTF-8, traversal/absolute/backslash/duplicate-path rejection, DTD and
  external-entity denial, bounded warning categories, finite numeric values,
  and required-entry/count/hash verification.

Records are emitted while the one-pass digest is still being completed.
`AnatelBasicPlanSqliteIndexStore` therefore inserts them in 1,000-row batches
inside a disposable database transaction and commits only after the final
required-entry, count, byte-count, and hash report succeeds. It writes the
parser report and warnings, creates reducing-filter indexes, sets schema
version 1, closes and syncs the staged database, validates it, then atomically
renames it to an immutable archive-hash/schema-derived filename. Each SQLite
file is bounded to 65,536 4,096-byte pages (256 MiB); the index family is also
bounded to eight files and 768 MiB, with no automatic eviction.

`AtomicAnatelCurrentPointerStore` is the sole visibility switch. It publishes a
bounded schema-v1 `AtomicFile` pointer only after raw and index validation.
HTTP, parsing, index, or pointer failure leaves the previous current generation
authoritative, while a newly verified immutable raw artifact may remain. Status
and queries open the selected index read-only and return explicit `NoData` for
missing, invalid, unavailable, or incompatible catalog state.

Domain availability uses `READY` or `NO_DATA`. The route-scoped ViewModel maps
that contract to `CHECKING`, `NOT_ACQUIRED`, `READY`, `REFRESHING`, or `FAILED`;
a valid zero-match query remains a `READY` empty page. A failed refresh returns
to `READY` with an error when a verified prior generation is still available.

The core requires an FM or television service and supports reducing filters for
two-letter state, exact municipality name or exact IBGE municipality code,
channel, inclusive frequency range, accent-insensitive descriptive substring,
and exact Basic Plan ID. Pages default to 50 records, are capped at 200, and
have a bounded offset. The current compact UI exposes service, state, channel,
text, and replacing 25-row previous/next pages. Municipality, frequency-range,
and exact Basic Plan ID filters remain core-only. All installed queries are
offline.

Refresh runs in the route-scoped ViewModel on an IO dispatcher and exposes only
an indeterminate `REFRESHING` state. Leaving the Data route clears the ViewModel
and cancels later UI publication, but the blocking catalog call receives no
cooperative cancellation signal and may continue until it returns; the UI tells
the user to keep that screen open. There is no byte progress, checkpoint/resume
contract, WorkManager or UIDT path, process-death/reboot survival, conditional
GET, automatic update, or catalog-metadata/license resolver.

### Remaining catalog lifecycle and integration work

1. Resolve and retain current dados.gov.br resource metadata and the exact
   license URL/text/version at request time. Compare it with the pinned Mosaico
   descriptor, and require review before accepting a host or format change;
   the direct `Canais.zip` constant must not become the only mutable source of
   truth.
2. Add conditional requests using retained validators, byte-level progress,
   cancellation/checkpoints, and the approved WorkManager/UIDT and
   process/reboot recovery design. The current whole-archive foreground path
   must not be relabeled as any of these capabilities.
3. Define user-visible retention, reference-aware cleanup, and full-store
   recovery for both immutable raw archives and indexes. Current bounded stores
   fail closed rather than silently evicting evidence.
4. Expose municipality, frequency-range, and Basic Plan ID filters in the UI
   only where their information density remains usable; the core already
   supports them.
5. Add explicit update comparison and snapshot diff semantics. A newer mutable
   catalog must not silently reinterpret historical work.
6. If project work uses a Basic Plan row, pin its stable row identity and exact
   catalog-generation hash, expose application as an explicit reviewed action,
   and preserve both source and project values. No project pin, application,
   diff, study input, or contour integration is delivered today.

Basic Plan data is a channel-planning catalog. A row does not by itself prove a
currently licensed station, permission to operate, an approved antenna
pattern, or a strict protected/interference calculation. Protected-contour
source fields from the catalog must not be silently merged with locally modeled
P.1546 geometry. A later regulatory workflow must show which values came from
the official catalog, which came from the project, which were modeled, and
which remain `NoData`.

## 11. Implementation and evidence map

| Concern | Current source/evidence |
|---|---|
| Canonical samples, provenance, bounds, availability | `domain/antenna/AntennaPatternModels.kt` |
| Complex interpolation, coordinate mapping, separable evaluation | `domain/antenna/AntennaPatternEngine.kt` and `AntennaPatternEngineTest.kt` |
| Coherent synthesis and numerical gain integration | `domain/antenna/AntennaArrayComposer.kt` and `AntennaArrayComposerTest.kt` |
| Format detection, Android canonical and desktop-v1 JSON interchange, JSON lexical preflight, metadata-preserving round trips, duplicate-angle golden parity, and hostile-input limits | `data/antenna/AntennaPatternFileCodecs.kt`, `AntennaPatternCodec.kt`, `AntennaPatternFileCodecsTest.kt`, `AntennaPatternCodecMetadataTest.kt`, `AntennaPatternDuplicateAngleGoldenTest.kt`, and `AntennaPatternDesktopTableParityTest.kt` |
| Deterministic independent HRP/VRP pairing and source bundle | `data/antenna/AntennaPatternPairCodec.kt` and `AntennaPatternPairCodecTest.kt` |
| Schema-6 fixed-grid mapping | `domain/application/AntennaPatternMapping.kt` and `domain/model/ProjectModels.kt` |
| Stale-safe install, assignment, and deletion | `domain/application/AntennaPatternCatalogUseCase.kt` and `AntennaPatternCatalogUseCaseTest.kt` |
| SAF review/store/export orchestration | `ui/antenna/AntennaPatternLabViewModel.kt` |
| Compact library/composer/assignment UI | `ui/screens/AntennaPatternLabScreen.kt` and `AntennaPatternLabScreenTest.kt` |
| Directional broadcast ERP and provenance | `domain/contour/BrazilBroadcastContours.kt` and `BrazilBroadcastContoursTest.kt` |
| Deterministic service-contour KMZ export | `data/export/ServiceContourKmzExporter.kt` and `ServiceContourKmzExporterTest.kt` |
| Anatel source/provenance/frequency models | `domain/anatel/AnatelBasicPlanModels.kt` and `AnatelChannelFrequencyResolver.kt` |
| Anatel refresh/status/query contract | `domain/anatel/AnatelBasicPlanCatalog.kt` and `AnatelBasicPlanCatalogTest.kt` |
| Bounded Anatel ZIP/XML parsing | `data/anatel/AnatelBasicPlanArchiveParser.kt` and `AnatelBasicPlanArchiveParserTest.kt` |
| Immutable raw snapshots and atomic current pointer | `data/anatel/AnatelBasicPlanCatalogFiles.kt` |
| Staged SQLite v1 index and offline query | `data/anatel/AnatelBasicPlanSqliteIndex.kt` |
| Production catalog orchestration | `data/anatel/AndroidAnatelBasicPlanCatalog.kt` and `AndroidAnatelBasicPlanCatalogTest.kt` |
| Review-gated refresh, filters, and paging state | `ui/anatel/AnatelBasicPlanViewModel.kt` and `AnatelBasicPlanViewModelTest.kt` |
| Compact Data Catalog section | `ui/screens/AnatelBasicPlanCatalogSection.kt` and `CatalogScreenTest.kt` |

Tests establish bounded deterministic behavior for the implemented model and
formats. They are not manufacturer conformance, independent full-wave parity,
field validation, regulatory certification, a complete device matrix, or a
live-official-source, conditional-refresh, retention-recovery,
background/process-survival, project-application, or regulatory Basic Plan
test. Synthetic repository instrumentation does cover the current bounded raw,
parse, staged-index, atomic-publication, offline-query, and failed-refresh
preservation paths.
