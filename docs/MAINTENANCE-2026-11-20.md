# Round 16 maintenance review — 2026-11-20

Agents prepare and repair PRs; humans merge them. Independent review provenance,
exact tested heads, scanner receipts and detailed finding dispositions belong in
the PR discussion and Round 16 evidence packet. No contract, fixture, provenance
pin, golden expectation, release or supported-format expansion is part of this
maintenance change.

The separate B run supplied the inherited policy and human documentation commits
(user-described Qwen; exact author model/provider not observed). The follow-up
author is Codex (GPT-6; routing variant not exposed). Existing worktrees and old
branches were preserved when the follow-up moved to an isolated branch.

## Scope and results

An actual independent configured `nrp/qwen3` review received all 25 authored Java
runtime modules, both Python helpers and supplied build/workflow/documentation
source. Findings were verified rather than accepted mechanically: ByteBuffer map
lookups do not mutate key positions, and a regression now checks repeated index
lookups. Actual fixes parse explicit parent checksum arguments by canonical
grammar, reject unknown CLI draft names before claiming the lane is enabled and
render source-level invisible character constants as readable Unicode escapes.
Existing text-scope and URI-field derivation tests supply context omitted from
the review packet; no speculative contract or validator changes were made.

- Clean Maven install: 653 tests, zero failures, errors or skips, with the exact
  existing released and draft corpora supplied. All 228 released file vectors,
  82 FHT and 62 FHGM draft vectors ran with separate ledgers.
- Packaged API consumer compiled with `--release 17`, using only the jar and its
  dependency jars: plain/gzip accepted with the expected checksum, one sequence
  byte changed caused an R3 rejection. Local Maven install succeeded.
- OSV query of the exact 17 resolved Maven coordinates returned no advisory hits.
  This is a database observation, not proof that dependencies lack vulnerabilities.
- Gitleaks history's 18 findings were public synthetic-accession test literals.
- Bandit 1.8.6/Python 3.13 checked both Python helpers without skipped/error files:
  28 diagnostics concern fixed-argv subprocesses, fixed HTTPS GitHub API requests
  and executable control assertions; the assertions are intentional test code.

The JVM validation ran on OpenJDK 27 with Java 17 bytecode target; supported JDK
17/21 platform matrices remain current-head CI obligations. No broad exception
handler converts internal/configuration failures into ordinary invalid-file verdicts.
