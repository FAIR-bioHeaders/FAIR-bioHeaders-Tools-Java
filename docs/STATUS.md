# Status of this library (2026-11-14)

Initial vertical slice: **read + checksum-verify + schema-validate** embedded
FAIR-bioHeaders headers in FASTA (FHR/FHP), GFA (FHR) and GFF3 (FHGFF3) files,
as a Java 17 library (`org.fairbioheaders.BioHeaders`) and a small
`verify` CLI. Nothing here is a release: version `0.1.0-SNAPSHOT`, no Maven
Central publication, no tags.

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

Plus 30 unit/integration JUnit tests (258 test executions, 0 failures, on
2026-11-14). The 63 not-run vectors are every `microdata-*` and `jsonld-*`
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

## What this library does NOT implement or claim

- **No writing.** No combine/strip/convert/faidx/verify-links/assess; no header
  writer, so no checksum *generation* — use the Python toolkit for that.
  Never hand-edit a checksum.
- **No microdata or JSON-LD bindings** (their 63 vectors are not run).
- **No GFF3 feature validation.** FHGFF3 vectors pass on header, placement,
  link-cardinality and checksum grounds only; feature-line syntax/SO/biology
  belongs to gff3-validator.
- **No FHT or FHGM support of any kind.** Both are drafts (rule V7): no draft
  lane, no pinned draft bytes, no `schemaVersion`, no w3id URL, no registry
  entry, no "supported" claim. A raw-main `fht.json` citation is read as FHR
  under published rule V5, with the near-miss warning.
- **No V7 draft lane at all**, even opt-in.
- No release, tag, Maven Central publication or Zenodo record exists for this
  repository; version `0.1.0-SNAPSHOT` is a development marker only.
- Non-string YAML mapping keys are rejected outright (the reference loader's
  JSON round-trip of such keys is out of scope; no shared vector covers it).
- Directive cross-checking (`#!genome-build` etc. compared with metadata),
  the toolkit's `MAX_*` streaming architecture, and its 1 MiB/10 000-line
  limits are ported as whole-buffer checks, not as streaming bounds; the
  limits match the toolkit values but memory use is O(file).

## Not verified (open questions, honestly listed)

- The suite (258 tests) passed on this host with Homebrew OpenJDK **17.0.21.0.3**
  and **27.0.1.0.3** (macOS/aarch64, Maven 3.9.11). JDK 21 is in the CI matrix
  but had not been executed here, and no GitHub Actions run existed at the time
  of writing — treat the CI green as the evidence for other platforms.
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
