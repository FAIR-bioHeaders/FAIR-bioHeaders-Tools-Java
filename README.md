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

| Header | File | Schema URL (contracts bundled from spec v0.6.0) | `schemaVersion` |
| --- | --- | --- | --- |
| FHR | reference genome FASTA or GFA | `https://w3id.org/fair-bioheaders/fhr/v0.6.0` | `'2.1'` |
| FHP | protein FASTA | `https://w3id.org/fair-bioheaders/fhp/v0.6.0` | `'1.2'` |
| FHGFF3 | GFF3 annotation | `https://w3id.org/fair-bioheaders/fhgff3/v0.6.0` | `'1.2'` |

The specification has released further versions since (through **v0.8.0**,
published 2026-11-20; see its `schemas/versions.json` — FHR 2.1, FHP 1.2 and
FHGFF3 1.2 are unchanged across v0.6.0 → v0.8.0). This build bundles the
**v0.6.0** copies, frozen by digest and verified on every build; promoting this
consumer's pins to a newer release is a separate deliberate dated change, not
implied by the specification's release train.

**Draft state, stated as of 2026-11-20.** FHT was **released as contract 1.0**
in specification **v0.7.0** (`w3id.org/fair-bioheaders/fht/v0.7.0` → the tag's
`fht.json`, and `FHT: "1.0"` now has a row in the shared registry), and FHGM
0.1 is published in specification **v0.8.0** as a **native model contract**
under `model-contracts/fhgm/0.1/` — deliberately *not* a header type, so it has
no file-bound `schemaVersion` and no header-registry row. This library has not
yet been promoted to either released form: it still registers **FHT** and
**FHGM** as commit-pinned drafts readable only through an explicit opt-in, and
until a deliberate pin-upgrade lands here, released-contract support for FHT is
**not claimed** even though the family has released one. With the opt-in closed
— the default — a draft URL in a `schema` field is rule V5 material (read as
FHR, near-miss warning), byte-identically to before the rows existed. The
FHT/FHGM w3id aliases for the model contract did not resolve on a live probe of
2026-11-20; the raw tag paths do, and alias registration stays an external
action tracked on the specification repository.

The opt-in, from Java and from the CLI:

```java
new BioHeaders(Options.builder().enableDraftContracts(true).build());
```
```bash
java -jar target/fair-bioheaders-java-0.1.0-SNAPSHOT.jar verify --experimental-drafts transcripts.fht.fasta
# or: -Dorg.fairbioheaders.experimental.drafts=1, FAIR_BIOHEADERS_EXPERIMENTAL_DRAFTS=1
```

A record read that way is validated against FHT-Specification's pinned
`fht.json` bytes (commit and SHA-256 in `schemas/bundled.json`, re-digest-checked
on every build) and carries a `V7` warning naming them, because a draft result is
not a released-contract result. Naming one lane is possible without opening the
other: `Options.builder().enableDraft("FHT")` (CLI: `--draft FHT`).

`FHGM` is registered the same way, as a draft of **records rather than a header**:
its records ride inside an FHGFF3 header under the `x-fhgm-models` extension key,
so the file stays routed by its own released FHGFF3 citation and the lane selects
only the contract and the validator applied to the records.

```java
FhgmModels models = FhgmModels.open(Options.builder().enableDraft("FHGM").build());
FhgmModels.Report report = models.check(gff3Bytes, List.of(priorSnapshotBytes));
```

`FhgmModels` runs the draft's model-level rules - transport overlay (K1), record
shape (K2), NFC-normalised `modelId` uniqueness (K3), `featureId` resolution to exactly one local
**root** gene row (K4: `gene` or `SO:0000704` with no `Parent`; a non-empty `Parent` is nested and
an empty `Parent=` cannot establish root, so neither passes), reference agreement with the file's
genome link (K5), and
revision-pointer resolution against the history you supply (K6) - against two
pinned contract files, and it refuses any carrier whose released FHGFF3 schema or
checksum fails first (the suite's boundary B1). A model-layer `pass` carries the
FHGM-Specification#29 widened meaning — named in [docs/CAPABILITY-MATRIX.md](docs/CAPABILITY-MATRIX.md),
and the K4/abstention semantics remain an open upstream question (`FHGM#22`/`#27`/`#28`), so a
`pass` is participation in the draft's current reading, not a settled verdict.
It is not support, not a release,
and not a feature-line validator: `gff3-validator` remains that. With the lane
closed, `FhgmModels` will not construct at all.

Contract `pattern`s are evaluated under the family's normative ECMA-262 floor
(`FAIR-bioHeaders-Specification#264`, `docs/REGEX-DIALECT.md`): `java.util.regex`'s ASCII-only
`\s` and its `$`-before-trailing-LF reading are translated, so a DOI or identifier padded with
U+00A0, U+202F or U+FEFF is INVALID here exactly as the reference engine reports it, while U+0085
stays legal because ECMA-262 says so (`EcmaRegularExpressionFactory`,
`FhtDraftWhitespaceEcmaTest`).

[docs/CAPABILITY-MATRIX.md](docs/CAPABILITY-MATRIX.md) is the type × operation ×
serialization × version statement, including the named holes;
[docs/STATUS.md](docs/STATUS.md) has the vector counts and
[docs/NATIVE-ACCEPTANCE.md](docs/NATIVE-ACCEPTANCE.md) the commands and guards.

## Requirements

Java 17 or later. Dependencies: networknt json-schema-validator, SnakeYAML
(parser only), Jackson (records and exact versions:
[docs/DEPENDENCIES.md](docs/DEPENDENCIES.md)).

## Build and test

Requirements: **Java 17 or later** (the toolchain is the Maven Wrapper —
`./mvnw` downloads its pinned Maven; no system Maven needed). Verified locally
on 2026-11-20 with Homebrew OpenJDK 27.0.1; `maven.compiler.release` is 17, so
language features stay at the Java 17 level.

```bash
./mvnw test          # unit tests; the conformance-vector tests need a
                     # FAIR-bioHeaders-Specification checkout (next command)
./mvnw test -Dfh.spec.dir=/path/to/FAIR-bioHeaders-Specification
```

The shared-vector tests locate the specification checkout via `-Dfh.spec.dir`,
the `FH_SPEC_DIR` environment variable, or a sibling directory; with none of
those they **skip** — a skipped vector is never reported as a pass. Released
and draft evidence are separate test groups and separate counts;
[docs/NATIVE-ACCEPTANCE.md](docs/NATIVE-ACCEPTANCE.md) lists the exact commands
and report guards.

## Using it from another project

**Nothing is published.** This artifact is `org.fairbioheaders:fair-bioheaders-java:0.1.0-SNAPSHOT`
in this repository's sources only. There is no GitHub Release for it, and a
Maven Central search for group `org.fairbioheaders` returned zero artifacts on
2026-11-20; no registry availability is claimed or implied below. Publication
is a separate maintainer action.

To consume it from a local Maven build, install it into your local repository
first:

```bash
./mvnw -DskipTests install     # -> ~/.m2/repository/org/fairbioheaders/fair-bioheaders-java/0.1.0-SNAPSHOT/
```

then add it like any SNAPSHOT coordinate — and refresh with
`mvn -U …` or rebuild after pulling this repository, because a SNAPSHOT is only
as current as your last install:

```xml
<dependency>
  <groupId>org.fairbioheaders</groupId>
  <artifactId>fair-bioheaders-java</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Without Maven, build the CLI jar and keep its `lib/` directory beside it — the
jar's manifest classpath names `lib/*`:

```bash
./mvnw -DskipTests package
java -jar target/fair-bioheaders-java-0.1.0-SNAPSHOT.jar verify genome.fhr.fasta
java -jar target/fair-bioheaders-java-0.1.0-SNAPSHOT.jar version
```

## Public API

Everything above the line is public API (`org.fairbioheaders`); the module map
in [AGENTS.md](AGENTS.md) names the internals.

| Surface | Signature | Result |
| --- | --- | --- |
| `BioHeaders()` / `BioHeaders(Options)` / `BioHeaders(SchemaBundle)` | constructors | default = released contracts only, environment-independent; `Options` carries the draft opt-in |
| `verifyFile(Path)` | reads, checksums, selects and validates | `VerifyResult`; throws `IOException` when the file cannot be read |
| `verifyFile(Path, ParentLinks.ParentSource)` | as above, with parent bytes for FHT draft rule C3 | `VerifyResult` with `parentLinkVerdicts()` |
| `verify(byte[], Kind)` / `verify(byte[], Kind, ParentSource)` | in-memory bytes; `Kind` is `FASTA`, `GFA` or `GFF3` (or use `kindFor(String)` from a file name) | `VerifyResult` |
| `readableTypes()` / `declaredDraftTypes()` / `options()` | registry views | which header types this build can select, and which drafts are declared (declared ≠ supported) |
| `VerifyResult.isValid()` / `.status()` | quick verdict | `true` only for checksum match plus contract validity; `status()` is `VALID`, `INVALID` or `UNSUPPORTED_VERSION` |
| `statedChecksum()` / `computedChecksum()` | base64 SHA-512/256 strings, exact comparison | null `stated` when the file carries no checksum line |
| `metadata()` | parsed header as a Jackson `JsonNode` | null when no header block was found |
| `errors()` / `warnings()` / `issues()` | rule-labelled `Issue` list (`severity()` — `ERROR` or `WARNING` — plus `rule()` and `message()`) | errors decide validity; warnings never do (except where the contract itself does) |
| `hasRule(String)` | rule-id membership test | e.g. `result.hasRule("V7")` identifies a draft-lane reading |
| `Options.builder().enableDraftContracts(true)` / `.enableDraft("FHT")` / `.enableDrafts(...)` | draft-lane controls | per-call, process-wide via `-Dorg.fairbioheaders.experimental.drafts=1`, or `FAIR_BIOHEADERS_EXPERIMENTAL_DRAFTS=1` |
| `FhgmModels.open(Options)` → `.check(gff3Bytes, priorSnapshots)` | FHGM draft model-layer verdicts (K1–K6) against the pinned contracts | refuses to construct with the lane closed; refuses a carrier whose released FHGFF3 header fails first |

CLI exit codes: **0** every named file verified, **1** any file invalid,
unsupported or unreadable (the line says which), **2** usage error. Output
escapes terminal control characters in file names and header values, so a
hostile file cannot forge a report line. Gzip and bgzip inputs are
decompressed transparently for checksum coverage (FORMAT.md R1: compression is
transport).

## Quick example (library)

```java
import java.nio.file.Path;
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

The CLI workflow (`package`, `install`, exit codes, draft flags) is above in
[Using it from another project](#using-it-from-another-project) and
[Public API](#public-api).

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

Historical-corpus accounting (retired predicates, canonical counterparts and the D9/R4 checks behind these numbers) is documented in [docs/STATUS.md](docs/STATUS.md#retired-predicate-accounting-2026-11-19).
