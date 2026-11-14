/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.fairbioheaders.BioHeaders;
import org.fairbioheaders.Issue;
import org.fairbioheaders.Kind;
import org.fairbioheaders.Options;
import org.fairbioheaders.ParentLinks;
import org.fairbioheaders.SchemaBundle;
import org.fairbioheaders.VerifyResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Native runner for the <b>FHT draft</b> suite of
 * <a href="https://github.com/FAIR-bioHeaders/FHT-Specification">FHT-Specification</a>
 * ({@code conformance/manifest.json}).
 *
 * <p><b>This is rule V7 evidence, not a support claim.</b> FHT has no released contract, no
 * {@code schemaVersion} row in the shared registry, no w3id URL and no publication; its
 * development contract is read here only through the explicit opt-in, only against the pinned
 * revision (commit and SHA-256) named in {@code schemas/bundled.json}, and its results are kept
 * separate from the released-contract counts everywhere they are reported. docs/STATUS.md and
 * docs/CAPABILITY-MATRIX.md carry the labels.
 *
 * <p>The suite is language-neutral data, the runner is Java. Per vector it enforces the manifest's
 * own expectation model: the verdict; the recomputed SHA-512/256; the {@code selfChecksum} state
 * ({@code matches} / {@code broken} / {@code unreachable}) so nothing passes for the wrong reason;
 * the {@code warnings} list; the C3 {@code parentLink} verdicts against the parent files the
 * manifest names; that a release-form citation of an unregistered type stays rule V5
 * ({@code result: invalid-as-fhr}) even with the lane open; and that every record the lane reads
 * carries the V7 warning naming the pinned commit and digest.
 *
 * <p>Checkout discovery: {@code -Dfh.fht.dir}, then {@code FH_FHT_DIR}, then a sibling
 * {@code ../FHT-Specification}. CI clones the pinned commit and passes it explicitly. With no
 * checkout the vectors are reported skipped and the receipt counts them as zero - never as
 * passing. Before running anything the harness also re-digests the checkout's {@code fht.json} and
 * requires it to equal the bytes this jar pins: if the development repository moved, the vectors
 * are no longer evidence about the contract this reader reads, and the run says so.
 */
public final class DraftHarness {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One vector's outcome, in the same shape the released-contract harness reports. */
    public record Outcome(String id, String format, String expected, boolean ran, boolean passed,
                          VerifyResult.Status status, String detail) {
    }

    private final Path suiteDir;
    private final JsonNode manifest;
    private final BioHeaders reader;
    private final String citation;
    private final String pinnedCommit;
    private final String pinnedSha256;
    private final String suiteCommit;
    private final String suiteManifestSha256;
    private final String drift;

    public DraftHarness() {
        this.suiteDir = locate();
        this.manifest = suiteDir == null ? null
            : JSON.readTree(suiteDir.resolve("manifest.json").toFile());
        Options options = Options.builder().enableDraft("FHT").build();
        this.reader = new BioHeaders(options);
        SchemaBundle.Draft row = fhtRow();
        this.citation = row == null ? null : row.citation();
        this.pinnedCommit = row == null ? null : row.commit();
        this.pinnedSha256 = row == null ? null : row.sha256();
        this.suiteCommit = row == null ? null : row.suiteCommit();
        this.suiteManifestSha256 = row == null ? null : row.suiteManifestSha256();
        this.drift = checkSuiteBytes();
    }

    private static SchemaBundle.Draft fhtRow() {
        return SchemaBundle.getDefault().draft("FHT");
    }

    /** The draft citation this reader is pinned to, as stated by the shipped registry. */
    public String citation() {
        return citation;
    }

    public String pinnedCommit() {
        return pinnedCommit;
    }

    public String pinnedSha256() {
        return pinnedSha256;
    }

    public String suiteCommit() {
        return suiteCommit;
    }

    public String suiteManifestSha256() {
        return suiteManifestSha256;
    }

    public int manifestVectorCount() {
        return manifest == null ? 0 : manifest.get("vectors").size();
    }

    /** The checkout's contract file digest as it stands (null with no checkout). */
    public String contractFile() {
        return suiteDir == null ? null : suiteDir.getParent().resolve("fht.json").toString();
    }

    /** Non-null when the checkout's contract bytes differ from the pinned ones. */
    public String drift() {
        return drift;
    }

    public boolean available() {
        return suiteDir != null && manifest != null && drift == null;
    }

    public Path suiteDir() {
        return suiteDir;
    }

    /** The manifest's own status label, recorded verbatim so receipts say what they measured. */
    public String statusLabel() {
        return manifest == null ? "" : manifest.path("status").asString("");
    }

    /**
     * Both halves of the suite's identity are checked before a single vector runs: the contract
     * bytes (the same digest the lane is pinned to) and the manifest bytes (the expectations
     * themselves). Either can move under a reader that claims to be reading a fixed revision, so
     * a mismatch is reported as drift and every vector is then "not run", not "failed".
     */
    private String checkSuiteBytes() {
        if (suiteDir == null || pinnedSha256 == null) {
            return null;
        }
        try {
            Path contract = suiteDir.getParent().resolve("fht.json");
            if (!Files.isRegularFile(contract)) {
                return "no fht.json beside the suite at " + contract;
            }
            String actualContract = SchemaBundle.sha256(Files.readAllBytes(contract));
            if (!actualContract.equals(pinnedSha256)) {
                return "FHT-Specification checkout has fht.json sha256 " + actualContract
                    + " but this jar pins " + pinnedSha256 + " (commit " + pinnedCommit
                    + "); re-pin deliberately before reading drafts";
            }
            String actualManifest = SchemaBundle.sha256(Files.readAllBytes(suiteDir.resolve("manifest.json")));
            if (suiteManifestSha256 != null && !suiteManifestSha256.isEmpty()
                    && !actualManifest.equals(suiteManifestSha256)) {
                return "FHT-Specification checkout has conformance/manifest.json sha256 "
                    + actualManifest + " but the registry pins " + suiteManifestSha256
                    + " (suite commit " + suiteCommit + ")";
            }
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The FHT-Specification {@code conformance} directory, or null when nothing was named and no
     * sibling holds one. A named directory without the suite is an error ({@link SpecSource}): a
     * draft run must never silently read a different revision than the one it was pointed at,
     * because the whole value of draft evidence is that its revision is known.
     */
    private static Path locate() {
        return SpecSource.resolve(System.getProperty("fh.fht.dir"), System.getenv("FH_FHT_DIR"),
            "-Dfh.fht.dir", "FH_FHT_DIR",
            List.of(Path.of("../FHT-Specification/conformance"), Path.of("FHT-Specification/conformance")),
            SpecSource.FHT_SUITE);
    }

    public List<JsonNode> vectors() {
        List<JsonNode> out = new ArrayList<>();
        if (manifest == null) {
            return out;
        }
        manifest.get("vectors").forEach(out::add);
        return out;
    }

    /** Run one manifest vector with the draft lane open. */
    public Outcome run(JsonNode vector) {
        String id = vector.get("id").asString();
        String format = vector.get("format").asString();
        String expected = vector.get("expected").asString();
        if (!"fht-fasta".equals(format)) {
            return new Outcome(id, format, expected, false, false, null,
                "format " + format + " is outside the FHT draft suite");
        }
        if (drift != null) {
            return new Outcome(id, format, expected, false, false, null, drift);
        }
        Kind kind = Kind.FASTA;
        Path file = suiteDir.resolve(vector.get("file").asString()).normalize();
        if (!file.startsWith(suiteDir)) {
            return new Outcome(id, format, expected, true, false, null,
                "vector path escapes the suite directory: " + vector.get("file").asString());
        }
        byte[] data;
        try {
            data = BioHeaders.readAll(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // The manifest names the parent file for each C3 link; the reader recomputes that file's
        // checksum and compares exact strings, so the verdict comes from the bytes, not the label.
        Map<String, String> filesByStated = new LinkedHashMap<>();
        Map<String, String> expectedVerdict = new LinkedHashMap<>();
        for (JsonNode link : vector.path("parentLink")) {
            String stated = link.path("statedChecksum").asString("");
            expectedVerdict.put(stated, link.path("result").asString(""));
            String parent = link.path("file").asString(null);
            if (parent != null) {
                filesByStated.put(stated, parent);
            }
        }
        // The source is supplied whenever the manifest records links at all, so a link whose
        // parent file the suite did not supply comes back as UNCHECKED rather than unreported.
        ParentLinks.ParentSource source = expectedVerdict.isEmpty()
            ? null
            : ParentLinks.relativeTo(suiteDir, filesByStated);
        VerifyResult result = reader.verify(data, kind, source);
        List<String> problems = new ArrayList<>();

        boolean draftRouted = result.metadata() != null && citation != null
            && citation.equals(result.metadata().path("schema").asString(null));
        boolean warned = result.hasRule("V7");
        if (draftRouted && !warned) {
            problems.add("a record read through the draft lane must carry the V7 warning");
        }
        if (!draftRouted && warned) {
            problems.add("a V7 warning appeared on a record not read through the lane");
        }

        if ("valid".equals(expected)) {
            if (result.status() != VerifyResult.Status.VALID) {
                problems.add("expected valid, got " + result.status() + " " + result.errors());
            }
            for (Issue error : result.errors()) {
                problems.add("unexpected error: " + error);
            }
        } else if (result.isValid()) {
            problems.add("expected invalid, got VALID");
        }

        // R1-R5 recomputation and the suite's selfChecksum anti-accident rule.
        if (vector.has("checksum")) {
            String want = vector.get("checksum").asString();
            if (!want.equals(result.computedChecksum())) {
                problems.add("computed checksum " + result.computedChecksum()
                    + " != manifest " + want);
            }
        }
        List<String> codes = result.issues().stream().map(Issue::rule).toList();
        switch (vector.path("selfChecksum").asString("")) {
            case "matches" -> {
                if (codes.contains("R3")) {
                    problems.add("selfChecksum=matches but the reader reports an R3 mismatch: " + codes);
                }
            }
            case "broken" -> {
                if (!codes.contains("R3")) {
                    problems.add("selfChecksum=broken expects an R3 checksum mismatch, got " + codes);
                }
            }
            case "unreachable" -> {
                boolean lineShape = codes.contains("R2") || codes.contains("R5") || codes.contains("R10");
                if (codes.contains("R3") || !lineShape) {
                    problems.add("selfChecksum=unreachable expects an R2/R5/R10 line-shape rejection"
                        + " and no R3, got " + codes);
                }
            }
            default -> problems.add("vector records no selfChecksum state");
        }

        // The failure class the manifest names, where this library reports that class.
        String rule = vector.path("rule").asString("");
        if (!rule.isEmpty() && "invalid".equals(expected)) {
            // The manifest names the rule a vector fails on; this is the label this reader reports
            // that class of failure under. R1/R3/R4 all surface as a checksum mismatch (R3) except
            // where the checksum *line* itself is malformed (non-canonical base64, wrong length),
            // which is an R5 line-shape rejection before any hashing is compared; R15 and C1/C2 are
            // enforced by the contract's own key rules, so they arrive as "schema"; C3 is reported
            // by ParentLinks.RULE; V5 is the reading itself, not a checksum problem.
            String label = switch (rule) {
                case "R1", "R3" -> "R3";
                case "R4" -> "R3-or-R5";
                case "C1", "C2", "V1", "R15" -> "schema";
                case "C3" -> ParentLinks.RULE;
                default -> rule;
            };
            boolean ok = label.equals("R3-or-R5")
                ? (codes.contains("R3") || codes.contains("R5"))
                : codes.contains(label);
            if (!ok) {
                problems.add("manifest names rule " + rule + "; expected the reader to report ["
                    + label + "], got " + codes);
            }
        }
        if ("invalid-as-fhr".equals(vector.path("result").asString(""))) {
            if (!codes.contains("V5")) {
                problems.add("a release-form citation of an unregistered type must stay rule V5"
                    + " with the near-miss warning, got " + codes);
            }
            if (draftRouted) {
                problems.add("the record must not be routed to the draft");
            }
        }

        for (JsonNode warning : vector.path("warnings")) {
            if ("R14".equals(warning.asString()) && !codes.contains("R14")) {
                problems.add("expected an R14 warning, none raised");
            }
        }

        // C3: the verdicts the reader reached, entry by entry, against the files the manifest names.
        if (!expectedVerdict.isEmpty()) {
            List<ParentLinks.Verdict> verdicts = result.parentLinkVerdicts();
            if (verdicts.size() != expectedVerdict.size()) {
                problems.add("the record carries " + verdicts.size() + " derivedFrom links but the"
                    + " manifest records " + expectedVerdict.size());
            }
            for (ParentLinks.Verdict verdict : verdicts) {
                String want = expectedVerdict.get(verdict.statedChecksum());
                if (want == null) {
                    problems.add("link " + verdict.index() + " states " + verdict.statedChecksum()
                        + ", which no manifest parentLink entry names");
                    continue;
                }
                if (!verdict.state().name().equals(verdict(want))) {
                    problems.add("link " + verdict.index() + " (" + verdict.relationship() + " "
                        + verdict.headerType() + ") is " + verdict.state()
                        + ", manifest says " + want);
                }
            }
            if ("valid".equals(expected) && expectedVerdict.containsValue("checked-pass")
                    && verdicts.stream().noneMatch(v -> v.state() == ParentLinks.State.CHECKED_PASS)) {
                problems.add("a passing link must be reported as checked, not skipped");
            }
            if ("valid".equals(expected) && expectedVerdict.containsValue("unchecked")
                    && verdicts.stream().anyMatch(v -> v.state() == ParentLinks.State.CHECKED_PASS)) {
                problems.add("an unchecked link must never be reported as verified");
            }
        }

        return new Outcome(id, format, expected, true, problems.isEmpty(), result.status(),
            String.join("; ", problems));
    }

    private static String verdict(String manifestVerdict) {
        return switch (manifestVerdict) {
            case "checked-pass" -> "CHECKED_PASS";
            case "checked-fail" -> "CHECKED_FAIL";
            case "unchecked" -> "UNCHECKED";
            default -> manifestVerdict.toUpperCase(java.util.Locale.ROOT);
        };
    }

    /** A human-readable per-vector report, for the CI artefact. */
    public static String report(List<Outcome> outcomes, DraftHarness harness) {
        StringBuilder sb = new StringBuilder();
        long ran = outcomes.stream().filter(Outcome::ran).count();
        long passed = outcomes.stream().filter(Outcome::passed).count();
        sb.append("FHT draft suite (rule V7, pinned development contract - not a support claim)\n")
            .append("  suite      : ").append(harness.suiteDir()).append('\n')
            .append("  contract   : commit ").append(harness.pinnedCommit())
            .append(" sha256 ").append(harness.pinnedSha256()).append('\n')
            .append("  suite      : commit ").append(harness.suiteCommit())
            .append(" manifest sha256 ").append(harness.suiteManifestSha256()).append('\n')
            .append("  citation   : ").append(harness.citation()).append('\n')
            .append("  manifest   : ").append(harness.statusLabel()).append('\n')
            .append("  vectors    : ").append(passed).append(" passed of ").append(ran)
            .append(" ran (").append(outcomes.size()).append(" in the manifest)\n");
        if (harness.drift() != null) {
            sb.append("  DRIFT      : ").append(harness.drift()).append('\n');
        }
        for (Outcome o : outcomes) {
            if (!o.passed()) {
                sb.append("    ").append(o.ran() ? "FAIL    " : "NOT RUN ").append(o.id())
                    .append(" [expected ").append(o.expected()).append("]")
                    .append(o.detail().isEmpty() ? "" : ": " + o.detail()).append('\n');
            }
        }
        return sb.toString();
    }
}
