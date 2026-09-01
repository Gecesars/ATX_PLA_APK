# Android regulatory input pipeline

Status date: September 1, 2026.

This document distinguishes the delivered Android engineering workflow from external filing approval. The implementation is CPU-only and runs on the physical Android device. It does not require an emulator or GPU compute.

## Delivered source pipeline

### ANADEM v1.0 bare-earth terrain

- The regulatory runner no longer accepts Copernicus GLO-30 DSM as filing terrain.
- It resolves the MGRS ANADEM tiles intersecting a fail-closed 620 km terrain envelope around the independent project site.
- Large source GeoTIFFs remain remote. The app requests only TIFF metadata pages and compressed 512 × 512 blocks needed by actual samples.
- Every session requires HTTPS, the fixed `metadados.snirh.gov.br` host, exact `206 Content-Range`, a strong ETag, and a bounded source length.
- Each cached range is content-addressed by SHA-256. Export evidence separately records the stable source-identity digest and the range-manifest digest; neither is mislabeled as a full upstream GeoTIFF content hash.
- Each MGRS source cache is bounded to 512 MiB and evicts older ranges. Insufficient storage fails explicitly.
- Source: [ANADEM data repository](https://hge-iph.github.io/anadem/) and [UFRGS project description](https://www.ufrgs.br/hge/anadem-modelo-digital-de-terreno-mdt/).

### IBGE 2022 census-sector geometry

- The APK still embeds the verified national municipality, sector-attribute, area, population, and envelope index.
- A study downloads the official GeoPackage for only the selected state. The transfer is resumable, strong-ETag-bound, byte-bounded, SHA-256 verified locally, and atomically installed in private no-backup storage.
- The reader validates the SQLite header, `quick_check`, GeoPackage feature metadata, SIRGAS 2000 (`EPSG:4674`), required columns, polygon types, byte order, ring closure, coordinate bounds, feature sizes, sector identities, and exact attribute joins.
- The transmitter is tested against every official census-sector situation in the selected municipality. Coverage uses only official urban sectors.
- A bounded spatial bucket index avoids a cell-by-every-sector quadratic scan while retaining exact polygon and hole containment.
- Source: [IBGE 2022 census-sector mesh](https://www.ibge.gov.br/geociencias/organizacao-do-territorio/malhas-territoriais/26565-malhas-de-setores-censitarios-divisoes-intramunicipais.html).

### MCom/Mosaico licensed baseline

- The app downloads the current official technical broadcast CSV from the MCom open-data publication, verifies and stores the source artifact, and builds a content-addressed private SQLite index.
- Product scope includes FM plus digital `GTVD` and `RTVD`. Analog `TV` and `RTV`, OM, and other services are excluded from this workflow.
- Digital TV projects remain limited to current channels 7–51. Legacy analog TV/RTV rows are retained only as untrusted catalog source data and are excluded from every regulatory calculation.
- Spectrum formerly associated with analog-TV channels 5 and 6 is represented only by the extended-FM channel plan: channels 141–197 at 76.1–87.3 MHz. The app never re-labels TV channel numbers 5 or 6 as FM channels.
- A licensed coordinate is mandatory for numerical D/U. A Basic Plan or municipality coordinate may locate a record for discovery only; it can never enter propagation calculations. Such a bounded candidate remains unevaluated and blocks the engineering gate.
- Same-service cochannel and first-adjacent licensed rows that have neither licensed nor discovery coordinates are counted separately and cannot be silently spatially excluded.
- Source: [MCom open broadcast datasets](https://www.gov.br/mcom/pt-br/acesso-a-informacao/dados-abertos/bases-abertas).

## Regulatory calculations

The project remains independent. The selected IBGE municipality identifies the proposed service municipality; it does not copy a station, channel, site, ERP, antenna, or height from the Basic Plan or licensed baseline.

- Protected contour: 72 true-north radials, ITU-R P.1546-6, FM `E(50,50)` or digital TV `E(50,90)`, largest crossing inside the requested bound.
- Coverage gate: all urban-sector area in the selected municipality is the denominator. Cells outside the protected contour are uncovered. Cells inside it use P.526-15 Deygout–Assis over 30 m ANADEM profiles. The mobile raster spacing is 250 m; field paths are cached in 1° azimuth and 250 m distance bins.
- Missing DTM samples produce lower and upper coverage bounds. A pass requires the lower bound to meet 50% for FM or 70% for digital TV. A fail requires even the upper bound to miss it. Every intermediate case is `NoData`.
- The population result is a clearly labeled uniform-within-sector area-weighted estimate. It is exported but does not override the area gate.
- Licensed D/U: every calculation-ready FM or digital-TV station is evaluated bidirectionally. Digital adjacent stations within 5 km use the ERP ratio rule. There is no nearest-48 station truncation.
- Existing versus proposed: for every calculation-ready licensed wanted station, the app evaluates its existing licensed interferers, then compares that minimum individual-signal margin with the margin introduced by the independent project. Results distinguish `UNCHANGED_COMPLIANT`, `UNCHANGED_EXISTING_CONFLICT`, `NEW_CONFLICT`, `AGGRAVATED`, and `NO_DATA`.
- The comparison is an individual-signal D/U comparison, not a composite-field aggregation claim.

## Evidence and user interface

- The Studies screen requires a municipality selected from the verified offline IBGE index and explicit review of the on-demand source notice.
- Download and processing phases show labels, bytes, and progress without enlarging the compact product typography.
- The Engineering Map renders exact census rings only at zoom 12 or closer to protect phone frame time. All geometry still participates in the regulatory gate.
- HTML, PDF, and XLSX exports include terrain integrity scope, cached-range evidence, IBGE geometry provenance, the coverage interval, MCom snapshot provenance, licensed scenario comparisons, blockers, assumptions, units, and `NoData`.

## Remaining external gates

The app may pass its delivered engineering gates, but it deliberately remains not filing-ready until both conditions below are completed outside the implementation:

1. independent numerical parity is signed off against an accepted regulatory reference implementation and controlled fixtures; and
2. a qualified Brazilian broadcast engineer performs a current technical, legal, dataset-license, and filing review.

Source publication changes, unavailable official files, weak or changed validators, unlocated relevant stations, malformed data, missing DTM, incomplete contours, incomplete D/U, coverage uncertainty, new/aggravated interference, and storage exhaustion all fail closed.
