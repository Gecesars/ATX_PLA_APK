# javaP1546 attribution

`P1546LandReference.kt` contains a compact, modified numerical subset derived from
[eeveetza/javaP1546](https://github.com/eeveetza/javaP1546) commit
`4d570c2de2d9cb8b27d36b5aefab03c229b5de9d`.

Changes made for ATX Plan Android on 2026-08-28:

- retained only land-path tables for 10% and 50% time;
- retained the 100, 600, and 2000 MHz nominal frequencies, eight nominal effective heights,
  and 78 nominal distances;
- quantized table fields to 0.01 dB and stored them as a compact offline byte sequence;
- implemented bounded logarithmic interpolation in Kotlin;
- excluded mixed/sea paths, clutter correction, terrain-clearance correction, and unsupported
  probability dimensions so those cases can fail closed at the application boundary.

The upstream implementation identifies itself as functionally identical to the reference version
approved by ITU-R Working Party 3K. ATX Plan Android still treats its generated geometry as a
planning reference until independent golden-vector, terrain, antenna-pattern, and filing workflows
are complete.
