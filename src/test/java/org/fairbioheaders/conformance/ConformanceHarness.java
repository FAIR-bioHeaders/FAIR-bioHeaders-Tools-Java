/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders.conformance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.fairbioheaders.BioHeaders;
import org.fairbioheaders.Issue;
import org.fairbioheaders.Kind;
import org.fairbioheaders.VerifyResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Runner for the shared conformance vectors of FAIR-bioHeaders-Specification
 * ({@code conformance/manifest.json}); acceptance for this library is that suite,
 * not a new per-language one. The suite is language-neutral: each vector names a
 * file, a container format, an expected verdict, and for file formats the expected
 * SHA-512/256 checksum of the exact bytes.
 *
 * <p>Vector source: a checkout of FAIR-bioHeaders-Specification, located via the
 * {@code fh.spec.dir} system property, the {@code FH_SPEC_DIR} environment variable,
 * or a sibling {@code ../FAIR-bioHeaders-Specification} directory. CI clones the
 * pinned release tag of the specification repository and passes it explicitly.
 *
 * <p>Formats covered here: {@code fasta}, {@code gfa}, {@code fhp-fasta} and
 * {@code fhgff3} (the embedded-header file containers). The {@code microdata} and
 * {@code jsonld} vectors exercise the HTML-microdata and JSON-LD bindings, which
 * this library does not implement yet (see docs/STATUS.md); they are reported as
 * not run, never as passing.
 */
public final class ConformanceHarness {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Outcome(String id, String format, boolean expectedValid, boolean ran,
                          boolean passed, VerifyResult.Status status, String detail) {
    }

    private final Path specDir;
    private final JsonNode manifest;
    private final BioHeaders verifier = new BioHeaders();

    public ConformanceHarness() {
        this.specDir = locateSpec();
        if (vectorsAvailable()) {
            this.manifest = JSON.readTree(specDir.resolve("conformance/manifest.json").toFile());
        } else {
            this.manifest = null;
        }
    }

    public boolean vectorsAvailable() {
        return specDir != null && Files.isRegularFile(specDir.resolve("conformance/manifest.json"));
    }

    public Path specDir() {
        return specDir;
    }

    private static Path locateSpec() {
        List<Path> candidates = new ArrayList<>();
        String prop = System.getProperty("fh.spec.dir");
        if (prop != null) {
            candidates.add(Path.of(prop));
        }
        String env = System.getenv("FH_SPEC_DIR");
        if (env != null) {
            candidates.add(Path.of(env));
        }
        candidates.add(Path.of("../FAIR-bioHeaders-Specification"));
        candidates.add(Path.of("FAIR-bioHeaders-Specification"));
        for (Path c : candidates) {
            if (Files.isRegularFile(c.resolve("conformance/manifest.json"))) {
                return c;
            }
        }
        return null;
    }

    public static Kind kindForFormat(String format) {
        switch (format) {
            case "fasta":
            case "fhp-fasta":
                return Kind.FASTA;
            case "gfa":
                return Kind.GFA;
            case "fhgff3":
                return Kind.GFF3;
            default:
                return null;
        }
    }

    public List<Outcome> runAll() throws IOException {
        List<Outcome> out = new ArrayList<>();
        for (JsonNode v : manifest.get("vectors")) {
            out.add(run(v));
        }
        return out;
    }

    /** The manifest vectors this slice runs (the embedded-header file formats). */
    public List<JsonNode> fileVectors() {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode v : manifest.get("vectors")) {
            if (kindForFormat(v.get("format").asString()) != null) {
                out.add(v);
            }
        }
        return out;
    }

    /** Run one manifest vector. */
    public Outcome run(JsonNode v) throws IOException {
        String id = v.get("id").asString();
        String format = v.get("format").asString();
        Kind kind = kindForFormat(format);
        boolean expectedValid = v.get("expected").asString().equals("valid");
        if (kind == null) {
            return new Outcome(id, format, expectedValid, false, false, null,
                "format " + format + " is outside this library's implemented scope");
        }
        Path file = specDir.resolve("conformance").resolve(v.get("file").asString());
        byte[] data = BioHeaders.readAll(file);
        VerifyResult result = verifier.verify(data, kind);

        List<String> problems = new ArrayList<>();
        if (expectedValid) {
            if (result.status() != VerifyResult.Status.VALID) {
                problems.add("expected valid, got " + result.status());
            }
            for (Issue e : result.errors()) {
                problems.add("unexpected error: " + e);
            }
            if (v.has("checksum")) {
                String want = v.get("checksum").asString();
                if (!want.equals(result.computedChecksum())) {
                    problems.add("computed checksum " + result.computedChecksum() + " != manifest " + want);
                }
            }
            if (v.has("warnings")) {
                for (JsonNode w : v.get("warnings")) {
                    String rule = w.asString();
                    if (rule.equals("R14") && result.warnings().stream().noneMatch(i -> i.rule().equals("R14"))) {
                        problems.add("expected an R14 warning, none raised");
                    }
                }
            }
        } else {
            if (result.isValid()) {
                problems.add("expected invalid, got VALID");
            }
            if (v.has("result") && v.get("result").asString().equals("unsupported-version")
                    && result.status() != VerifyResult.Status.UNSUPPORTED_VERSION) {
                problems.add("expected unsupported-version, got " + result.status());
            }
        }
        return new Outcome(id, format, expectedValid, true, problems.isEmpty(),
            result.status(), String.join("; ", problems));
    }

    /** Human-readable pass/fail summary, per format. */
    public static String report(List<Outcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        List<String> formats = List.of("fasta", "gfa", "fhp-fasta", "fhgff3");
        for (String f : formats) {
            List<Outcome> os = outcomes.stream().filter(o -> o.format().equals(f)).toList();
            long pass = os.stream().filter(Outcome::passed).count();
            sb.append(String.format("%-9s %3d/%3d passed%n", f, pass, os.size()));
            for (Outcome o : os) {
                if (!o.passed()) {
                    sb.append("    FAIL ").append(o.id()).append(" (expected ")
                        .append(o.expectedValid() ? "valid" : "invalid")
                        .append(", got ").append(o.status()).append("): ").append(o.detail()).append('\n');
                }
            }
        }
        long notRun = outcomes.stream().filter(o -> !o.ran()).count();
        sb.append(String.format("%nnot run (bindings outside this slice): %d%n", notRun));
        for (Outcome o : outcomes) {
            if (!o.ran()) {
                sb.append("    skipped ").append(o.id()).append(" [").append(o.format()).append("]\n");
            }
        }
        return sb.toString();
    }
}
