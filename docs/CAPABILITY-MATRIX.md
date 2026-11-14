# Capability matrix: type × operation × serialization × contract version

Status date: **2026-11-14**. This is the honest capability statement of
`fair-bioheaders-java`. It exists so that a gap is a named row rather than an
inference, and so that no summary of "supports FHR, FHP, FHGFF3, FHT and FHGM"
can be read into this library: **the five-type family is not five types of
support here.** Cell vocabulary:

| mark | meaning |
| --- | --- |
| `yes` | implemented, and covered by a named test or vector set in this repository |
| `partial` | implemented for a stated subset; the remainder is named in the row |
| `no` | not implemented - the reason follows, and it is a real hole, not a formality |
| `n/a` | the cell is not a thing this library could do (why it says so) |

Anything not marked `yes` is not claimed. `docs/STATUS.md` carries the vector
counts and the "not verified" list.

## 1. Type × operation

| operation | FHR | FHP | FHGFF3 | FHT | FHGM |
| --- | --- | --- | --- | --- | --- |
| read the embedded header block | yes | yes | yes | yes, **draft lane only** (opt-in, pinned) | yes as a *carrier* only: FHGM has no header of its own; its records are read from an FHGFF3 header under `x-fhgm-models` (last row) |
| verify the SHA-512/256 checksum over exact bytes | yes | yes | yes | yes, draft lane | n/a - integrity of an FHGM-carrying file is the enclosing FHGFF3 header's checksum, which is `yes` above |
| select the contract (rules V1-V6) | yes | yes | yes | yes (V7 lane; V1-V6 have no FHT row) | n/a - FHGM records carry no `schema` field; the file is routed by its FHGFF3 citation |
| validate against the contract | yes | yes | yes | yes, against the **pinned draft bytes** | yes, **draft lane only**: the pinned record contract and transport overlay, plus the K3-K6 model rules, by `FhgmModels` |
| rule R11 record-marker warning | yes | yes | n/a (GFF3 lines are not FASTA) | yes, draft lane | n/a - only a carrier file could be FASTA, and the carrier is GFF3 |
| rule R14 link-scheme warning | yes | yes | yes | yes (fields derived from the draft contract) | no - R14 speaks about a header's own `format: uri` fields; the draft suite asserts no R14 lane for nested records, so none is invented here (§4 row 3) |
| rule R15 extension fields | yes (2.1+) | yes (1.1+) | yes (1.1+) | yes (the draft admits `x-`) | the carrier admits them; `FhgmModels` reads `x-fhgm-models` as records and leaves every other `x-` key uninterpreted |
| parent-link verdicts (`derivedFrom[].checksum`) | yes, caller-supplied parents | yes, caller-supplied parents | yes, caller-supplied parents | yes, caller-supplied parents (the FHT suite's C3 vectors run here) | the carrier file's links are FHGFF3's; K6 pointer resolution is a separate rule and is implemented |
| write / combine a header | no - this library never writes; use `bioheaders combine` | no | no | no | no |
| strip a header | no | no | no | no | no |
| convert between serializations | no | no | no | no | no |
| `faidx` (htslib-compatible index) | no | no | no | no | n/a |
| verify-links as a standalone command | partial - `ParentLinks` is the operation, exposed through the library and `verify --parent`; there is no `verify-links` subcommand, no recursive lineage walk and no accession-ID cross-check | partial (same) | partial (same) | partial (same) | no |
| GFF3 feature-line validation (syntax, SO, biology) | n/a | n/a | no - belongs to `gff3-validator`; FHGFF3 vectors here pass on header/placement/link/checksum grounds only | n/a | no - and the FHGM suite records its `gff3` layer as skipped for the same reason; no FHGM rule depends on it |
| assess / rubric / conformance reporting | no | no | no | no | no |
| read gene-model records (`x-fhgm-models`) | n/a | n/a | as uninterpreted bytes in the parsed header | n/a | **yes, draft lane only**: `FhgmModels` with K1-K6, opt-in and warned per record |
| resolve a revision pointer against supplied history (K6) | n/a | n/a | n/a | n/a | yes, draft lane, for the snapshots the caller hands over; no directory scan, no accession lookup, no curator-acceptance claim |

## 2. Type × serialization (input side; this library has no output side)

| serialization | FHR | FHP | FHGFF3 | FHT | FHGM |
| --- | --- | --- | --- | --- | --- |
| FASTA `;~` block, exact bytes (LF, CRLF, lone CR) | yes | yes | n/a | yes, draft lane | n/a |
| GFA `#~` block | yes | n/a | n/a | no - the draft suite has no GFA vectors | n/a |
| GFF3 `#~` block after `##gff-version 3` | no - FHR is FASTA/GFA | n/a | yes | n/a | no |
| gzip and multi-member bgzip transport | yes | yes | yes | yes, draft lane (the suite's gzip/BGZF vectors run) | not exercised: the FHGM suite ships uncompressed GFF3 |
| header YAML → JSON value model (R8/R12/R13) | yes | yes | yes | yes, draft lane | yes, as the carrier's YAML; the records are read out of it by `FhgmModels` |
| JSON metadata document (contract validation alone) | yes, `SchemaBundle.validate` on a `JsonNode` | yes | yes | yes, draft lane | yes for the record contract itself (K2 over a parsed carrier); there is no standalone FHGM JSON document format |
| HTML microdata (docs/MICRODATA.md M1-M5) | no | no | no | no | n/a |
| JSON-LD (docs/JSONLD.md J1-J9) | no | no | no | no | n/a |
| schema.org export (X rules) | no | no | no | no | n/a |

## 3. Contract-version coverage

`yes` = a bundled, digest-pinned copy exists and selection resolves to it
(rule V4 reports anything else as unsupported-version).

| type | contract versions bundled | current (`main`) copy | evidence |
| --- | --- | --- | --- |
| FHR | `1` (v0.3.0/v0.3.1), `2.0` (v0.4.0-v0.4.2), `2.1` (v0.5.0/v0.6.0) | `fhr-2.1.json` = v0.6.0 bytes, identical to v0.5.0 | 98 `fasta` vectors (incl. rule V6 FHR 1 paths) |
| FHP | `1.0` (v0.4.0-v0.4.2), `1.1` (v0.5.0), `1.2` (v0.6.0) | `fhp-1.2.json` | 36 `fhp-fasta` vectors |
| FHGFF3 | `1.0` (v0.4.0-v0.4.2), `1.1` (v0.5.0), `1.2` (v0.6.0) | `fhgff3-1.2.json` | 38 `fhgff3` vectors |
| FHT | none - **no released contract** | draft `fht-draft.json`: FHT-Specification commit `226f923c28b680edb3c546e99a751482d06aef1a`, SHA-256 `59ad9641adcb0e5591045c2d894c066d1adc9984cca84c5ff6502f8dad88487e` | 66 vectors of the FHT **draft** suite, in the labelled `draft-vectors` job only |
| FHGM | none - **no released contract** | two pinned draft files, both opt-in: record contract `fhgm-model-draft.json` = FHGM-Specification commit `ac3d37b3033312844fc3151217731928ae33aa54`, SHA-256 `bf1553cac04dc88d8afaff1d39bc50360342e1ebedac4b73dfe3dff570ff410f` (9 564 B); transport overlay `fhgm-transport-draft.json`, SHA-256 `dd9f69274f26860bbfd71d0e8b2794420c5369fb5d40ce838fb6a9e6d5116e3d` (1 363 B). The base schema the suite was observed against is released FHGFF3 1.1 (`v0.5.0`), whose frozen copy in this jar is byte-identical to it. Both blobs re-verified against live `main` on 2026-11-14. | 31 vectors of the FHGM **draft** suite (`draft-vectors` job only), plus 10 synthetic rule tests |

Releases `v0.4.1`/`v0.4.2` repeat the `v0.4.0` bytes and `v0.6.0`'s `fhr.json`
repeats `v0.5.0`'s: release identity and byte identity are recorded as separate
axes in `schemas/provenance.json`, and every copy in the jar is digest-checked on
every build (`CapabilityTest`, `DraftProvenanceTest`).

## 4. Named holes, with reasons

Cells a reviewer should not have to guess about:

1. **HTML microdata (26 shared vectors) and JSON-LD (37 shared vectors): not run.**
   Reason: the bindings (`docs/MICRODATA.md` rules M1-M5, `docs/JSONLD.md` rules
   J1-J9, the context documents and the canonical-serialisation rules) are not
   implemented in this library, so there is nothing for those vectors to
   exercise. They are reported as *not run* with their ids by
   `ConformanceReportTest` and counted in the 291-vector ledger as 63 not-run,
   never as passing and never omitted from the total.
2. **FHGM: a pinned draft reading of the records, and nothing more.** `FhgmModels` validates the
   records under `x-fhgm-models` against the two pinned contract files (K1 transport, K2 record
   shape) and enforces K3 (one `modelId` per snapshot), K4 (`featureId` percent-decodes to exactly
   one local gene row), K5 (the record's reference shares a comparable form with the file's genome
   link and never contradicts it) and K6 (a `predecessor` claim resolves against the prior snapshots
   the caller supplied, and only those), warning per record. What it is **not**: a released type (no
   `schemaVersion`, no w3id URL, no registry row), a supported type, a feature-line validator, or a
   lineage resolver. A carrier whose FHGFF3 header or checksum is broken is refused at boundary B1
   before any model verdict - the suite's own ordering, and the reason no FHGM pass can be read as
   an integrity statement.
3. **GFA containers for the FHT draft: no.** Reason: the draft suite defines its
   format as `fht-fasta` only; inventing GFA draft fixtures here would be
   authoring normative expectations the owning repository has not stated.
4. **Parent links: partial.** `ParentLinks` recomputes and compares the exact
   strings of caller-supplied parent files and returns a per-entry
   `CHECKED_PASS` / `CHECKED_FAIL` / `UNCHECKED` verdict; a failing entry rejects
   the record. It is not a `verify-links` command: there is no lineage walk, no
   accession-ID cross-check, and no directory scan, and an unsupplied parent is
   reported as unchecked rather than verified.
5. **`verify` never consults parents.** Deliberate: reading one file cannot
   establish a link to another, and the released default behavior is pinned
   byte-for-byte by `src/test/resources/golden/default-behavior-v0.6.0.json`.
6. **Rule V7 is opt-in and labelled.** `Options.enableDraftContracts(true)` (or
   `--experimental-drafts`, `-Dorg.fairbioheaders.experimental.drafts=1`,
   `FAIR_BIOHEADERS_EXPERIMENTAL_DRAFTS=1`). With the gate closed the default is
   byte-identical to the released-only reader; with it open, every record read
   through the lane carries a `V7` warning naming the commit and SHA-256 and is
   logged. A draft never takes a release form: a `.../fht/vX.Y.Z` w3id URL or an
   `FHT-Specification` raw **tag** URL is not a draft citation and stays rule V5
   with the near-miss warning (both cases are vectors in the FHT suite and tests
   here). No `schemaVersion`, registry row, alias or support claim is invented
   for a draft.
7. **Microdata `R14`/`R15` per-type divergence, `assess`, `--online` resolution,
   directive cross-checks (`#!genome-build`), the toolkit's streaming bounds and
   its size limits as bounds (not whole-buffer checks):** not implemented; see
   `docs/STATUS.md`.

## 5. How to read a count in this repository

* Released-contract acceptance: 228 of 291 shared vectors run; 63 declared
  not-run with reason 1 above.
* Draft evidence, in the labelled `draft-vectors` job only (175 tests: 66 FHT
  vectors, 66 lane-off controls, 31 FHGM vectors, 10 FHGM rule tests, 2 ledgers),
  each suite's receipt naming the contract commit and digest and the suite commit
  and manifest digest it measured. Neither count is added to the 291, and neither
  is a support claim.
* Every gap is a named row here with its reason. A missing capability is
  reported as missing; nothing is inferred from the library's class names, from
  the presence of a registry row, or from the reference toolkit's coverage.
