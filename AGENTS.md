# Agent instructions

## Independent review and merge authorization (2026-11-18)

The maintainer's independent-review rule (2026-11-18) applies only to
FAIR-bioHeaders repositories and agents operated by David Molik (`molikd`) or
Adam Wright (`adamjohnwright`). A PR submitted by either maintainer or their
agents may satisfy the approval gate through an independent **different model**:
Copilot, or a model operated by David or Adam. The approving model/agent must be
other than the submitter; an agent must not self-approve its PR, and another
instance of the same model does not satisfy the different-model requirement.
Record author/reviewer model provenance when needed to establish independence.
David and Adam themselves may self-approve their PRs; this exception does not
apply to their agents.

An eligible agent may merge only after that approval requirement is met, all
applicable CI checks are green, and its own validation of the current changes
passes, including changes after review. Recheck the exact head, base branch,
findings and dependencies immediately before merging. Missing checks are not
passing checks, and branch protection must not be bypassed. Read every overall
review and inline finding, reply with its disposition and fix/evidence, resolve
handled threads, and post an overall response. Merge authorization does not grant
contract adoption, curator acceptance, release, publication or deployment
authority; those retain their separate maintainer gates.

Authority: [approval by another, different model](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification/issues/268#issuecomment-6984337685)
and [human self-approval exception](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification/issues/268#issuecomment-6984418624).

Port source: FAIR-bioHeaders-Specification commit
`0d0d6b43efdee96bc7548f1f7c65fdafb2a31f32` ([specification PR #269](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification/pull/269)).

## Repository identity and ownership

This is **FAIR-bioHeaders-Tools-Java**, one of the four per-language library
repositories (`FAIR-bioHeaders-Tools-{R,JavaScript,Java,Julia}`, maintainer
decision of 2026-11-14, tracked in FAIR-bioHeaders-Specification#52). It
implements the same rules as the rest of the family against the contracts
published by [FAIR-bioHeaders-Specification](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification),
which remains the sole publication authority. The reference implementation —
including everything this library does not do (writing, conversion, stripping,
the full CLI) — is [FAIR-bioHeaders-Tools](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Tools)
(Python). Nothing in this repository can amend a contract, a format rule or a
version decision: those are made in the specification repository by the two
maintainers, David and Adam, who jointly hold schema and release authority
(GOVERNANCE.md in the specification repository; its optional steering group is
not active).

This file is the family's shared agent guidance **adapted for this tree**.
The family convention (softened 2026-11-14) keeps the shared sections of the
AGENTS.md files *in step* across holders — not byte-identical: a repository
may substitute absolute shared-specification URLs where relative paths do not
resolve in its own tree, and may carry repo-local sections. Do not build CI
that gates text identity, do not "fix" another repository's local section, and
record the source commit when porting guidance changes.

Do not invent identifiers, authors, provenance, SeqCol digests or scientific
relationships. Preserve supplied metadata and sequence bytes
([2024 FHR paper](https://doi.org/10.1093/bib/bbae122)).

## Schema additions and their own development cycle

A new optional field, structured alternative, enum value, constraint, required
field or changed meaning is a schema-contract change, even when motivated by a
UI, exporter, parser fix or documentation task. Identify it explicitly as a
**schema addition** or other contract change in its issue and PR. Supporting an
already adopted field in a consumer is implementation work, not a new field.

Schema additions have their own proposal, review, compatibility, versioning and
release cycle in FAIR-bioHeaders-Specification and the owning type repository.
A consumer like this library never introduces one; if a feature request here
would need a new field, link the upstream contract issue instead.

Use the shared specification's `docs/SCHEMA-VERSIONS.md` and
`schemas/versions.json`: contract versions are per type and distinct from
repository releases. Do not assign versions for implementing an existing
contract; this repository has no released contract version of its own and
`0.1.0-SNAPSHOT` is a development marker only. Never rewrite immutable
releases, fixed schema aliases or existing file-bound headers.

Check the adoption decision and released support before implementing a
proposal; an issue or PR's existence does not establish adoption. **FHT and
FHGM are drafts (rule V7): they have no released contract, no **released or
registered** `schemaVersion`, no w3id URL, no entry in the shared registry, and
no support claim — and a draft never takes a release form.** The qualification is
load-bearing, not pedantry: the FHT draft contract itself *requires* a
`schemaVersion: "1.0"` field inside every FHT header, so "no `schemaVersion`" read
bare is simply false about FHT bytes and makes a future V7 token sweep either cry
wolf at a required field or miss a real release form. What no draft has is a
`schemaVersion` **registered in the shared `schemas/versions.json`**, which is the
thing that would make a reader entitled to select it as a release.** This repository registers **two**
drafts as pinned rows of `schemas/bundled.json`, both readable only through the
explicit V7 opt-in and both warned on for every record read that way: `FHT` (the
commit and SHA-256 of FHT-Specification's `fht.json`, a file-bound header) and
`FHGM` (an `embedded-records` draft: two pinned files — the record contract and the
transport overlay — plus the extension key `x-fhgm-models` and the carrier type
FHGFF3, applied by `FhgmModels`). A records row that does not name all four
transport fields is refused at registry load. Neither registration is support. Registration is not adoption, support or release: with
the gate closed a draft citation is still rule V5 material, and the FHT vectors are
counted in their own labelled job, never inside the 291. Adding a draft row, or
widening what one does, is a deliberate change with its own review, pinned commit
and SHA-256, and a stated reason.

## Technical constraints of this tree

- The released contracts declare JSON Schema **draft/2020-12** (verified at
  the v0.6.0 tag and in every bundled copy). Validation must stay dialect-
  correct: networknt json-schema-validator with format assertions, and the
  version-gated `if`/`then` conditionals must actually fire (see
  `CapabilityTest`). Do not swap in a draft-07-capped validator.
- The header checksum is **SHA-512/256 over exact file bytes** minus the one
  checksum line and its terminator (FORMAT.md R1-R5). Use
  `MessageDigest.getInstance("SHA-512/256")`; never truncated SHA-512, never
  MD5, and compare digests as exact strings.
- YAML scalar typing must satisfy the R12 1.1/1.2-agreement rule as the
  reference toolkit resolves it — that is why `PortableYaml` carries its own
  resolver table. Changing YAML dependencies silently changes validity;
  re-run the `PortableYamlR12Test` table and the shared vectors after any bump.
- Bundled schema copies are frozen bytes of specification tags, recorded in
  `schemas/provenance.json` with SHA-256; refreshes must add new files for a
  new release, never overwrite frozen copies, and `CapabilityTest` re-verifies
  every digest.

## Verification

From the repository root:

```bash
./mvnw test                                   # all tests
./mvnw test -Dfh.spec.dir=/path/to/FAIR-bioHeaders-Specification   # incl. shared vectors
```

Acceptance is the shared conformance suite in FAIR-bioHeaders-Specification
(`conformance/manifest.json`, language-neutral by design) — **not** a new
per-language suite. Run every vector your slice honestly covers, name and
explain the vectors you cannot run, and never claim family parity from a
partial run (`docs/STATUS.md` records the current counts). Fetch the
specification checkout before relying on it; checkouts lag. For checksum or
parser changes, run the vectors per format and record environment and results;
for schema-bundle changes, re-verify bytes against the tags. Record checks
actually run; report failures verbatim; do not fabricate test counts.

## Licensing and historical material

New FAIR BioHeaders project contributions from March 2025 onward use MPL-2.0,
subject to the local LICENSE. This repository carries no historical USDA
public-domain material; the historical notice stays preserved in the
repositories that have it — do not import or restate it here. Do not label all
contributors as government employees (David Molik left USDA in February 2025),
and do not rewrite historical releases or silently relicense third-party
material; every Maven dependency keeps its own license (docs/DEPENDENCIES.md).

## Using FAIR-bioHeaders in another project

For assistants and people adding FAIR-bioHeaders headers to data elsewhere
(the authoritative summary is the shared
[`llms.txt`](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification/blob/main/llms.txt);
this repository's own [`llms.txt`](llms.txt) mirrors the Java-relevant facts):

- Use the `fair-bioheaders` tools (`pip install fair-bioheaders`): `bioheaders
  combine metadata.yaml genome.fa -o genome.fhr.fa` adds a header and computes
  the checksum; `bioheaders verify` checks it; `bioheaders strip` removes it.
  This Java library verifies, it never writes. Never write or edit a checksum
  by hand.
- Cite a versioned schema in `schema` (`https://w3id.org/fair-bioheaders/fhr/v0.6.0`,
  `.../fhp/v0.6.0`, `.../fhgff3/v0.6.0`) with its `schemaVersion` from the
  shared registry; required fields are in `fhr.json`, `fhp.json` and
  `fhgff3.json` — never invent values for them.
- Strict FASTA readers (samtools faidx and the rest of htslib, Picard,
  Biopython's default `fasta` format and others in `docs/TOOL_COMPATIBILITY.md`)
  reject a leading `;~` block: strip it first, or use Biopython's
  `fasta-pearson`; htslib-based tools can use an index from `bioheaders faidx`.
- FASTA header values with `>` or `@` are escaped as `\x3E` and `\x40`
  (FORMAT.md R11).
- Keep `llms.txt` current when a release changes schema versions, URLs or
  commands.

## Repo-local scope

This section applies only to FAIR-bioHeaders-Tools-Java.

- Module map: `HeaderScan` (R1-R7/R10/G1 bytes + checksum coverage),
  `PortableYaml` (R8/R12/R13 typing into Jackson `JsonNode`), `SchemaSelection`
  (V1-V6 + near-miss, and V7 when a draft row is opted in), `SchemaBundle`
  (bundled contracts + networknt validation + draft digest verification),
  `TypeRegistry`/`HeaderTypeDef` (header types as registry data), `Options`
  (the V7 opt-in knob), `DraftLog` (the `java.util.logging` channel for lane
  warnings), `UriSchemes`/`UriFields` (R14, field list derived from the contract
  being applied), `ParentLinks` (per-entry `derivedFrom` verdicts), `BioHeaders`
  (facade), `cli.Main` (verify/version; `Main.run` is testable in-process).
  Public API is `org.fairbioheaders.BioHeaders`, `VerifyResult`, `Issue`, `Kind`,
  `FhType`, `Options`, `ParentLinks`, `SchemaBundle.Draft`; everything else is
  internal.
- **Default behavior is pinned by a golden.**
  `src/test/resources/golden/default-behavior-v0.6.0.json` is the canonical
  fingerprint generated from commit `237da6c` (released-contract behavior: 228
  vector verdicts, 304 selection outcomes, 47 header documents, the release
  registry, container inference) and `DefaultBehaviorGoldenTest` requires it to
  reproduce byte-for-byte. A change that moves the fingerprint is allowed only
  as a labelled behavior change with the reason stated and the golden regenerated
  from the *predecessor* commit — never refresh the golden to make a test pass.
  `GoldenDumpTest -Dfh.golden.write=<path>` is the generator.
- Java 17 is the baseline; keep dependencies few and licensed per
  docs/DEPENDENCIES.md, and never add one without recording its license there.
- The vector tests locate the specification checkout via `-Dfh.spec.dir`,
  `FH_SPEC_DIR` or a sibling directory; without one they skip (never pass).
  CI clones the specification at a pinned tag and passes it explicitly.
- Draft evidence is a separate tagged test group: `./mvnw test
  -DexcludedGroups=draft -Dfh.spec.dir=<spec>` is released-contract acceptance
  (318 tests), and `./mvnw test -Dgroups=draft
  -Dfh.fht.dir=<FHT-Specification>/conformance
  -Dfh.fhgm.dir=<FHGM-Specification>/development/conformance` is rule V7 evidence
  (270 tests at the 2026-11-18 re-pin plus its review fixes: 82 FHT vectors, 82 lane-off controls,
  62 FHGM vectors, 10 rule tests, 2 ledgers). CI runs them as separate labelled jobs. The receipts
  (`target/default-behavior-golden.properties`,
  `target/fht-draft-vectors.properties`,
  `target/fhgm-draft-vectors.properties`) plus
  `.github/scripts/assert-reports.sh` keep a zero-test, missing-fixture or
  silent-skip outcome from reading as a pass. Never merge the two counts into one
  support number. Suites are re-pinned deliberately (digest first, count second);
  never automate a re-pin.
- Do not "upgrade" this slice into writing headers, microdata/JSON-LD or
  feature validation in a mixed PR — those are separately scoped issues;
  update `docs/STATUS.md` (implemented / not implemented / not verified) and the
  cell in `docs/CAPABILITY-MATRIX.md` with every behavior change. A matrix cell may
  only move to `yes` when a named test or vector set backs it; an unfillable cell
  stays published as not-supported-yet with its reason.
- Publishing releases, tags, Maven Central deployment, Zenodo records or
  GitHub Release objects is a separate maintainer action. Agents may merge PRs
  only under the independent-review authorization above.

## Issue, project and code-review behavior

### Issues and the connected project

- Add every issue you work on to the connected project (project #9, "FAIR
  BioHeaders — 2026 refresh") and set `Status` to `In Progress` when work starts.
- Keep `Status` current as the work moves (`Todo`, `In Progress`, `Blocked`,
  `Blocked - In Progress`, `Done`), and set `Outcome` when it closes.
- Link every PR to its issue (`Closes #N` or `Refs #N`). Do not do unreported work.
- After each work run (implementation, tests, review pass), post a status
  comment on the issue or PR: what changed, checks run and results,
  commit/PR links, blockers, and the next action. Do not rely on chat history.

### Code review on a PR

When a PR receives code review (human or model):

1. Read the overall review and every inline comment before changing anything.
2. Make each fix in a **new commit**. Do not amend or force-push over reviewed
   commits; if a rebase is required, cite the new head in the replies.
3. Reply to each inline finding with the fix and the relevant commit hash.
4. Resolve that inline comment/thread.
5. After all findings are handled, post an overall reply to the review
   summarizing each finding's disposition and the commits.
6. Treat optional suggestions as required unless they are genuinely out of
   scope; state why and link a follow-up issue.

Do not push directly to the default branch. PR merges follow the independent
review authorization above; adoption and publication keep their separate gates.
