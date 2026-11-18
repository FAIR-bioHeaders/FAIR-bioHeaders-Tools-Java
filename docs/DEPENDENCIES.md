# Dependency record

All licenses were read from the resolved POMs or bundled `META-INF/LICENSE` on
2026-11-14 (`mvn dependency:tree` output cross-checked against `~/.m2` copies).
This library is MPL-2.0; the dependencies below stay under their own terms.
No dependency is GPL/copyleft with wider reach than the MPL obligations, and
nothing is vendored at source level — every artifact comes from Maven Central.

## Runtime (compile scope)

| Artifact | Version | License | Used for |
| --- | --- | --- | --- |
| `com.networknt:json-schema-validator` | 3.0.9 | Apache-2.0 | draft/2020-12 contract validation (E0-verified; see `docs/STATUS.md`) |
| `com.ethlo.time:itu` | 1.17.0 | Apache-2.0 | transitive (RFC 3339 date/time checks) |
| `tools.jackson.core:jackson-databind` | 3.1.8 | Apache-2.0 | JSON model shared with the validator |
| `tools.jackson.core:jackson-core` | 3.1.8 | Apache-2.0 | transitive |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.21 | Apache-2.0 | transitive |
| `tools.jackson.dataformat:jackson-dataformat-yaml` | 3.1.8 | Apache-2.0 | transitive (validator YAML input support) |
| `org.snakeyaml:snakeyaml-engine` | 3.0.1 | Apache-2.0 | transitive of the Jackson YAML format |
| `org.slf4j:slf4j-api` | 2.0.20 | MIT | transitive logging API facade |
| `org.yaml:snakeyaml` | 2.7 | Apache-2.0 | direct: low-level YAML *parser* only; scalar typing is decided by this library's own R12 resolver, not by any bundled YAML 1.1/1.2 resolver |

`com.networknt:json-schema-validator` also lists optional dependencies
(`org.jruby.joni:joni`, GraalVM JS regex support); they are `optional=true` in
its POM and are not resolved or required by this project.

## Test scope

| Artifact | Version | License |
| --- | --- | --- |
| `org.junit.jupiter:junit-jupiter` (api/params/engine, platform 1.11.4, opentest4j 1.3.0, apiguardian 1.1.2) | 5.11.4 | EPL-2.0 |

## Build

Maven wrapper (`mvnw`) pinned to Apache Maven 3.9.11 (Apache-2.0);
`maven-compiler-plugin` with `release=17`, `maven-surefire-plugin` 3.5.2,
`maven-jar-plugin` 3.4.2 (its manifest names `lib/*.jar` on the class path so
`java -jar` works), `maven-dependency-plugin` 3.8.1 (copies the runtime jars above
to `target/lib/` at `package`; nothing is shaded or repackaged, so each jar keeps
its own license files). Java 17 is the minimum supported JDK (the
json-schema-validator 3.x line targets Java 17); builds run on the current
LTS and newer JDKs — CI pins 17 and 21.

## Data files

`src/main/resources/org/fairbioheaders/schemas/*.json` are verbatim copies of
released FAIR-bioHeaders-Specification files, redistributed under that
repository's license terms with tags, commits and SHA-256 digests recorded in
`provenance.json` (see the LICENSE scope section).
