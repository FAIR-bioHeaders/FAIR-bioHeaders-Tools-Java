# Status of this library (2026-11-15)

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
- **Two drafts are registered, and neither is support.** `FHT` is a draft row:
  `fht-draft.json` is FHT-Specification commit
  `86438f21c3a17a58334c97aed4955be1a2393522`'s `fht.json`, SHA-256
  `4156353aa6d077fee2d2c69be4e17859b8e4f81bb10824f3c87f6701de196e7a`, 34 396 bytes
  (round-13 coordinated re-pin, measured 2026-11-18 against a fresh clone at head
  `0311ddac`; the frozen copy is byte-identical to that commit's blob, and the
  commit recorded for a pinned file is its last-touch commit - the convention now
  unified across the four native libraries). Reading it requires the opt-in, and it
  is a draft in every sense rule V7 uses: no `schemaVersion` in the shared registry,
  no w3id URL, no release, no support claim, and its vectors are counted separately
  ("Draft evidence" below). With the gate closed - which is the default, and what
  the golden pins - the citation still falls to rule V5 exactly as before this row
  existed. `FHGM` is registered as the second draft row - an `embedded-records`
  draft whose two pinned files (record contract and transport overlay) each carry
  their own commit and digest; see `docs/CAPABILITY-MATRIX.md` §4.

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

**Labelled changes.** The golden's bytes stay as `237da6c` wrote them. A deliberate
behavior change is recorded in `src/test/resources/golden/labelled-changes.json`: one
entry per moved fingerprint entry, with the golden entry it replaces (`from`), the
entry the code now produces (`to`) and the reason. `DefaultBehaviorGoldenTest` applies
the list to the golden before comparing, and fails when an entry's `from` no longer
matches the golden, so the list cannot go stale and each change is a reviewable diff.
When the comparison fails, the test writes `target/golden-suggested-changes.json` as a
starting point for that review.

**Newer specification checkouts.** The golden's `vectors` section was recorded
against the v0.6.0 manifest (SHA-256 `cdbb79b1…`). Against those bytes all 228 are
compared, as CI does. Against any other manifest - for example the specification's
`main`, which lists new vectors and has retired others - the vectors the two share are
compared by id and the receipt names the manifest digest; vectors the golden has
never seen are covered by `FileVectorTest` and `AcceptanceLedgerTest`, which read the
manifest. `./mvnw test -Dfh.spec.dir=<specification main>` therefore runs green without
editing a count.

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

Plus 90 unit/integration/CLI JUnit tests in the released-contract job: **318 test
executions, 0 failures, 0 skipped** as recorded on 2026-11-16; the released job has
since grown to 351 executions locally (measured 2026-11-18 on this branch, JDK
17.0.21.0.3) — that doc/CI-literal lag is tracked as its own change (Tools-Java#14),
not folded into a re-pin. (JDK 17.0.21.0.3 and 27.0.1.0.3 locally,
Temurin 17 and 21 in CI on ubuntu-latest and macos-latest). The draft job adds 270
more (82 FHT draft vectors, 82 lane-off controls, 62 FHGM draft vectors, 10 FHGM
rule tests, 16 FHGM verdict-precision tests, 16 FHGM carrier-citation cells, 18
second-round review cases and 2 ledgers) for **639 measured in total on the pinned
baseline** (351 + 288), on JDK
17.0.21.0.3. **Baseline matters when quoting these numbers**: the same tree run
against FAIR-bioHeaders-Specification's live `main` instead of the pinned `v0.6.0`
tag measures 607 tests with 7 failures (five shared-suite additions plus two
ledger/golden totals) — that is not a defect in the draft lane, it is what pinning a
build to a moving branch does, and it is the direct argument for re-pinning these
drafts to release tags the moment `v0.7.0` and `v0.8.0` exist.
**What actually enforces these counts is the report guard, not Maven.** `mvn test` exits
0 when draft vector tests are *skipped*: `FhtDraftVectorTest`/`FhgmDraftVectorTest` turn a
missing or drifted checkout into an `assumeTrue(false)` dynamic test, and Surefire records
that as a skip, not a failure. Measured on this head against the superseded checkouts
(FHT `be9bcc82`, FHGM `57118c52`): `./mvnw -B -ntp test -Dgroups=draft` printed
`Tests run: 136, Failures: 0, Errors: 0, Skipped: 2` and **BUILD SUCCESS**, exit 0, while
`sh .github/scripts/assert-reports.sh 288` rejected those same reports with
`expected zero skipped tests: a skipped vector or suite is a missing fixture, not a pass`
and exit 1, naming both `FhgmDraftVectorTest.xml` and `FhtDraftVectorTest.xml`. So a green Maven exit is never
acceptance evidence for this repository — the CI step that gates is the report guard plus
the receipt greps, and the receipt's own `vectorsRan=0` / `note=drift` line is what says
nothing was scored.

The earlier PR #1 figure was 258 (30 unit plus 228 vectors). The 63 not-run vectors are every `microdata-*` and `jsonld-*`
vector listed in `ConformanceReportTest` output; they exercise the HTML
microdata (rule M1/M3, `itemtype`/`itemprop` token lists, typed values) and
JSON-LD (rules J1/J7, context handling) bindings, which this slice does not
implement. **Do not read the 228/228 as family parity**: parity claims belong
to the full vector suite plus the released toolkit's behavior, and 63 vectors
are outside this library's current scope.

How a vector counts as passed (see `ConformanceHarness`): expected-valid files
must return `VALID`, with the manifest's `checksum` recomputed exactly, every
manifest `metadata` value read back (numbers compared by value), and the
manifest-listed `R14` warning present; expected-invalid files must return
`INVALID` (or `UNSUPPORTED_VERSION` where the manifest's `result` says
`unsupported-version`, per rule V4) **with an error for the rule the manifest
names**. Rules are matched by label, except where the evidence is necessarily
another layer's (`ConformanceHarness.evidenceFor`): contract rules (P1, P2, G2,
G3, R15, and selection rules V1-V3, V5, V6) show as `schema` errors; R1 and R4
(which bytes are hashed, how the digest is written) show as an R3 mismatch or an
R5 value fault; and a `#~` line after `##FASTA` (G1's block boundary) is the R10
late line.

The ledger's 291 / 228 / 63 are the v0.6.0 baseline and are asserted only
against that manifest's bytes; against another manifest the ledger reads the
counts from the manifest and still requires every implemented-format vector to
run and pass and every other vector to carry a declared reason.

### Draft evidence, kept separate (rule V7)

FHT has no released contract, so its results never join the table above. They run
in their own labelled `draft-vectors` CI job against the FHT-Specification suite
at the pinned revision, and the job asserts the receipt rather than a green tick:

| suite | vectors | ran | passed | identity of what was measured |
| --- | --- | --- | --- | --- |
| FHT development suite (`conformance/manifest.json`, format `fht-fasta`) | 82 | 82 | **82** | contract `fht.json` commit `86438f21c3a17a58334c97aed4955be1a2393522`, SHA-256 `4156353aa6d077fee2d2c69be4e17859b8e4f81bb10824f3c87f6701de196e7a` (34 396 B; the contract bytes MOVED at this re-pin — previous `59ad9641…`, 29 430 B, at `226f923c`); suite commit `86438f21c3a17a58334c97aed4955be1a2393522`, manifest SHA-256 `e5a4a5d027259e29a7e3cfb42da5e1ef07fa78d4a0e604ef73f28b8e5a59a508` (76 939 B; previously suite `be9bcc82…` / manifest `9fd8c0b2…` / 72 vectors, and before that `24d3b033…` / 66) |
| FHGM draft suite (`development/conformance/manifest.json`, format `fhgff3` with `x-fhgm-models`) | 62 | 62 | **62** | record contract `development/model/fhgm-model.schema.json` commit `06176262f9824f26d974a15d8feeebde1fbeb0a8`, SHA-256 `b7075e5872e0a5ffce5ae61c590ce182f1f219fa269413cad3b1a966753cfeae` (11 522 B; MOVED at this re-pin — previous `bf1553ca…`, 9 564 B, at `ac3d37b3`); transport overlay `development/model/fhgm-draft.schema.json` commit `019bc29e32ce225791053dd2ce323bb91f304ce1`, SHA-256 `533b3120f4a86590f1b60a2175487597c99b646b3d092b7818b1f682c6cecf9b` (3 191 B; previously `d1619cce…`, 2 613 B, at `5d15ccc1`, and before that `dd9f6927…`, 1 363 B); suite commit `06176262f9824f26d974a15d8feeebde1fbeb0a8`, manifest SHA-256 `e41f05fd8bb8b8ec175b0192b80832041c84e15c325876aa66a86ba6e59413ce` (69 526 B; previously `57118c52…` / `0d279015…` / 43 vectors) |

**Draft-suite re-pin, 2026-11-15 (previous era).** Both suite records advanced; both contract
records stayed because their bytes did not move (re-measured from the development
repositories' content-addressed blobs, not from raw URLs). On that revision the FHGM
record-contract pin and the suite pin parted ways (`ac3d37b3` vs `57118c52`). The 12 new FHGM vectors were the promoted
real-data `dmel-*` cases (RefSeq `GCF_000001215.4` chr4 fixtures); the commit that
sealed them says their expectations require independent review, and the 4 new FHT
parent-link cases carry the suite's own `promotion: candidate-unreviewed` marker.
Running them here is draft-lane evidence, never acceptance of that review. FHGM's
separate 1.2 matrix lane (`development/conformance/base_matrix.json`, identity-rewritten
twins observed against FHGFF3 1.2) is **not run** by this job: the sealed manifest is
what this library pins and executes; the matrix lane would double-count the same
expectations under rewritten file identity, so it stays not-run with that reason.

**Round-13 coordinated re-pin, 2026-11-18: the CONTRACT bytes moved.** For the first
time every draft artifact advanced together — FHT contract `59ad9641` → `4156353a`
(29 430 → 34 396 B, FHT-Specification #54), FHT suite 72 → 82 (0 removed; C1 24 → 31,
C2 3 → 4, C3 10 → 10), FHGM record contract `bf1553ca` → `b7075e58` (9 564 → 11 522 B)
and overlay `d1619cce` → `533b3120` (2 613 → 3 191 B, FHGM #26/#29), FHGM suite 43 → 62
(0 removed; 17 valid / 45 invalid; K4 occurrences 8 → 11). A suite-only re-pin is
impossible: measured on this jar, the FHT suite's `fht-fasta-molecule-type-mixed`
(expected valid) is REJECTED by the previous contract (the `MoleculeType` enum grew
4 → 5 with `mixed`), the previous contract ACCEPTS `fht-fasta-blank-version-separator`
(expected invalid), and the FHGM suite's `fhr-specification-alias-v0.6.0` and
`v0.6.0-citation-stating-1.1` (both expected valid) are rejected by the previous
overlay (seven-carrier enum; v0.6.0 paired const `1.2` only). So contract, overlay,
manifest and the jar's frozen copies are re-pinned atomically, and the implementation
moved with them where the widened bytes demand it: `FhgmModels` now applies K3 identity
**before** any per-record rule and after NFC normalisation (the two new K3 vectors),
and K4 requires the targeted gene row to be **root-level** — a gene carrying a
`Parent` attribute no longer resolves (`feature-target-gene-with-parent`, a semantics
change inside FHGM#29, not a new rule label). The draft-lane reading of FHT files
moved to the streaming `verifyFile` path so a truncated gzip member arrives as the
reader's own R1 INVALID (`fht-fasta-gzip-truncated`) instead of crashing an eager
decompress — a control that runs red if the R1 handling regresses.
Commit convention unified this pass across the four native libraries: each pinned
file records its OWN last-touch commit, never a floating default-branch head; every
recorded commit was re-verified byte-identical to its digest by `git show`, and the
advisory freshness job compares digests against live main regardless of the label.

**Review fixes on the same head (2026-11-18, after the independent review of
`91cafb7`).** The re-pin's implementation changes were correct in direction and wrong
in three specifics, each one the same class of defect — a verdict asserted from
something the reader had not resolved:

1. **K4's root condition does not accept an unreadable `Parent`.** A gene row written
   `ID=toy-a;Parent=` — a *present* tag with an empty value list under the GFF3
   attribute grammar, which is a different assertion from the tag being absent — and a
   bare `ID=toy-a;Parent` (no `=` at all) both resolved as root, because the parser
   skipped a token with no `=` and required a non-empty value to call a row nested.
   `Gff3FeatureIndex` now answers with three states (`ABSENT` / `NESTED` /
   `INDETERMINATE`, merged strongest-wins when several rows carry one ID), and K4
   resolves only from `ABSENT`. An indeterminate row **rejects** under K4; it is not
   silently upgraded to a root and not counted as a pass. Reject rather than abstain is
   a deliberate choice, stated here because abstention in this lane is exactly the
   unresolved upstream question (§4 and the limitations below): this library already
   scores K4 natively, so the honest options were assert-too-much or refuse-the-record,
   and refusing is the one that cannot mislead a caller. Key matching stays exact-as-written,
   the way the draft's own index does it, so ` Parent` with a leading space is still
   a different tag.
2. **K2 (record shape) runs before K3 (identity), over the whole array.** The re-pin had
   put K3 first, so two records that were both *missing* `modelId` produced
   `K3: Ambiguous model ID ''` — an identity fabricated from an absent field, and a
   finding that points at the wrong rule. `validate_fhgm.py` validates every record's
   shape before it collects normalised IDs, and this reader now keeps that order. The
   full sequence, with what each step may report first:

   | step | this library | the draft's reference checker |
   | --- | --- | --- |
   | base schema + exact-byte checksum | B1, on the carrier, before any model verdict | `base.validate`, then `checksum` |
   | transport overlay | K1 | `transport.validate` |
   | **every record's shape** | **K2, whole-array first pass** | **K2, whole-array first pass** |
   | identity uniqueness, after NFC | K3, before any per-record rule | K3, before any per-record rule |
   | **read the feature layer** | **after K3**: `featureIndex(data)` runs only once both passes are clean | `gene_targets(data)` is called after the K3 loop |
   | gene-target resolution (root row) | K4, per record | K4, per record |
   | reference agreement with the file link | K5, per record | K5, per record |
   | revision-pointer verdicts | K6, after the snapshot clears | K6, after the snapshot clears |

   The K3-before-K4/K5 precedence the re-pin introduced is kept — that is what
   `duplicate-model-id-reported-before-record-checks` pins — and `FhgmVerdictPrecisionTest`
   now holds both halves: a real collision still outranks a per-record finding, and a
   record with nothing valid to compare never reaches the comparison at all. Enumerating
   the order was the fix: the K3 inversion had also been able to mask any other K2
   defect (unknown key, non-string field, nested-shape error) behind an ambiguity
   report, not just the missing-`modelId` case.
3. **`$defs.text` is enforced in Unicode terms, in the draft lane only.** The pinned
   record contract states the rule as "no leading or trailing whitespace (Unicode
   whitespace included)" and forbids C0/DEL/C1/zero-width/bidi characters anywhere
   (`fhgm-model-draft.json` lines 155-161) — but writes it as `^\S` and `\s$`, which
   the JSON Schema evaluator here hands to `java.util.regex` *without*
   `UNICODE_CHARACTER_CLASS`. ASCII `\s` is `[ \t\n\x0B\f\r]`, so a `modelId` of
   `"model-a\u00A0"`, or of `"\u00A0"` alone, satisfied the pattern and passed: the
   contract's own hidden-character rule, defeated by an engine default, in the model
   layer this library exposes to callers. `DraftTextPolicy` now applies the constraint
   over the code points **the contract itself enumerates** — the set
   `$defs.link` spells out at line 166 (`\t-\r`, space, `\x1c-\x1f`, `\x85`,
   `\xa0`, `\u1680`, `\u2000-\u200a`, `\u2028`, `\u2029`, `\u202f`, `\u205f`,
   `\u3000`, `\ufeff`) plus the invisible class of `$defs.text`'s own `not` pattern —
   and — after the second review round corrected the first attempt — it is applied
   to **the properties the contract actually types as `$defs.text`, and to no other
   string**. The scope is derived from the pinned schema by `DraftTextPolicy.textPaths`,
   which follows `$ref` and `allOf` and returns the fourteen sites
   (`modelId`, `modelVersion`, `featureId`, `reuseConditions`, `predecessor.modelId`,
   `predecessor.modelVersion`, `contributors[].name`, `contributors[].role`,
   `evidence[].description`, `methods.software[].name`, `methods.software[].version`,
   `methods.limitations[]`, `publication.deposit[]` — a `$defs/curie`, whose
   `allOf[0]` IS `$defs.text` — and `reference.genomeReference.assemblyName`), rather
   than a field list kept by hand. A test asserts that set exactly, so widening or
   narrowing the contract widens or narrows the check on its own.

   **The first version of this fix got the scope wrong, and the correction matters.**
   It walked every string in the record, which applied `$defs.text`'s invisible-character
   ban to `publication.article` — a property whose schema carries only the DOI pattern
   `^10\.[0-9]+(\.[0-9]+)*/\S+$` and no reference to `$defs.text`. The result was that
   `10.1234/ab\u200dcd` was **rejected**, a false invalidity, which the
   [#264 ruling](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification/issues/264)
   weighs as heavily as a false pass. It is now a positive control: that DOI is accepted
   (`aNonTextFieldKeepsItsOwnConstraint`), the same character in `modelId` is still
   rejected (`aTextFieldStillRejectsTheSameCharacter`), and both directions are asserted
   in one record so the two cannot drift apart again (`theSameCharacterIsAcceptedInOneFieldAndRejectedInAnother`).
   The distinction being drawn is the contract's own — *which properties carry which
   definition* — not a judgement about how strict to be. Internal ordinary whitespace in a
   text field stays legal, exactly as the contract leaves it.

   **ZWNJ and ZWJ: the frozen FHGM schema governs, and an earlier instruction here was
   wrong.** A cross-family FHT reading was quoted into this work as licensing ZWJ/ZWNJ
   to stay legal. It does not, and nothing in this library implements that: `$defs.text`'s
   own `not` pattern at **line 160** of the pinned `fhgm-model-draft.json` lists
   `\u200b-\u200f` explicitly, which covers U+200C (ZWNJ) and U+200D (ZWJ), so both reject
   in every `$defs.text` field — `zeroWidthJoinersAreRejectedInATextField` pins it, and
   the rejection is the *schema's own*, because the range is written as explicit code
   points rather than a class, so it survives any engine. The schema was **not** edited
   (digest still `b7075e58…`, `cmp` clean). The cross-family question is real but belongs
   to the owner: it is raised in
   [FHGM-Specification#32](https://github.com/FAIR-bioHeaders/FHGM-Specification/issues/32),
   which is also where the `\s`-vs-prose blind spot in the FHGM suite is recorded.

   The fix is draft-scoped on purpose. Editing the frozen bytes to carry an explicit
   class would move a digest this jar quotes and re-pins deliberately; the portable
   pattern is owning-repository work in `FHGM-Specification` (with the ±fixtures a
   schema change needs), and until it lands, the sentence wins over the engine.

   **Named, unpatched, untested in the FHT lane — and the count, corrected.** The FHT
   draft contract also writes `\S`. Counted on the pinned bytes (`4156353a…`) there are
   **five pattern occurrences containing `\s`/`\S`, and they are NOT five affected
   constraints** — an earlier paragraph in this file said "five sites", which was wrong:

   | occurrences | pattern | whitespace-class dependent? |
   | --- | --- | --- |
   | **3** | the `(?![\s\S])` end-anchor idiom (`sequencingRuns` at root and in `$defs`, `Sample.tissueIdentifier`) | **no.** `[\s\S]` means "any character" whichever way `\s` is defined, so the assertion is end-of-input in every engine. |
   | **2** | `^10\.[0-9]+(\.[0-9]+)*/\S+$` — one distinct DOI pattern, vendored twice (root `properties.scholarlyArticle` and `$defs/FHT/…`) | **yes.** One real dependency, not five. |

   And that one is not hypothetical, measured on this build against the toolkit's engine:
   a sealed, otherwise-valid FHT header whose `scholarlyArticle` ends in **U+00A0**
   reports **VALID** here, while `jsonschema` 4.26.0 on CPython 3.12.15 — the stack the
   reference toolkit validates with — reports it **INVALID**
   (`'10.1234/abc\xa0' does not match '^10\.[0-9]+(\.[0-9]+)*/\S+$'`). The ASCII-space
   DOI fails in both, and a ZWJ-inside-DOI value passes in both, so the divergence is
   exactly the non-ASCII-whitespace edge. **Java's draft-lane FHT reading therefore
   differs from the toolkit's on that input, and a green `82/82` is not evidence the case
   was ever checked** — no FHT vector pads a DOI with non-ASCII whitespace. It predates
   this re-pin (the superseded `59ad9641` carries the same pattern), it is **not** fixed
   here, because the pattern is `FHT-Specification`'s to change and a blanket local
   override is precisely the over-reach the preceding paragraph is about. Filed as
   [Tools-Java#16](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Tools-Java/issues/16)
   with the measurements and the vector pair it asks for.


**A `62/62` here is not a `62/62` over there.** The JavaScript library's own re-pin PR
(JS#22) reports **58 pass / 4 fail against these same 62 FHGM vectors**, because that lane
declined to implement the widened expectations and left them failing rather than abstaining
on them; this lane implements them, which is precisely why it can print `62/62`. The two
numbers are therefore **not equivalent outcomes** and must never be presented side by side as
if they were: Java's `62/62` rests on a local decision to adopt FHGM#29's semantics and to
reject where the attribute is unreadable, while Julia's `51 pass / 11 not-run` rests on the
opposite local decision to abstain on K4 entirely. Three libraries, three different
relationships to the same contested draft semantics, one shared pinned byte set. (The JS
figures are quoted from that PR's own body, not re-measured here; JS's tee-piped draft job can
also surface a vector failure in its artefact without reddening the step, which is that lane's
problem to solve and is noted in #14's territory, not changed by this PR.)

**Second review round on `4afdf50a`: four more blockers, all fixed, all red-first.**
The re-review of the first fix round (same independent reviewer, `COMMENTED`, "I would
not approve this head") found four defects, two of them produced by instructions in the
producer's own brief rather than by the code's intent, and each is the same failure mode
this cycle keeps meeting — a verdict asserted from input that was not faithfully read, or
a constraint asserted where the contract did not place one:

1. **K4 resolved a target out of a decoding failure.** A sealed carrier with one gene row
   `ID=%FF` and a record naming `featureId: "\ufffd"` returned **PASS**. `%FF` is not the
   UTF-8 encoding of anything; `new String(bytes, UTF_8)` never fails, it *substitutes*
   U+FFFD, and the substituted value then matched the record. Identity is now the decoded
   **bytes** (`ByteBuffer`-keyed, the same comparison the draft's checker makes: index by
   `unquote_to_bytes`, look up by `feature_id.encode()`), so `%FF` matches no text ID and
   K4 does not resolve. **Decision: REJECT under K4 — matching the oracle exactly** (the
   checker raises `must target one local gene row` when no row's bytes equal the ID's), and
   the finding keeps the two causes apart: a row ID that no text can name is counted and
   reported as such, so "absent" and "unreadable" are not collapsed into one message.
   Controls, all in `FhgmRoundTwoReviewTest`: `%FF` vs `"\ufffd"` rejects; the *actual*
   UTF-8 bytes `%EF%BF%BD` vs `"\ufffd"` **passes**; `%FF%FE%FF` vs `"\ufffd\ufffd"`
   rejects; `toy-%C3%A9` vs `"toy-\u00e9"` passes and the decomposed spelling does not
   alias it; the suite's own percent pair (`toy%2Da`) still resolves only when decoded.
2. **The feature layer was read too early, which is part of the ordering, not a detail of
   it.** `featureIndex(data)` sat above the whole-array K2 and K3 passes, so a row that is
   not nine columns raised first and reported **K4** over a record that was missing a
   required field (must be **K2**) and over a genuine duplicate identity (must be **K3**).
   The index is now built after both, as the checker builds it; `malformedRowsCannotMaskK2`
   and `malformedRowsCannotMaskK3` pin it, and `malformedRowStillRejectsWhenRecordsAreValid`
   keeps the K4 verdict for a file whose only fault is the row. The per-record K5-before-K4
   interleaving is unchanged and matches upstream (it is not a global K4 pass).
   **One ordering difference is kept deliberately, and now has a name and a test:** this
   library settles the *carrier* — released base schema and the exact-byte checksum, B1 —
   **before** K1, where `validate_snapshot` runs `base.validate` → `transport.validate` →
   checksum. So for a file that is both checksum-stale and transport-inadmissible, Java
   reports **B1** and never reaches the overlay, where upstream would name the transport
   fault first. It is deliberate: the draft lane is layered on the released reading and
   must never run model rules over a carrier the released reader refuses, which is what V7
   requires of it. It changes only *which* boundary a doubly-broken file is reported at, not
   whether it is rejected. `carrierIntegrityIsSettledBeforeK1` asserts both halves —
   an intact-but-inadmissible carrier reports K1 alone, and the same carrier with one
   tampered row reports B1 alone — so the divergence is pinned rather than remembered.
3. **The text policy over-reached** onto `publication.article`, which is not a
   `$defs/text` site, and rejected a DOI containing ZWJ. Scope is now derived from the
   schema (the fourteen `$ref` sites, followed through `$ref`/`allOf`), asserted as an
   exact set, and proved in both directions on one record. See the correction under
   defect 3 of the previous block.
4. **ZWNJ/ZWJ** stay rejected in `$defs.text` fields because the frozen schema lists
   `\u200b-\u200f` explicitly at line 160; no frozen byte was edited and the cross-family
   question is raised upstream in FHGM-Specification#32 instead of being settled here. See
   the retraction under defect 3 above.

**Red-first / green, measured.** On a pristine `git archive` of `4afdf50a` with only the new
test file added, `./mvnw -o test -Dgroups=draft -Dtest=FhgmRoundTwoReviewTest` printed
`Tests run: 17, Failures: 6` — `aPercentDecodingFailureDoesNotMatchTheReplacementCharacter`,
`anUndecodableRowIdIsReportedApartFromAnAbsentOne`, `malformedRowsCannotMaskK2`,
`malformedRowsCannotMaskK3`, `aNonTextFieldKeepsItsOwnConstraint` and
`theSameCharacterIsAcceptedInOneFieldAndRejectedInAnother`. The other eleven cases were
already green there and stay green: they are controls, and `zeroWidthJoinersAreRejectedInATextField`
among them is the measured evidence for blocker 4 — the frozen schema already refused those
code points, so defect 4 was a claim and a scope question, not a behaviour this library had
broken. At this head the same class is `17 run / 0 red`, `DraftTextScopeTest` is `1/1`, and
the whole draft group is `288 run / 0 red`.

**And a disclosure that was missing from the first fix round**: the FHT-lane `\S`
dependency is now stated in full, with its corrected count (one pattern, two sites, not
five) and with the measured difference between this library and the toolkit's engine, as
[Tools-Java#16](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Tools-Java/issues/16).

**What the pinned suites do NOT cover, measured rather than assumed.** Each fix was
reverted on its own and the whole draft group re-run against the pinned baseline. Every
revert turned red exactly its own unit cases and nothing else: dropping the indeterminate
`Parent` branch, or reading an empty `Parent=` as absent, reddens 2 cases; putting K3 back
before K2 reddens 2; removing the Unicode text policy reddens 5. In all four reverts the
FHT lane stayed `82 ran / 82 passed`, the FHGM lane stayed `62 ran / 62 passed`, the
16-cell carrier matrix stayed green and `assert-reports` saw no skips. That is the finding,
not a comfort: **the 62 FHGM vectors contain no case for an empty or malformed `Parent`,
no case for a record whose `modelId` is absent, and no case for a text value padded with a
non-ASCII space** - a green draft job cannot see any of these three, which is why they
arrived by review rather than by CI. They are pinned here as unit cases, and they are
vector-shaped gaps worth reporting upstream to `FHGM-Specification` (this PR does not
create that issue, and does not claim the suite covers them).

**Two limitations no digest diff or vector count reveals**, carried here because the
numbers above cannot state them: (1) the pinned FHGM bytes include FHGM#29's widening
of `layers.model` — a `pass` now also asserts trimmed invisible-character-free text,
RFC 3986 links with a real host, non-blank deposits, NFC-unique identifiers and a
root-level gene target; a pass under these bytes is NOT the same claim as a pass under
the 2026-11-15 bytes. (2) The K4 question is an **open upstream decision**, not a
settled one: FHGM#22 (does `layers.model` presuppose feature rows?) with FHGM#27/#28
and FAIR-bioHeaders-Specification#241 remain unresolved, the owning repository still
describes the layers as orthogonal while its layer-semantics proposal sits unanswered,
and JS#11 documents the failure shapes any resolution must answer (a zero-row feature
layer reported as `reject` where the validator says `absent-layer`; an empty `ID=`
making K4 an unfalsifiable `pass`). Java scores K4 natively, which is exactly why this
statement matters here: this lane's 62/62 is an observation about a CONTESTED K4
semantics, and unlike Julia — which declares K4 `not-supported-yet` and runs
51 pass / 11 not-run — it cannot abstain. Also unchanged: 4 of the 82 FHT vectors are
still `candidate-unreviewed` promotion cases, and the promoted `dmel-*` expectations
still await independent review upstream.

Suites are pinned **by digest first, count second**: the FHGM manifest passed through
36 001 bytes at `5d15ccc1` with its vector count still 31, and the 2026-11-18 FHGM
manifest grew to 62 vectors while removing none — a count-only assertion would have
called the earlier step no drift at all.

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
82 files with the default reader and requires each to be **rejected** as FHR (or as
a broken stream, R1) with
no `V7` warning — 82/82. Without it, a passing draft suite would not distinguish
the lane from the released reader.

`FHGM` is the second registered draft row, an `embedded-records` type: it is not a
header type (its records ride inside an FHGFF3 header under `x-fhgm-models`, and the
file stays routed by its own released FHGFF3 citation), and its suite's model-level
rules K1–K6 are ported natively by `FhgmModels`, which reads the record contract and
the transport overlay pinned in this jar and refuses any carrier that fails the
released FHGFF3 base schema or its checksum (boundary B1) before a model verdict
exists. What it does not get here: a released form, a support claim, the suite's
optional `gff3` feature layer (skipped by design, `gff3-validator`'s subject), or the
1.2 matrix lane (not run, see the re-pin note above).

### FHGM draft records (K1–K6), and what a pass means

FHGM is not a file-bound header type. Its records ride inside an FHGFF3 header
under one R15 extension key, `x-fhgm-models`, and the file stays routed by its own
released FHGFF3 citation under V1–V3; rule V7 selects only the contract and the
validator applied to the records. `FhgmModels` is that validator, ported natively
from the draft's model-level rules:

| rule | what this library checks | where it is proven |
| --- | --- | --- |
| B1 | the carrier file must satisfy the released FHGFF3 base schema **and** its exact-byte checksum before any model verdict exists | the suite's `transport-1-0-extension` and `tampered-record-bytes` vectors, and `FhgmModelsTest.aBrokenCarrierIsBoundaryB1NotAModelVerdict` |
| K1 | the pinned transport overlay: a released FHGFF3 identity selected by its cited schema URL (v0.5.0 with `1.1`, or v0.6.0 with `1.1` or `1.2`, FHGM#26) plus a non-empty `x-fhgm-models` array | `empty-model-array` + `k1RequiresTheTransportOverlay…` |
| K2 | each record against the pinned record contract (four required fields, closed nested objects, opaque non-empty strings; portable link-host and non-blank patterns since the 2026-11-18 bytes) | `record-unknown-key`, `record-without-reference`, `record-evidence-uri-*`, `record-model-id-trailing-space` + `k2IsThePinnedRecordContract…` |
| K3 | one `modelId` per snapshot, compared after NFC normalisation and before any per-record rule; a collision is reported as an unselectable identity and rejects the snapshot | `ambiguous-model-id-in-snapshot`, `duplicate-model-id-reported-before-record-checks`, `model-ids-equal-under-nfc`, `pointer-into-ambiguous-history` + `k3RejectsTwoRecordsSharingOneModelId…` |
| K4 | `featureId`, percent-decoded over raw bytes, resolves to exactly one local **root-level** `gene` (or `SO:0000704`) row — since the 2026-11-18 bytes, a gene carrying a `Parent` attribute does not resolve | `feature-target-absent`, `-duplicate-rows`, `-transcript-row`, `-gene-with-parent`, `percent-decoded-feature-target` + `k4RequiresExactlyOneLocalGeneRow` |
| K5 | the record's reference shares at least one comparable form with the file's genome link and never contradicts it | `reference-forms-not-comparable`, `record-reference-contradicts-file`, `both-reference-forms`, `file-linked-by-fhr-parent` + `k5RequiresOneComparableFormAndForbidsContradiction` |
| K6 | a `predecessor` claim resolves against the prior snapshots the caller supplied, and only those (own model ID named, a different recorded version, matching assembly link and record reference, no recorded successor skipped) | `revision-resolves-supplied-history`, `predecessor-*`, `gap-*`, `stale-pointer-absent-history`, `revision-without-supplied-history`, `contract-rich-revision-chain`, `history-with-unresolved-own-pointer` + `k6ResolvesAPointerOnlyAgainstSuppliedHistory` |

Per record read through this lane the result carries a `V7` warning naming the
record contract's commit and digest and the extension key, and the same statement
goes to the `org.fairbioheaders.draft` logger. The `gff3` layer of the suite is
reported **skipped** — feature-line validation belongs to `gff3-validator`, and the
suite itself records that no FHGM rule depends on that layer.

Runner controls, so the 31/31 was not a vacuous agreement (and the 43/43 after the
2026-11-15 re-pin, and the 62/62 after the 2026-11-18 re-pin, ran the same controls): changing one byte of a vector fixture
inside checksum coverage made that vector fail (31 ran, 1 failure,
build red); zeroing one recorded fixture digest in the manifest made the whole
suite report **not run** through the pinned-manifest drift check (1 skipped test,
never a pass). Both were reverted, and the earlier "control" that appeared to pass
was found to be a no-op edit — the file it claimed to tamper with was byte-identical
to the original, which is why `cmp` is now run against the control before any
conclusion is drawn from it. The 2026-11-15 re-pin PR repeated the discipline on
its own changes: (1) with the old suite pins still in place, pointing both lanes at
the live (advanced) checkouts produced `vectorsRan=0`, a drift line naming the live
and pinned manifest digests in both receipts, and `assert-reports.sh` exit 1 on the
resulting skips; (2) appending one space to the shipped `fhgm-transport-draft.json`
(a change `cmp` confirmed was really present) made `DraftProvenanceTest` fail on the
digest and `SchemaBundle` throw `IllegalStateException("…refusing to read drafts
that do not match their own provenance")` in all 10 `FhgmModelsTest` cases; the file
was then restored and `cmp`-verified byte-identical before the green re-run. The
2026-11-18 re-pin repeated the discipline again: with the OLD pins still in the jar,
pointing the draft job at the live (advanced, 82- and 62-vector) checkouts produced
`vectorsRan=0` in both receipts and a `note=drift` line naming live and pinned
digests plus `re-pin deliberately` (FHT, contract side) and the pinned-vs-live
manifest digests (FHGM, suite side), with the build red; the coupling itself was
demonstrated vector by vector on the CLI - the same `fht-fasta-molecule-type-mixed`
bytes are `INVALID` under the previous contract and `checksum verified; metadata
valid` under the pinned one, `fht-fasta-blank-version-separator` is `metadata valid`
(a lie) under the previous contract and rejected under the pinned one, and the two
overlay-dependent valid FHGM vectors (`fhr-specification-alias-v0.6.0`,
`v0.6.0-citation-stating-1.1`) are `REJECT [K1]` against the previous overlay bytes
and `PASS` against the pinned ones - so no partial pin of this revision could have
been green without the byte moves it hides.

## What this library does

- R1 byte model (LF/CRLF/lone CR, no normalization) incl. gzip and multi-member
  bgzip decompression for the checksum (NUL padding after the last member is
  skipped, as Python's `gzip` does; corrupt or truncated gzip is an `R1` error);
  R7 BOM rejection (FASTA/GFA).
- Streaming (`HeaderScan`, `Gunzip`): `verifyFile` reads the file once, holds only
  the header block (the toolkit's 1 MiB / 10 000-line bounds, applied as streaming
  bounds) and hashes everything else on the way through, keeping a digest copy from
  just before the candidate checksum line as the toolkit's `SequenceScan` does.
  Memory use is independent of file size (`StreamingScanTest` verifies a generated
  stream larger than any Java array). `readAll` and `verify(byte[])` remain for
  in-memory use; `readAll` refuses more than 1 GiB decompressed.
- Container inference follows the toolkit's extension table
  (`.fa .fasta .fas .fna .faa .ffn .frn .fsa .mpfa` FASTA, `.gfa` GFA, `.gff3 .gff`
  GFF3, after one trailing `.gz` or `.bgz`); any other name is refused as an
  unsupported extension (labelled golden changes `kind-unsupported-*`).
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
- **No FHGM *support*, and no FHGM released form.** FHGM is not a header type - its
  records ride inside an FHGFF3 header under `x-fhgm-models` - and what exists here
  is the rule V7 lane: both contract files pinned by commit and digest
  (record contract `b7075e58…`, 11 522 B, at `06176262`; transport overlay
  `533b3120…`, 3 191 B, at its last-touch `019bc29e`, re-verified against
  `FHGM-Specification` main on 2026-11-18), the K1–K6 checks ported by `FhgmModels`,
  and the suite's
  recorded expectations executed in the labelled draft job. A `layers.model` `pass`
  here means the **widened** FHGM#29 assertion - trimmed invisible-character-free
  text, real-host RFC 3986 links, non-blank deposits, NFC-unique identifiers, a
  root-level gene target - and the K4 abstention question (FHGM#22/#27/#28,
  FAIR-bioHeaders-Specification#241, JS#11) is an open upstream decision this lane
  participates in by scoring, not resolving; see the re-pin note above. What this library does
  **not** do with that: give FHGM a `schemaVersion`, a w3id URL, a registry row, a
  release tag or a support claim; run the suite's optional `gff3` feature layer
  (skipped by design); or run the suite's separate 1.2-base matrix lane
  (`base_matrix.json`) - only the sealed `manifest.json` is executed, and the matrix
  lane is reported not-run with this reason. The 12 `dmel-*` vectors promoted on
  2026-11-15 still await independent review of their expectations upstream; their
  passing here is lane evidence about the pinned revision, nothing more.
- No release, tag, Maven Central publication or Zenodo record exists for this
  repository; version `0.1.0-SNAPSHOT` is a development marker only.
- Non-string YAML mapping keys are rejected outright (the reference loader's
  JSON round-trip of such keys is out of scope; no shared vector covers it).
- Directive cross-checking (`#!genome-build` etc. compared with metadata) is
  not implemented.

## Fixed divergences from the reference toolkit (2026-11-18)

Each is a labelled change against the golden (`golden/labelled-changes.json`) or
covered by `HeaderChecksTest`:

- **Near-miss wording (Java#2).** An `fht` near-miss `schema` URL now reads `read as
  FHR, not FHT, ...` as the toolkit words it; it used to say `not FHGFF3` because the
  old type lookup fell through to FHGFF3. Message only.
- **The excluded line is the record's checksum (R2).** The line the checksum excludes
  must parse alone as `{checksum: <value>}` and that value must equal the parsed
  record's `checksum`, as the toolkit's `SequenceScan.checksum` requires. A
  `checksum:` line hidden inside a quoted or flow scalar, with the real key spelled
  `"checksum"` elsewhere, used to verify; it is now an R2 error.
- **Containers.** A header type is accepted only in the containers its registry row
  names (`containers` in `schemas/bundled.json`: FHR in FASTA/GFA, FHP in FASTA,
  FHGFF3 in GFF3, FHT in FASTA), as the toolkit's `validate_header_family`; anything
  else is a `container` error, e.g. `FHP metadata is incompatible with GFA; expected
  FHR metadata`.
- **Plain `=` (R12).** PyYAML resolves a plain `=` to YAML 1.1's value tag, not a
  string, so it is now an R12 disagreement; quoted `'='` is fine.
- **R14.** Whitespace anywhere in a link (including NBSP and the other Unicode
  spaces) means no accepted scheme, not only at the ends; and the FHR field table now
  includes `metadataAuthor[].uri` and `assemblyAuthor[].uri`, which the contract
  declares `format: uri` and the toolkit checks.

## Draft pin currency (re-pinned 2026-11-18, round 13)

The 2026-11-18 morning record of this section said the FHT pin was behind
`main` — `fht.json` had moved after `226f923c` in FHT-Specification #50 (`f908fec`)
and #51 (`dc82725`), with the suite manifest moving too — and that a re-pin was a
deliberate change of its own. This PR is that deliberate change, executed against a
fresh clone. `main`'s contract bytes went on moving after that note was written —
`7ea83956…` at `dc82725`, the then-live `91a890e2…` at `f908fec`, `2c437f60…` at
`91fdc1f` (re-measured from each commit's blob, 2026-11-18) — before settling: this
jar now pins `4156353a…` at last-touch commit `86438f21` (#54), with the 82-vector
suite at `e5a4a5d0…` from the same commit; the FHGM record contract, overlay and
suite advanced with it (`06176262` / `019bc29e`, above). Every
FHT reading from this build is evidence about commit `86438f21` only. The advisory
`draft-pin-freshness` job continues to compare digests against live raw main and
nothing automates a re-pin from it.

## Not verified (open questions, honestly listed)

- The suite passed on this host with Homebrew OpenJDK **17.0.21.0.3** and
  **27.0.1.0.3** (macOS/aarch64, Maven 3.9.11) and in CI with Temurin **17** and
  **21** on ubuntu-latest, with the specification pinned at tag `v0.6.0` and the
  FHT suite pinned at `86438f2` (the pre-registry run was 43618810450; the 2026-11-18
  re-pin evidence above was produced locally on this machine's JDK 17.0.21.0.3 and
  the detached suite checkouts, and the CI runs of this very commit remain to be
  observed). The report
  guard (`.github/scripts/assert-reports.sh`) fails on zero tests, a class that
  executed nothing, or any skip; the receipts pin the vector counts. Platforms/JDKs
  beyond these are untested.
- The FHT draft reading is verified only where the FHT suite reaches: FASTA
  containers, the pinned `fht.json` revision, and the 82 vectors' rules. Nothing
  about FHT in a GFA container, FHT microdata or FHT JSON-LD, or any other draft
  revision, is implemented or tested here. The 4 parent-link vectors added on
  2026-11-15 still carry the suite's own `promotion: candidate-unreviewed` marker at
  this revision: they
  exercise this reader, but their expectations await review by a second reader
  upstream (FHT-Specification#8), and nothing here pre-empts that review.
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
