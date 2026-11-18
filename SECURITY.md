# Security policy

Report suspected vulnerabilities privately, including the affected version or
commit, reproduction steps, impact, and any safe proof of concept. Avoid
posting credentials, private genome data or exploit details in a public issue.

Use the family's maintainer-confirmed reporting addresses (as recorded in
[FAIR-bioHeaders-Specification/SECURITY.md](https://github.com/FAIR-bioHeaders/FAIR-bioHeaders-Specification/blob/main/SECURITY.md)):
email [molik@fhref.org](mailto:molik@fhref.org); if the report involves David
Molik, contact [Adam Wright](mailto:wright@fhref.org) at
[wright@fhref.org](mailto:wright@fhref.org) directly, and if it involves Adam
Wright, use David's address. Identify the repository (FAIR-bioHeaders-Tools-Java)
and version. Do not assume GitHub private vulnerability reporting is enabled.

Maintainers should acknowledge receipt, assess severity, coordinate a fix and
disclosure, and credit the reporter with consent. No guaranteed response
deadline is stated. Schema-correctness issues without security impact can use
ordinary issues.

Implementation context worth knowing when you report: this library parses
untrusted files. It rejects YAML anchors/aliases/tags/merge keys/duplicate
keys, bounds the header block (1 MiB, 10 000 lines) and the YAML document
(20 000 nodes, depth 64), and schema selection resolves only against bundled
frozen copies (rules V1-V6, mirroring the toolkit: "schemas are never fetched"
— a reference to an unbundled release fails as unsupported-version instead of
triggering network use). A finding that bypasses any of this is in scope for
this policy.

Memory: `BioHeaders.verifyFile` and the CLI read a file once, as a stream. Gzip
and bgzip input is decompressed as it is read, only the header block is held
(the 1 MiB / 10 000-line bound above, with lines longer than that streamed
rather than buffered), and every other byte passes through the SHA-512/256
digest and is dropped, so memory use does not grow with the file or with its
compression ratio. Parent files given to `verify --parent` or
`ParentLinks.relativeTo` are streamed the same way. The in-memory entry points
(`BioHeaders.verify(byte[], Kind)`, `BioHeaders.readAll`) hold the whole input
by design; `readAll` refuses input that decompresses past 1 GiB with an
`IOException`. Corrupt or truncated gzip data is reported as an `R1` error,
and the CLI reports unreadable files, unsupported extensions and memory
exhaustion as per-file errors rather than stack traces.
