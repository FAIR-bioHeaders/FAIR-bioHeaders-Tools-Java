# Native acceptance

Status date: **2026-11-15** (draft-suite re-pin; guard counts and pinned suite
refs moved with it). This file is the acceptance statement for
FAIR-bioHeaders-Tools-Java#3: what the primary job runs, how to reproduce it
exactly, which guards can make it fail, and what "native" means here as a checked
property rather than an intention.

## The rule this satisfies

The family requirement (FAIR-bioHeaders-Specification#211, round 10) is that a
language library be native to its language end to end, and that **the primary
acceptance job run with no Python and no foreign-language toolkit dependency**.
Reference-implementation differentials are supplemental evidence; they are never
the oracle. In this repository:

* the implementation is Java (`src/main/java`);
* the shared-conformance runner is Java
  (`org.fairbioheaders.conformance.ConformanceHarness`, `DraftHarness`);
* the assertions are JUnit 5 on Maven Surefire;
* the acceptance inputs are the JDK, Maven, this repository, and a **read-only**
  checkout of the specification's language-neutral data
  (`conformance/manifest.json` plus byte-exact vector files). Nothing else.

The Python differentials recorded in `docs/STATUS.md` (the SHA-512/256
equivalence chain, the R12 `portable_plain_tag` table) were each produced **once,
outside CI**, by calling the released toolkit; the values they produced are
committed as expectations here, so no CI run depends on them, and no CI job
installs or invokes Python. That is what makes them supplemental rather than
load-bearing.

## Exact commands

Everything below runs from the repository root. The Maven wrapper is pinned to
Apache Maven 3.9.11; Java 17 is the baseline.

```bash
# 1. Released-contract acceptance (the primary job). No draft tests.
git -C .. clone https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification spec-v0.6.0
git -C spec-v0.6.0 checkout v0.6.0
./mvnw -B -ntp clean test -DexcludedGroups=draft -Dfh.spec.dir=$PWD/../spec-v0.6.0

# 2. Rule V7 draft evidence (its own labelled job, never merged into 1's counts).
git -C .. clone https://github.com/FAIR-bioHeaders/FHT-Specification fht-pin
git -C fht-pin checkout 86438f21c3a17a58334c97aed4955be1a2393522
git -C .. clone https://github.com/FAIR-bioHeaders/FHGM-Specification fhgm-pin
git -C fhgm-pin checkout 06176262f9824f26d974a15d8feeebde1fbeb0a8
./mvnw -B -ntp test -Dgroups=draft \
  -Dfh.fht.dir=$PWD/../fht-pin/conformance \
  -Dfh.fhgm.dir=$PWD/../fhgm-pin/development/conformance

# 3. Everything (local default: the draft tests skip visibly if no FHT checkout exists).
./mvnw -B -ntp test -Dfh.spec.dir=/path/to/FAIR-bioHeaders-Specification

# 4. The reports guard, exactly as CI runs it.
sh .github/scripts/assert-reports.sh 318          # released job
sh .github/scripts/assert-reports.sh 288 target/surefire-reports   # draft job
#   Maven alone is NOT the enforcement: a drifted checkout yields skips and BUILD SUCCESS.
#   This guard is the step that rejects skips, alongside the receipt greps in build.yml.
sh .github/scripts/ci-guards-dryrun.sh            # local dry run of the CI shell guards
```

Discovery conventions: `-Dfh.spec.dir` → `FH_SPEC_DIR` → a sibling
`../FAIR-bioHeaders-Specification`; `-Dfh.fht.dir` → `FH_FHT_DIR` → a sibling
`../FHT-Specification/conformance`; `-Dfh.fhgm.dir` → `FH_FHGM_DIR` → a sibling
`../FHGM-Specification/development/conformance` (or `../FHGM/…`). A **named**
directory that does not hold the suite is a hard error — it never falls through to
a sibling, because case-insensitive filesystems once made that rescue a broken
path (`SpecSource`, `SpecSourceTest`). With no checkout, the vector tests are
reported as skipped and the receipts record zero compared - a missing fixture is
never a pass, and the CI report guard fails on any skip.

Artifacts land in `target/`: `surefire-reports/`,
`default-behavior-golden.properties`, `acceptance-ledger.json`,
`acceptance-ledger.properties`, `fht-draft-vectors.properties` and
`fhgm-draft-vectors.properties`. CI uploads them
per job and per JDK with `if-no-files-found: error`.

## Runtime and operating-system matrix

| dimension | supported / tested | how it is known |
| --- | --- | --- |
| Java language level | 17 (`maven.compiler.release=17`) | the POM; the json-schema-validator 3.x line targets Java 17 |
| JDKs in CI | Temurin **17** and **21**, both jobs | `.github/workflows/build.yml` matrix |
| JDK beyond CI | Homebrew OpenJDK **27.0.1.0.3** locally, green | recorded in `docs/STATUS.md`; not a CI guarantee |
| OS in CI | `ubuntu-latest` (the primary acceptance target) | CI runs |
| OS locally | macOS/aarch64 (Homebrew OpenJDK 17, 27) | `docs/STATUS.md` |
| OS not tested | Windows; other Linux arches; JDK 8/11 (below baseline) | named here rather than left to inference |

Checksums, digests and byte handling use only `java.base` APIs
(`MessageDigest("SHA-512/256")` since Java 9, `java.util.zip`, `java.nio`), so
nothing in the acceptance path is platform-specific; but a platform that is not
run is reported as not tested, not as supported.

## Guards: what makes the build fail

| guard | where | fails when |
| --- | --- | --- |
| vector count | `build.yml` + surefire XML | `FileVectorTest` executes fewer than 228 vectors |
| default-behavior receipt | `build.yml` | `target/default-behavior-golden.properties` is missing, or `vectorsCompared≠228`, `documentsCompared≠47`, `selectionCasesCompared≠304`, `kindsCompared≠18` |
| golden equality | `DefaultBehaviorGoldenTest` | the recomputed fingerprint differs by even one byte from the golden generated from commit `237da6c` |
| acceptance ledger | `AcceptanceLedgerTest` + `build.yml` | the shared manifest is not 291 vectors, the run set is not 228, anything fails, or a format is not run without a declared reason |
| report guard | `.github/scripts/assert-reports.sh` | no report directory, no `TEST-*.xml`, a class that executed 0 tests, any skip, any failure/error, or an aggregate below the job's bound (318 released, 238 draft) |
| draft receipts | `draft-vectors` job | FHT ran/passed ≠ 82, FHGM ran/passed ≠ 62, a recorded contract, transport-overlay or suite pin that differs from the registry's, or a missing `gff3Layer=skipped` statement in the FHGM receipt |
| draft byte identity | `DraftHarness`, `FhgmHarness`, `DraftProvenanceTest` | a checkout whose `fht.json`, `fhgm-model.schema.json`, `fhgm-draft.schema.json` or suite manifest differs from the registry pins, or a shipped copy that differs from its own recorded SHA-256 |
| native purity | `NativePurityTest` | any source uses `ProcessBuilder`, `Runtime.getRuntime`, `javax.script`/`ScriptEngine`, `System.load`/`loadLibrary`, `LibraryLoader`, or a Python bridge; or the test classpath holds a jar outside the documented set; or a foreign project file (`requirements.txt`, `pyproject.toml`, `package.json`, `DESCRIPTION`, `Project.toml`, `.venv`, …) appears in the tree |
| build wiring | `build.yml` | `pom.xml` names an interpreter/exec/foreign plugin, or a foreign project file exists in the tree |
| pin freshness | `draft-pin-freshness` (advisory) | FHT-Specification's live raw-main bytes no longer match the recorded pin (a deliberate re-pin is then required); the step first proves its own probe by checking that a nonsense path returns 404 |

## Deliberate-failure evidence

"Verify a deliberate assertion/fixture failure makes the native CI job fail" was
executed on PR #6, twice, and both runs are cited there and in the #3 status
comment: once by flipping one vector expectation in a test (red at the test
phase), once by pointing `-Dfh.spec.dir` at a directory without a manifest (red
at the skip/report guard). Both breaks were then reverted in a following commit,
and the green run after the revert is cited too. The 2026-11-15 suite re-pin added
two more, on the pin machinery itself: pointing both draft lanes at the advanced
checkouts while the old pins were still recorded produced `vectorsRan=0` with a
drift line in each receipt and a report-guard exit 1 (never stale green); and a
one-byte tamper of the shipped `fhgm-transport-draft.json` — byte-verified by
`cmp` to be a real mutation before any conclusion was drawn — failed
`DraftProvenanceTest` and made `SchemaBundle` refuse to build the lane at all.
Both were restored and `cmp`-verified against the pristine bytes before the green
re-run.

## What this file does not claim

It does not make FHT or FHGM supported. Draft evidence runs in its own labelled
job and is reported separately; `docs/CAPABILITY-MATRIX.md` is the capability
statement, including the 63 shared vectors this library does not run, the FHGM
`gff3` feature layer it skips by design and the FHGM 1.2 matrix lane
(`development/conformance/base_matrix.json`) it does not run. Acceptance for the
released types remains the shared
language-neutral manifest, not a per-language suite: this repository adds no
normative vectors of its own.
