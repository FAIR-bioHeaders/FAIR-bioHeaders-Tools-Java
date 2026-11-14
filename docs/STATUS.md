# Status of this library (2026-11-14)

Vertical slice: **read + checksum-verify + schema-validate** embedded
FAIR-bioHeaders headers in FASTA (FHR/FHP), GFA (FHR) and GFF3 (FHGFF3) files,
as a Java 17 library (`org.fairbioheaders.BioHeaders`) and a small
`verify` CLI. Nothing here is a release: version `0.1.0-SNAPSHOT`, no Maven
Central publication, no tags.

## Registry and API shape (issue #2, PR #4)

Header types are **registry data**, not reader branches. `schemas/bundled.json`
carries a `types` array; `TypeRegistry` turns each row into a `HeaderTypeDef`
(URL key for rules V1-V3, file key, `released`/`draft` status, `embedded-header`
or `embedded-records` transport, and the rule R14 `format: uri` field list), and
`SchemaSelection` builds its V1-V3 patterns from the released rows instead of
hard-coding them. `FhType` is now a value class (with `status()` and
`transport()`) rather than a three-constant enum; the released constants
`FhType.FHR`, `FhType.FHP` and `FhType.FHGFF3` are unchanged in name and meaning,
and `equals`/`hashCode`/`toString` keep the old call sites working.

Consequences that matter for the five-type goal:

- A draft type becomes readable by **adding a registry row plus its pinned
  contract bytes** — no change to `HeaderScan`, `PortableYaml`, `SchemaBundle`'s
  validation path or `BioHeaders`. `src/test/resources/org/fairbioheaders/testtypes/`
  is a second registry that declares two types the library has never heard of
  (`PRT`, a draft file-bound header; `PRM`, a draft carried as records inside
  another type's header, the FHGM shape) and `TypeRegistryTest`/`DraftLaneTest`
  read records through it, which is the check that the claim is true and not
  aspirational.
- A *released* type still needs a reviewed `FhType` constant: a registry row that
  claims `released` without one fails at construction, because a released type is
  public API and a resource file cannot mint public API. Promotion of FHT or FHGM
  to released is a shared-specification publication, then a deliberate code change.
- The rule V7 opt-in is `org.fairbioheaders.Options`
  (`Options.builder().enableDraftContracts(true)`, or `.enableDraft("FHT")`), read
  from the `org.fairbioheaders.experimental.drafts` system property or the
  `FAIR_BIOHEADERS_EXPERIMENTAL_DRAFTS` environment variable — the native form of
  the reference toolkit's `BIOHEADERS_EXPERIMENTAL_FHT`. With the lane on, the
  pinned bytes' SHA-256 is re-computed when the bundle is built, and a mismatch
  throws instead of reading a draft that does not match its own provenance
  (rule V7's "says so instead of quietly choosing a newer draft").
- **One draft is registered, and it is not support.** `FHT` is a draft row:
  `fht-draft.json` is FHT-Specification commit
  `226f923c28b680edb3c546e99a751482d06aef1a`'s `fht.json`, SHA-256
  `59ad9641adcb0e5591045c2d894c066d1adc9984cca84c5ff6502f8dad88487e`, 29 430 bytes
  (re-verified against the live repository on 2026-11-14: the commit's blob and
  `origin/main`'s blob are the same bytes). Reading it requires the opt-in, and it
  is a draft in every sense rule V7 uses: no `schemaVersion` in the shared registry,
  no w3id URL, no release, no support claim, and its vectors are counted separately
  ("Draft evidence" below). With the gate closed - which is the default, and what
  the golden pins - the citation still falls to rule V5 exactly as before this row
  existed. `FHGM` registers nothing here; see `docs/CAPABILITY-MATRIX.md` §4.

## Default behaviour is byte-identical to the pre-registry code

`src/test/resources/golden/default-behavior-v0.6.0.json` — 192 799 bytes, SHA-256
`2c7b2a02f6e3f4e8cd67c488895c0ce7d5761ac4a2f6185b754b2f92f083beaa` — is a canonical
fingerprint of the released-contract behavior **generated from commit `237da6c`**, the
merged PR #1 head, by `GoldenDumpTest` run in a checkout of that commit (specification
pinned at tag `v0.6.0`, JDK 17.0.21.0.3). It records:

| fingerprint section | entries | what it pins |
| --- | --- | --- |
| `bundle` | 7 releases x 3 types | the release registry, current copies, `has`/`releasesWith` answers |
| `selection` | 304 | V1-V6 outcomes (type@release plus any warning text) for 38 schema-URL forms x 8 `schemaVersion` values |
| `kinds` | 18 | container inference from file names |
| `documents` | 47 | status, stated/computed checksum, parsed metadata and full ordered issue list for 47 header byte-documents (5 with real hashlib-computed checksums, tamper and CR/CRLF variants, and the malformed/edge battery) |
| `vectors` | 228 | verdict and pass/fail of every embedded-header shared conformance vector |

`DefaultBehaviorGoldenTest` recomputes it and compares the canonical bytes section by
section. Current result: **equal, all sections, 228/228 vectors compared and passed**.
The generator is deterministic (two runs in the pre-refactor tree produced the same
bytes) and the test carries its own positive control (it fails if no document reads
VALID, or if the battery or vector counts shrink). The negative control was run while
writing it: dropping `relatedLink[]` from the R14 field table moved the first
difference to line 2091 of the fingerprint, so the gate bites rather than agreeing by
accident. The golden is generated once by the old code and is not regenerated by the
new code; changing it is a deliberate, labelled behavior change, never a refresh.

## E0 capability study (the gate) — results on this host

Environment: Homebrew OpenJDK **17.0.21.0.3** (build 17.0.21.0.3+0, aarch64,
macOS), Apache Maven **3.9.11**, run 2026-11-14. A Maven probe project was
built and its tests executed before any scaffolding or validator code was
written (the same assertions now live permanently as `CapabilityTest`,
`ChecksumTest` and the vector tests).

1. **Draft/2020-12 validation — yes.**
   `com.networknt:json-schema-validator` **3.0.9** (with
   `tools.jackson.core:jackson-databind` 3.1.8 /
   `tools.jackson.dataformat:jackson-dataformat-yaml` 3.1.8 /
   `com.ethlo.time:itu` 1.17.0 / `org.slf4j:slf4j-api` 2.0.20) loads the
   released contracts `fhr.json`, `fhp.json`, `fhgff3.json` — verified at the
   `v0.6.0` tag (`git show v0.6.0:<file>`; all three declare
   `"$schema": "https://json-schema.org/draft/2020-12/schema"`; the older
   bundled copies, including FHR 1's `fhr-1.json` from tag `v0.3.1`, declare
   it as well, verified from the bundled bytes). It evaluates the keyword
   patterns those contracts actually use — local `$ref: #/$defs/...`, `allOf`
   with `if`/`then` conditionals (the version-gated extension-key
   `propertyNames` rule and the `completeness.lineageDataset` BUSCO pattern),
   `contains`, `const`, `patternProperties`, `additionalProperties: false`,
   and the `format: date`/`format: uri` keywords (format assertions enabled).
   Positive controls: the released `examples/example.fhr.json`,
   `examples/fhp/*.json`, `examples/fhgff3/minimal-reference.fhgff3.json` all
   validate; negative controls: unknown keys, bad `dateReleased`, and
   `Primates_Odb10!` as `lineageDataset` are each rejected, the last by the
   fired `if`/`then` pattern.
2. **SHA-512/256 — yes, byte-identical to the Python reference.**
   The JDK provides `MessageDigest.getInstance("SHA-512/256")` (Java 9+) and
   it is the real algorithm, not truncated SHA-512 (asserted in
   `ChecksumTest`). Equivalence chain for the same 32-byte digest of the
   released conformance vector `conformance/valid/fasta-lf.fhr.fasta`
   (coverage = every byte except the checksum line and its terminator,
   FORMAT.md R1/R3/R4):
   - `hashlib.new("sha512_256")` (Python 3.15, converter-0.11.0 venv):
     `Z1p/siczUlN/N+lDB5Kg6vFRrztGErgLw9H4MfVfZeU=`
   - released toolkit `bioheaders.cli.checksum(data, 'fasta')`,
     fair-bioheaders **0.11.0** from PyPI: identical string
   - the manifest's recorded `checksum` for the vector: identical
   - this Java reader after R3 exclusion: identical (asserted against the
     manifest in the `fasta-lf` vector test, which passes)
   - anchor values also pinned from hashlib: SHA-512/256("abc") =
     `UwSOJoGUHvmbLim3a0x9q+TC0MY0/G1G4OLxMQfnryM=`, SHA-512/256("") =
     `xnK40e9W7Sirh8NiLFEUBpvdOte4+XN0mNDAHs7wlno=`
   Note: `examples/example.fhr.fasta` carries a synthetic checksum that does
   **not** match its own bytes (FORMAT.md: synthetic examples are for
   structure only) — which is why the proof uses the conformance vectors, the
   toolkit's own computed digest and the manifest.
3. **YAML parsing dependency and dialect.** Headers are parsed with the
   `org.yaml:snakeyaml` 2.7 **parser** only; scalar typing (R12) is decided by
   this library's own YAML-1.1-vs-1.2 agreement resolver, transcribed from
   PyYAML 6.0.3's resolver table and the toolkit's `portable_plain_tag()`.
   Equivalence is pinned by `PortableYamlR12Test`: a 57-row table of plain
   scalars whose expected tag-or-rejection outcomes were generated on
   2026-11-14 by calling the released toolkit's own
   `bioheaders.portable_plain_tag`. This matters because Jackson 3's YAML
   format (and snakeyaml-engine) resolve with YAML 1.2 and would classify the
   four `*-yaml-*` vectors differently from the PyYAML-based reference.

## Shared conformance vectors

Acceptance is FAIR-bioHeaders-Specification's shared suite
(`conformance/manifest.json`, 291 vectors; counts read from the manifest),
never a new per-language suite. This checkout ran the suite against the
specification working tree at `a4278c4`, whose `conformance/manifest.json`
blob is byte-identical to the one at tag `v0.6.0` (`6a8c33d…`); all nine
bundled schema files were extracted from the release tags themselves.
(`origin/main`'s manifest also lists 291 vectors with identical per-format
counts — verified 2026-11-14.)

| format | vectors | run | passed | failed |
| --- | --- | --- | --- | --- |
| `fasta` | 98 | 98 | **98** | 0 |
| `gfa` | 56 | 56 | **56** | 0 |
| `fhp-fasta` | 36 | 36 | **36** | 0 |
| `fhgff3` | 38 | 38 | **38** | 0 |
| `microdata` | 26 | 0 | — | not implemented |
| `jsonld` | 37 | 0 | — | not implemented |
| **total** | **291** | **228** | **228** | 0 |

Plus 76 unit/integration/CLI JUnit tests in the released-contract job: **304 test
executions, 0 failures, 0 skipped** (JDK 17.0.21.0.3 and 27.0.1.0.3 locally,
Temurin 17 and 21 in CI). The draft job adds 133 more (66 FHT draft vectors, 66
lane-off controls, 1 ledger) for **437 in total**, with the same counts on JDK 17
and 21. The earlier PR #1 figure was 258 (30 unit plus 228 vectors). The 63 not-run vectors are every `microdata-*` and `jsonld-*`
vector listed in `ConformanceReportTest` output; they exercise the HTML
microdata (rule M1/M3, `itemtype`/`itemprop` token lists, typed values) and
JSON-LD (rules J1/J7, context handling) bindings, which this slice does not
implement. **Do not read the 228/228 as family parity**: parity claims belong
to the full vector suite plus the released toolkit's behavior, and 63 vectors
are outside this library's current scope.

How a vector counts as passed (see `ConformanceHarness`): expected-valid files
must return `VALID`, with the manifest's `checksum` recomputed exactly and the
manifest-listed `R14` warning present; expected-invalid files must return
`INVALID` (or `UNSUPPORTED_VERSION` where the manifest's `result` says
`unsupported-version`, per rule V4).

### Draft evidence, kept separate (rule V7)

FHT has no released contract, so its results never join the table above. They run
in their own labelled `draft-vectors` CI job against the FHT-Specification suite
at the pinned revision, and the job asserts the receipt rather than a green tick:

| suite | vectors | ran | passed | identity of what was measured |
| --- | --- | --- | --- | --- |
| FHT development suite (`conformance/manifest.json`, format `fht-fasta`) | 66 | 66 | **66** | contract `fht.json` commit `226f923c28b680edb3c546e99a751482d06aef1a`, SHA-256 `59ad9641adcb0e5591045c2d894c066d1adc9984cca84c5ff6502f8dad88487e`; suite commit `24d3b033ab951ef79ee9f6d17419d8ba700f1b0a`, manifest SHA-256 `9b07179497bdc7247d6ead2404d9d5fdee48308a58b385bd4cd9a7e91450053a` |

`DraftHarness` re-digests the checkout's `fht.json` **and** its `manifest.json`
against the registry pin before running anything; on a mismatch every vector is
reported **not run** with the drift message, never as a pass. (That path was
exercised for real while writing this: a stale extraction at the contract commit,
whose manifest predated the `parent-mixed-verdicts` vector, produced
`vectorsRan=0` and a drift line instead of 66 green tests.)

Per vector it enforces the manifest's own model: verdict; recomputed SHA-512/256;
`selfChecksum` (`matches` must not report `R3`; `broken` must report `R3`;
`unreachable` must report the `R2`/`R5`/`R10` line-shape family and not `R3` — the
suite's anti-accident rule); the `warnings` list; the named `rule`; the
`parentLink` verdicts through `ParentLinks`; that `result: invalid-as-fhr` stays a
rule V5 reading even with the lane open; and that every record routed to the draft
carries the `V7` warning naming the commit and digest (and that no other record
does).

The differential control runs in the same job: `FhtLaneOffControlTest` reads all
66 files with the default reader and requires each to be **rejected** as FHR with
no `V7` warning — 66/66. Without it, a passing draft suite would not distinguish
the lane from the released reader.

`FHGM` registers nothing in this build: it is not a header type (its records ride
inside an FHGFF3 header under `x-fhgm-models`), and its suite's verdicts depend on
a model-level checker (rules K1–K6) this library does not have. The boundary and
the reason are row 2 of `docs/CAPABILITY-MATRIX.md` §4; no partial FHGM claim is
made.

## What this library does

- R1 byte model (LF/CRLF/lone CR, no normalization) incl. gzip and multi-member
  bgzip decompression for the checksum; R7 BOM rejection (FASTA/GFA).
- R2/R5 exactly-one root-level checksum line, root-indent and quoting rules.
- R3/R4 SHA-512/256 coverage over exact bytes with canonical padded base64.
- R6 (UTF-8 header lines, U+0085/U+2028/U+2029 rejection), R10 (leading header
  block, no late `;~`/`#~` lines), G1 (GFF3 line 1, block boundary, `##FASTA`).
- R8 (duplicate keys, anchors, aliases, merge keys), R12 (portable plain
  scalars, tags rejected), R13 (finite numbers only).
- R11 warning (raw `>`/`@` in FASTA header lines); R14 warning (link schemes,
  field list derived from the `format: uri` leaves of the three current
  contracts); toolkit-side near-miss schema-URL warning (FAIR-bioHeaders-Tools#125).
- V1–V6 schema selection with frozen bundled copies of every release that
  registered a type (v0.3.0–v0.6.0), provenance and sha256 recorded
  (`schemas/provenance.json`; every copy verified byte-identical to the
  released fair-bioheaders 0.11.0 bundle on 2026-11-14).
- Draft/2020-12 validation with format assertions.
- V7 draft lane (opt-in): the pinned FHT draft contract, a `V7` warning naming the
  commit and digest on every record read through it, the same statement on the
  `org.fairbioheaders.draft` `java.util.logging` logger, and a bundle that refuses
  to build when the shipped draft bytes no longer match their recorded digest.
- Parent-link verdicts (`ParentLinks`, and `verify --parent` on the CLI):
  `CHECKED_PASS` / `CHECKED_FAIL` / `UNCHECKED` per `derivedFrom` entry, with a
  failing entry reported as an error and an unsupplied parent never called verified.
- Container registry: header types are data (`schemas/bundled.json` `types`), so a
  type joins with a row plus pinned bytes; `UriFields` reads the R14 field list off
  the contract being applied.

## What this library does NOT implement or claim

- **No writing.** No combine/strip/convert/faidx/verify-links/assess; no header
  writer, so no checksum *generation* — use the Python toolkit for that.
  Never hand-edit a checksum.
- **No microdata or JSON-LD bindings** (their 63 vectors are not run).
- **No GFF3 feature validation.** FHGFF3 vectors pass on header, placement,
  link-cardinality and checksum grounds only; feature-line syntax/SO/biology
  belongs to gff3-validator.
- **No FHT or FHGM *support*.** Neither has a released contract (rule V7), a
  `schemaVersion` row in the shared registry, a w3id URL or a release tag, and
  nothing in this repository gives them one. What FHT has here is a **pinned
  opt-in reading lane**: with `--experimental-drafts` (or the `Options`/property/env
  equivalents) the reader validates a record against FHT-Specification's pinned
  draft bytes and says so on every record. That is development-preview behavior,
  labelled as such in the result, in the log, in the CI job name and in this file -
  not a claim that FHT is supported, and not enabled by default: with the gate
  closed the citation is rule V5 material, byte-identically to before the row
  existed.
- **FHGM: nothing at all.** No pinned bytes, no registry row, no reader, no vectors
  run. FHGM is not a header type - its records ride inside an FHGFF3 header under
  `x-fhgm-models` - and its acceptance suite asserts model-level rules K1–K6
  (ID uniqueness, `featureId` resolution against the GFF3 feature layer,
  reference/assembly agreement with the file's genome link, and predecessor-pointer
  resolution across supplied snapshots). This library reads none of that, so the
  whole lane is declared absent rather than half-ported: evaluating only the record
  schema would pass the suite's valid vectors while skipping the rules the suite
  exists to test. Registered candidate bytes for a later change:
  `development/model/fhgm-model.schema.json` at commit
  `ac3d37b3033312844fc3151217731928ae33aa54`, SHA-256
  `bf1553cac04dc88d8afaff1d39bc50360342e1ebedac4b73dfe3dff570ff410f` (9 564 bytes),
  and the transport overlay `development/model/fhgm-draft.schema.json`, SHA-256
  `dd9f69274f26860bbfd71d0e8b2794420c5369fb5d40ce838fb6a9e6d5116e3d` (1 363 bytes),
  both re-verified against `FHGM-Specification` main on 2026-11-14.
- No release, tag, Maven Central publication or Zenodo record exists for this
  repository; version `0.1.0-SNAPSHOT` is a development marker only.
- Non-string YAML mapping keys are rejected outright (the reference loader's
  JSON round-trip of such keys is out of scope; no shared vector covers it).
- Directive cross-checking (`#!genome-build` etc. compared with metadata),
  the toolkit's `MAX_*` streaming architecture, and its 1 MiB/10 000-line
  limits are ported as whole-buffer checks, not as streaming bounds; the
  limits match the toolkit values but memory use is O(file).

## Known wording divergence from the reference toolkit (open, deliberately unfixed here)

For a near-miss `schema` URL naming `fht` — for example
`https://w3id.org/fair-bioheaders/fht/v0.7.0` or an `FHT-Specification` raw tag URL —
this reader says `read as FHR, not FHGFF3, against the current schema (rule V5)` while
the reference toolkit says `not FHT`. The cause is historical: `SchemaSelection.typeOfUrlKey`
mapped every name that was not `fhp` to `FhType.FHGFF3` through its default branch, and the
warning was built from that mapping. The behavior is *message-only* — the type, release,
validity, exit status and every vector outcome are unchanged, and rule V5 reads the record as
FHR either way — but it is wrong wording about a real type.

It is **not** corrected in the registry refactor, because that PR's claim is that default
behavior is byte-identical and the corrected string is inside the default-behavior golden
(`selection` section, the `fht` URL rows). Correcting it is a small, separately labelled
change that updates the golden with a stated reason; until then the golden pins the existing
text so it cannot change by accident.

## Not verified (open questions, honestly listed)

- The suite passed on this host with Homebrew OpenJDK **17.0.21.0.3** and
  **27.0.1.0.3** (macOS/aarch64, Maven 3.9.11) and in CI with Temurin **17** and
  **21** on ubuntu-latest, with the specification pinned at tag `v0.6.0` and the
  FHT suite pinned at `24d3b03` (the pre-registry run was 43618810450). The report
  guard (`.github/scripts/assert-reports.sh`) fails on zero tests, a class that
  executed nothing, or any skip; the receipts pin the vector counts. Platforms/JDKs
  beyond these are untested.
- The FHT draft reading is verified only where the FHT suite reaches: FASTA
  containers, the pinned `fht.json` revision, and the 66 vectors' rules. Nothing
  about FHT in a GFA container, FHT microdata or FHT JSON-LD, or any other draft
  revision, is implemented or tested here.
- Behavior on huge files (multi-GB bgzip) and on adversarially crafted YAML is
  bounded by buffer-in-memory design and the ported size limits, but was not
  load-tested.
- `fhr-1.json` (FHR 1)'s full V6 compatibility path is covered only by the
  `fasta-schema-*` vectors that exist; no additional legacy corpus was tested.
- The R14 field table is derived from the three *current* contracts; records
  read against older bundled contracts use the same table (their uri-format
  fields are a subset; no drift was found, but the derivation is not per-release).
- No fuzzing, no formal conformance to the JSON Schema test suite; the
  capability claims are scoped to the keyword patterns the released contracts
  use, as exercised above.
