# Capability matrix: type × operation × serialization × contract version

Status date: **2026-11-18** (round-13 coordinated draft re-pin). This is the honest
capability statement of
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
| GFF3 `#~` block after `##gff-version 3` | no - FHR is FASTA/GFA (refused as a `container` error) | n/a (refused) | yes | n/a | no |
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
| FHT | none - **no released contract** | draft `fht-draft.json`: FHT-Specification commit `86438f21c3a17a58334c97aed4955be1a2393522` (last-touch of both contract and manifest), SHA-256 `4156353aa6d077fee2d2c69be4e17859b8e4f81bb10824f3c87f6701de196e7a` (34 396 B; contract bytes MOVED at the 2026-11-18 re-pin, from `59ad9641…`/29 430 B) | 82 vectors of the FHT **draft** suite (suite commit `86438f21…`, manifest SHA-256 `e5a4a5d0…`), in the labelled `draft-vectors` job only; 4 of the 82 are the suite's own `candidate-unreviewed` promotion cases |
| FHGM | none - **no released contract** | two pinned draft files, each with its own last-touch commit and digest, both opt-in: record contract `fhgm-model-draft.json` = FHGM-Specification commit `06176262f9824f26d974a15d8feeebde1fbeb0a8`, SHA-256 `b7075e5872e0a5ffce5ae61c590ce182f1f219fa269413cad3b1a966753cfeae` (11 522 B, moved from `bf1553ca…`); transport overlay `fhgm-transport-draft.json` = commit `019bc29e32ce225791053dd2ce323bb91f304ce1`, SHA-256 `533b3120f4a86590f1b60a2175487597c99b646b3d092b7818b1f682c6cecf9b` (3 191 B, moved from `d1619cce…`). **A model-layer `pass` under these bytes carries the FHGM#29 widened meaning:** beyond schema validity it asserts trimmed invisible-character-free text, real-host RFC 3986 links, non-blank deposits, NFC-unique identifiers and a root-level gene target - and that widening is invisible to a digest diff or a vector count. The overlay admits **both** released FHGFF3 bases as closed identity branches, selected by cited URL (FHGM#26): 1.1 (`v0.5.0`, `20ce320f…`) sealed lane, 1.2 (`v0.6.0`, `1942be4c…`) also admitting a `schemaVersion` 1.1 statement - each frozen in this jar and tag-resolved to a commit (`schemas/provenance.json` drafts.FHGM.baseSchemas; re-verified 2026-11-18). | 62 vectors of the FHGM **draft** suite (`draft-vectors` job only; suite commit `06176262…`, manifest SHA-256 `e41f05fd…`), plus 10 synthetic rule tests. **K4 caveat:** this library scores K4 natively while the abstention question is **open upstream** (FHGM#22/#27/#28, FAIR-bioHeaders-Specification#241; JS#11 documents the unfalsifiable-`pass` and `reject`-vs-`absent-layer` shapes) - 62/62 here is participation in a contested semantics, not its resolution, and is deliberately not comparable with Julia's 51/0/11-of-62, which declares K4 `not-supported-yet`. The suite's separate 1.2 `base_matrix.json` lane is **not run** here (reason in `docs/STATUS.md`). |

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
   shape) and enforces K3 (one `modelId` per snapshot, compared after NFC normalisation), K4
   (`featureId` percent-decodes to exactly one local gene row that is **root-level**), K5 (the
   record's reference shares a comparable form with the file's genome link and never contradicts it)
   and K6 (a `predecessor` claim resolves against the prior snapshots the caller supplied, and only
   those), warning per record. The order is the draft's own, and each step is stated because a
   verdict is only as good as the layer that produced it: B1 (carrier schema + exact-byte checksum)
   → K1 → **K2 for every record** → K3 → K4 → K5 → K6. Shape comes before identity because an
   identity the contract has not accepted is not an identity - a record with no `modelId` is a K2
   failure, never a K3 collision about the empty string. Root comes before nothing but its own
   evidence: `Parent=` is a present tag with an empty value list and a bare `Parent` is
   unreadable, so both are **INDETERMINATE** and K4 rejects rather than calling the row a root
   (`schemas/provenance.json` `drafts.FHGM.suitePinAnnotation.verdictPrecision`). And the contract's
   text rule is Unicode, so it is enforced on code points, not on `\s`: see `DraftTextPolicy`.
   What it is **not**: a released type (no released or registered `schemaVersion`, no w3id URL, no
   registry row - though the FHT draft's own bytes require a `schemaVersion: "1.0"` field, which is
   why the qualifier is part of the claim), a supported type, a feature-line validator, or a lineage
   resolver. A carrier whose FHGFF3 header or checksum is broken is refused at boundary B1 before
   any model verdict - the suite's own ordering, and the reason no FHGM pass can be read as an
   integrity statement.
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
   here). No released or registered `schemaVersion`, registry row, alias or
   support claim is invented for a draft — which is not the same statement as "no
   `schemaVersion` anywhere": the FHT draft contract requires a
   `schemaVersion: "1.0"` field inside an FHT header, and that field is part of the
   pinned draft bytes, not a release registration. Say "no released/registered FHT
   `schemaVersion`", because the bare form is false and a V7 sweep keyed on the bare
   form would flag its own documentation.
7. **Microdata `R14`/`R15` per-type divergence, `assess`, `--online` resolution,
   directive cross-checks (`#!genome-build`):** not implemented; see
   `docs/STATUS.md`. (Streaming is implemented: `verifyFile` reads a file once
   and holds only the bounded header block, as the toolkit's `SequenceScan`
   does.)

## 5. How to read a count in this repository

* Released-contract acceptance: 228 of 291 shared vectors run; 63 declared
  not-run with reason 1 above.
* Draft evidence, in the labelled `draft-vectors` job only (270 tests measured
  2026-11-18 on the pinned baseline: 82 FHT vectors + 82 lane-off controls + 62 FHGM
  vectors + 10 FHGM rule tests + 16 FHGM verdict-precision tests + 16 FHGM
  carrier-citation cells + 2 ledgers = 270),
  each suite's receipt naming the contract commit and digest and the suite commit
  and manifest digest it measured. Neither count is added to the 291, and neither
  is a support claim; the FHGM figure scores the widened K4 semantics this library
  implements but upstream has not settled, and 4 FHT vectors remain
  `candidate-unreviewed` at the suite's own marking.
* Every gap is a named row here with its reason. A missing capability is
  reported as missing; nothing is inferred from the library's class names, from
  the presence of a registry row, or from the reference toolkit's coverage.
