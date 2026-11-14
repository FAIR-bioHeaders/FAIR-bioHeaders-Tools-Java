# Contributing

Issues about **this Java implementation** belong here: parser bugs, missing
rule coverage, dependency and build problems, API ergonomics and conformance
failures against the shared vectors. Format rules, schema fields and version
decisions belong to
[FAIR-bioHeaders-Specification](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification);
reference-toolkit behavior belongs to
[FAIR-bioHeaders-Tools](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Tools);
GFF3 feature validation belongs to
[gff3-validator](https://github.com/FAIR-bioHeaders/gff3-validator). A change
that would need a new metadata field is a schema addition and must be proposed
upstream first — see AGENTS.md.

Start with an issue describing the use case or failure and a small
reproducible example (an actual file or the smallest synthetic one that shows
it). Submit a focused branch/PR with behavior, compatibility implications and
relevant validation. Do not include private genome data, credentials or
unrelated formatting in fixtures.

## Setup and checks

Java 17 or later; no local Maven install is needed.

```bash
./mvnw test
./mvnw test -Dfh.spec.dir=/path/to/FAIR-bioHeaders-Specification
```

The second command enables the shared conformance-vector tests; without a
specification checkout they skip rather than pass. CI clones the pinned
specification tag and runs them, so a PR is judged against the shared suite.
Acceptance is that suite — do not add a parallel per-language vector format;
if you find a gap, the vector belongs upstream.

## Contracts and bundled schemas

The copies under `src/main/resources/org/fairbioheaders/schemas/` are frozen
bytes of specification releases with provenance in `provenance.json`. Adopting
a new schema release means *adding* files and registry entries with tags,
commits and SHA-256 digests taken from the actual tags, plus selection and
version-bump coordination; never edit a frozen copy in place and never refresh
a drift check just to make it green.

## Release policy

Publishing a release (tag, GitHub Release, Maven Central deployment, Zenodo
record) is a separate maintainer action; contributions stop at reviewed PRs.

## License of contributions

New FAIR BioHeaders project contributions from March 2025 onward use
[MPL-2.0](LICENSE). This repository contains no historical USDA material;
third-party dependencies keep their own licenses and must be recorded in
docs/DEPENDENCIES.md when added. Contributors must have the rights needed for
these terms; retain source notices.

David and Adam are the maintainers and jointly hold schema and release
authority ([GOVERNANCE](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification/blob/main/GOVERNANCE.md)).
Follow [CODE_OF_CONDUCT](CODE_OF_CONDUCT.md) and use [SECURITY](SECURITY.md)
for sensitive findings.
