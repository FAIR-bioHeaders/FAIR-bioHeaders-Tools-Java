---
name: fair-bioheaders-java
description: Read, checksum-verify and JSON-Schema-validate FAIR-bioHeaders headers from Java/JVM code - FHR headers in reference genome FASTA or GFA, FHP headers in protein FASTA, FHGFF3 headers in GFF3 - using the FAIR-bioHeaders-Tools-Java library. Use when a JVM project must validate a `;~` or `#~` header, recompute its SHA-512/256 checksum, or select the right released contract. For writing, combining, stripping or converting headers use the Python `fair-bioheaders` toolkit instead; this library is verification-only.
---

# FAIR-bioHeaders headers from Java (verification only)

A FAIR-bioHeaders header is a block of YAML lines at the top of a data file:
`;~key: value` lines in FASTA, `#~key: value` lines in GFA and GFF3 (after
`##gff-version 3`). The metadata validates against a JSON Schema contract, and
a SHA-512/256 checksum covers every file byte except the checksum line and its
terminator (FORMAT.md R1-R5). Specification:
https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification (rules R1 to
R15 in `docs/FORMAT.md`).

| Header | File | Schema URL (released v0.6.0) | `schemaVersion` |
| --- | --- | --- | --- |
| FHR | reference genome FASTA or GFA | `https://w3id.org/fair-bioheaders/fhr/v0.6.0` | `'2.1'` |
| FHP | protein FASTA | `https://w3id.org/fair-bioheaders/fhp/v0.6.0` | `'1.2'` |
| FHGFF3 | GFF3 annotation | `https://w3id.org/fair-bioheaders/fhgff3/v0.6.0` | `'1.2'` |

Older bundled selections: v0.3.0/v0.3.1 (FHR 1), v0.4.x (FHR 2.0, FHP/FHGFF3
1.0), v0.5.0 (FHR 2.1, FHP/FHGFF3 1.1). FHT and FHGM are drafts with **no
released schema**: never cite, version or support-claim them (rule V7).

## Rules

- **Never invent metadata.** Species, authors, ORCIDs, accessions, versions,
  dates and SeqCol digests come from the user or from files they point to.
- **Never compute or edit the checksum by hand.** This library only verifies.
  To add or fix a header, use the Python toolkit: `bioheaders combine`
  computes the checksum; after any change, combine again.
- **Respect `schema`-URL selection (V1-V6).** A record is validated against
  the contract its own `schema` value selects; an unknown release returns
  `UNSUPPORTED_VERSION` and is a failure, not a license to re-check against
  another schema. Do not hand the wrong-type contract to a record: use
  `BioHeaders.verify`, which selects via `SchemaSelection`.
- **Verification does not mean the file is deposit-ready.** A header is not
  an INSDC submission, and FHGFF3 verification here does not validate feature
  lines — use gff3-validator for features.
- **Strict FASTA readers reject a leading `;~` block** (htslib/samtools,
  Picard, Biopython default `fasta`): keep a stripped copy for them
  (`bioheaders strip`), or an index from `bioheaders faidx`.

## 1. Add the library

Not yet published to a Maven repository: build from this checkout
(`./mvnw package`, which also copies the runtime jars to `target/lib/` so
`java -jar target/fair-bioheaders-java-0.1.0-SNAPSHOT.jar verify FILE` works)
and depend on the jar plus its runtime dependencies
(declared in `pom.xml`; networknt json-schema-validator 3.0.9, snakeyaml 2.7,
Jackson 3.1.8 — Java 17+).

## 2. Verify a file

```java
import org.fairbioheaders.BioHeaders;
import org.fairbioheaders.VerifyResult;

BioHeaders verifier = new BioHeaders();          // thread-safe, caches contracts
VerifyResult r = verifier.verifyFile(Path.of("genome.fhr.fasta"));

switch (r.status()) {
    case VALID -> {
        System.out.println(r.computedChecksum()); // recomputed, matches stated
        System.out.println(r.metadata());         // parsed header as JsonNode
        r.warnings().forEach(System.out::println); // R11/R14/V5/V6 advisories
    }
    case INVALID -> r.errors().forEach(System.out::println);       // rule-labelled
    case UNSUPPORTED_VERSION -> System.out.println(r.errors());    // rule V4
}
```

Raw bytes: `verify(byte[] decompressedBytes, Kind.FASTA|Kind.GFA|Kind.GFF3)`;
gzip/bgzip files arrive through `BioHeaders.readAll(Path)` (R1: the checksum
covers decompressed bytes). CLI: `... cli.Main verify FILE...`, exit 0 only
when every file verifies.

## 3. Interpreting results

- `INVALID` with rule `R3`: the bytes do not hash to the stated checksum — the
  file (or its checksum line) was edited after combining; re-combine upstream.
- `INVALID` with rule `R12`: a plain scalar reads differently under YAML 1.1
  and 1.2 (`010`, `1_000`, `1:30`, `yes`, `1e3`) or carries an explicit tag;
  the writer must quote such values.
- `INVALID` with rule `schema`: a contract violation — see the message for the
  instance pointer; required fields and the `x-` extension rule (R15) are
  enforced by the selected contract itself.
- `UNSUPPORTED_VERSION`: the cited release is not bundled here; the message
  lists what is available.
- Only trust a verdict against the exact contract version the record cites;
  see `docs/STATUS.md` for what this library does not cover (microdata,
  JSON-LD, feature validation, FHT/FHGM).
