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
import java.util.Set;

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

    /**
     * The specification checkout holding {@code conformance/manifest.json}, or null when nothing
     * was named and no sibling holds one (reported as skipped, never as passed). A named directory
     * that does not hold the suite is an error: see {@link SpecSource}.
     */
    private static Path locateSpec() {
        return SpecSource.resolve(System.getProperty("fh.spec.dir"), System.getenv("FH_SPEC_DIR"),
            "-Dfh.spec.dir", "FH_SPEC_DIR",
            List.of(Path.of("../FAIR-bioHeaders-Specification"), Path.of("FAIR-bioHeaders-Specification")),
            SpecSource.SPECIFICATION);
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
        boolean retiredValid = false;
        JsonNode crosswalk = JSON.readTree(ConformanceHarness.class.getResourceAsStream("/golden/retirement-crosswalk.json"));
        for (JsonNode retired : crosswalk.path("cases")) {
            if (retired.path("id").asString().equals(id) && expectedValid) {
                try {
                    String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                    if (!digest.equals(retired.path("oldSha256").asString())) throw new IOException("retired source bytes differ: " + id);
                } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
                expectedValid = false;
                retiredValid = true;
            }
        }
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
            JsonNode wantMetadata = v.get("metadata");
            if (wantMetadata != null && wantMetadata.isObject()) {
                JsonNode got = result.metadata();
                for (String key : wantMetadata.propertyNames()) {
                    JsonNode have = got == null ? null : got.get(key);
                    if (have == null || !sameValue(wantMetadata.get(key), have)) {
                        problems.add("metadata " + key + " = " + have + ", manifest says "
                            + wantMetadata.get(key));
                    }
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
            // Invalid for the right reason: an error must carry the rule the vector breaks.
            if (v.has("rule") && !result.isValid()) {
                String rule = retiredValid ? "R4" : v.get("rule").asString();
                Set<String> accepted = evidenceFor(rule);
                if (result.errors().stream().noneMatch(i -> accepted.contains(i.rule()))) {
                    problems.add("expected an error for rule " + rule + " (labels " + accepted
                        + "), got " + result.errors().stream().map(Issue::rule).distinct().toList());
                }
            }
        }
        return new Outcome(id, format, expectedValid, true, problems.isEmpty(),
            result.status(), String.join("; ", problems));
    }

    /**
     * The issue labels that count as evidence that a vector failed for the rule the manifest names.
     * Most rules are checked by name. The rules a released contract enforces (P1, P2, G2, G3, R15,
     * and the selection rules V1-V3, V5 and V6, whose failure shows as the record not satisfying
     * the contract it selected) surface as {@code schema} errors from the validator, so a schema
     * error is their evidence; a GFF3 header citing another type is refused as the wrong
     * container before any validation (G3). R1 and R4 define which bytes are hashed and how the digest is
     * written, so a file that breaks them states a checksum that does not match the covered bytes
     * (R3) or is not a canonical 44-character value (R5). G1 also defines where the GFF3 header
     * block ends, and a {@code #~} line after {@code ##FASTA} is reported as the late header line
     * it is (R10).
     */
    public static Set<String> evidenceFor(String rule) {
        return switch (rule) {
            case "G3" -> Set.of("G3", "schema", "container");
            case "R17" -> Set.of("R17", "container");
            case "P1", "P2", "G2", "R15", "V1", "V2", "V3", "V5", "V6" -> Set.of(rule, "schema");
            case "R1" -> Set.of("R1", "R3");
            case "R4" -> Set.of("R4", "R3", "R5");
            case "G1" -> Set.of("G1", "R10");
            default -> Set.of(rule);
        };
    }

    /**
     * JSON equality where numbers compare by value, as JSON (and the reference toolkit's Python
     * comparison) does: the manifest writes the YAML float {@code 1.0} as {@code 1}.
     */
    static boolean sameValue(JsonNode want, JsonNode have) {
        if (want.isNumber() && have.isNumber()) {
            return want.decimalValue().compareTo(have.decimalValue()) == 0;
        }
        if (want.isObject() && have.isObject()) {
            if (want.size() != have.size()) {
                return false;
            }
            for (String key : want.propertyNames()) {
                if (!have.has(key) || !sameValue(want.get(key), have.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (want.isArray() && have.isArray()) {
            if (want.size() != have.size()) {
                return false;
            }
            for (int i = 0; i < want.size(); i++) {
                if (!sameValue(want.get(i), have.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return want.equals(have);
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
