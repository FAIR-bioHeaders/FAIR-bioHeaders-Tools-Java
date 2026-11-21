# Contract regular expressions

The family's [regular-expression rules](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification/blob/main/docs/REGEX-DIALECT.md)
remain authoritative. This consumer uses `EcmaRegularExpressionFactory` to
translate ECMA-262 end anchors, whitespace classes and empty character classes
before compiling them with Java's backtracking `Pattern` engine. Matching uses
`Matcher.find()`; this adapter does not impose a per-match timeout or execution
budget. Adversarial instance text can therefore make an expensive contract
pattern costly to evaluate.

In the shipped reader/verifier, patterns come from bundled contract resources,
not from file-supplied regular expressions or a schema URL fetched from that
file. Released resource digests are recorded in `schemas/provenance.json` and
checked by `CapabilityTest.everyBundledSchemaMatchesItsRecordedSha256()` during
the test/build cycle. Opt-in draft contract and overlay digests are also checked
when `SchemaBundle` loads them against their registry pins. Released schemas
are not re-hashed on every validation. These pins establish which bytes were
reviewed; they do not prove that a pattern resists pathological backtracking.

Review new or changed contract patterns for regular-expression denial of service
through the owning specification's contract review cycle before deliberately
adopting new bytes here. This documented trust boundary retains the current
pinned contracts and matching behavior; it adds no timeout or schema-selection
change. Applications requiring a hard wall-clock bound must enforce that bound
outside this validator, for example by isolating validation in a process they
can terminate.
