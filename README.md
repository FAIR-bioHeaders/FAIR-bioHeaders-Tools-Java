# FAIR-bioHeaders Tools (Java)

Java library and CLI for **reading, checksum-verifying and schema-validating**
FAIR-bioHeaders metadata headers in data files: FHR headers in reference-genome
FASTA/GFA, FHP headers in protein FASTA, and FHGFF3 headers in GFF3. It is the
Java member of the per-language library family; the normative definitions live
in [FAIR-bioHeaders-Specification](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification)
and the reference implementation — with header writing, conversion, stripping
and the full CLI — is [FAIR-bioHeaders-Tools](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Tools)
(`pip install fair-bioheaders`). This repository makes **no release claim**:
it is `0.1.0-SNAPSHOT` scaffold-plus-slice, and acceptance is the shared
conformance-vector suite, not a new per-language one. See
[docs/STATUS.md](docs/STATUS.md) for exactly what is implemented, the vector
results and the open gaps.

A FAIR-bioHeaders header is a block of YAML lines at the top of the file
(`;~` lines in FASTA, `#~` in GFA and GFF3), validated against a JSON Schema
(draft/2020-12) and covered by a **SHA-512/256** checksum of the file's exact
bytes.

| Header | File | Schema URL (current release v0.6.0) | `schemaVersion` |
| --- | --- | --- | --- |
| FHR | reference genome FASTA or GFA | `https://w3id.org/fair-bioheaders/fhr/v0.6.0` | `'2.1'` |
| FHP | protein FASTA | `https://w3id.org/fair-bioheaders/fhp/v0.6.0` | `'1.2'` |
| FHGFF3 | GFF3 annotation | `https://w3id.org/fair-bioheaders/fhgff3/v0.6.0` | `'1.2'` |

FHT and FHGM have **no released contract** (drafts; specification rule V7) and
this library implements no draft lane for them.

## Requirements

Java 17 or later. Dependencies: networknt json-schema-validator, SnakeYAML
(parser only), Jackson (records and exact versions:
[docs/DEPENDENCIES.md](docs/DEPENDENCIES.md)).

## Build and test

```bash
./mvnw test          # unit tests; the conformance-vector tests need a
                     # FAIR-bioHeaders-Specification checkout (next command)
./mvnw test -Dfh.spec.dir=/path/to/FAIR-bioHeaders-Specification
```

## Use

Library:

```java
import org.fairbioheaders.BioHeaders;
import org.fairbioheaders.VerifyResult;

VerifyResult result = new BioHeaders().verifyFile(Path.of("genome.fhr.fasta"));
if (result.isValid()) {
    System.out.println(result.computedChecksum());   // recomputed SHA-512/256
    System.out.println(result.metadata());           // parsed header as JSON
}
for (var issue : result.issues()) {
    System.out.println(issue);                       // rule-labelled errors/warnings
}
```

CLI (after `./mvnw package`; put the dependency jars on the classpath or use a
shaded build of your own):

```bash
java -cp target/classes:<deps> org.fairbioheaders.cli.Main verify genome.fhr.fasta
```

Gzip and bgzip inputs are decompressed transparently for checksum coverage
(FORMAT.md R1: compression is transport).

## Rules you must not break

- **Never write or edit a checksum by hand.** Only a writer like
  `bioheaders combine` computes it; any byte change outside the checksum line
  invalidates it (this library verifies, it does not write).
- **Strict FASTA readers reject a leading `;~` block** — samtools faidx and
  the rest of htslib, Picard, Biopython's default `fasta` format and others
  (see the specification's `docs/TOOL_COMPATIBILITY.md`). Strip first with the
  Python toolkit, or use Biopython's `fasta-pearson`; htslib-based tools can
  use an index from `bioheaders faidx`.
- FASTA header values containing `>` or `@` are written escaped as `\x3E` and
  `\x40` (FORMAT.md R11); a file with the raw characters stays valid and this
  reader warns about it.
- A file's `schema` URL selects the contract it is validated against
  (rules V1–V6); unknown versions are reported **unsupported**, never silently
  re-validated against another schema.

## Layout

```
src/main/java/org/fairbioheaders/   reader, checksum, YAML R12 resolver, selection, CLI
src/main/resources/.../schemas/     frozen contract copies + bundled.json + provenance.json
src/test/java/.../conformance/      runner for the shared spec conformance vectors
docs/STATUS.md                      implemented slice, vector results, not-verified list
docs/DEPENDENCIES.md                every dependency's version and license
llms.txt                            key facts for assistants and tools
skills/                             agent skill file for this library
AGENTS.md CONTRIBUTING.md SECURITY.md CODE_OF_CONDUCT.md
```

## License

[MPL-2.0](LICENSE) — see the scope note in that file and the dependency record
in [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md). Bundled schema copies retain
the FAIR-bioHeaders-Specification license terms recorded in
`schemas/provenance.json`.

## Related

- [FAIR-bioHeaders-Specification](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification) — contracts, format rules, conformance vectors (publication authority)
- [FAIR-bioHeaders-Tools](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Tools) — Python reference toolkit (`bioheaders` CLI)
- [gff3-validator](https://github.com/FAIR-bioHeaders/gff3-validator) — GFF3 feature/SO validation
- [fhref.org](https://fhref.org/) — project site
- Paper: Wright et al. 2024, Briefings in Bioinformatics, [doi:10.1093/bib/bbae122](https://doi.org/10.1093/bib/bbae122)
