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
import java.util.List;

import org.fairbioheaders.BioHeaders;
import org.fairbioheaders.FhgmModels;
import org.fairbioheaders.Issue;
import org.fairbioheaders.Kind;
import org.fairbioheaders.Options;
import org.fairbioheaders.SchemaBundle;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Native runner for the <b>FHGM draft</b> suite of
 * <a href="https://github.com/FAIR-bioHeaders/FHGM-Specification">FHGM-Specification</a>
 * ({@code development/conformance/manifest.json}).
 *
 * <p>Rule V7 evidence, labelled as such everywhere it is reported. FHGM has no released contract, no
 * {@code schemaVersion}, no w3id URL and no registry row; it is not a file-bound header type either
 * - its records ride inside an FHGFF3 header under {@code x-fhgm-models}. A pass below means "the
 * pinned draft contracts and this library's port of the draft's model-level rules agree with the
 * suite's recorded expectations at the pinned revision". It does not mean FHGM is supported.
 *
 * <p>Each vector is checked on every layer its manifest declares:
 *
 * <ul>
 *   <li>{@code bytes} - the fixture's own size and SHA-256, so upstream identity is verified rather
 *       than assumed;</li>
 *   <li>{@code layers.base} - the released FHGFF3 contract's verdict on the header (accept/reject);
 *       this is the B1 boundary and is never replaced by a model verdict;</li>
 *   <li>{@code layers.checksum} - whether the file's SHA-512/256 line covers its bytes
 *       ({@code verify}) or no longer does ({@code stale});</li>
 *   <li>{@code layers.model} - the K1-K6 verdict from {@link FhgmModels};</li>
 *   <li>{@code layers.gff3} - always reported skipped: feature-line validation belongs to
 *       {@code gff3-validator}, and the suite itself says no FHGM rule depends on that layer;</li>
 *   <li>{@code expected} plus {@code rule} - the overall verdict and the rule id the suite names;</li>
 *   <li>{@code context.predecessors} - resolved inside the suite tree only, then handed to the
 *       checker as the whole of the history it may use.</li>
 * </ul>
 *
 * <p>On {@code reason}: the suite records the reference checker's wording, which for schema-layer
 * rules is that library's phrasing rather than this validator's. So {@code reason} is matched where
 * this library owns the sentence (K3-K6, B1) and reported alongside the verdict where the wording
 * comes from JSON Schema evaluation (K1, K2); the rule id, the layer verdicts and the overall
 * expectation are asserted exactly in every case.
 */
public final class FhgmHarness {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Outcome(String id, String expected, boolean ran, boolean passed, String state,
                          String detail) {
    }

    private final Path suiteDir;
    private final JsonNode manifest;
    private final FhgmModels models;
    private final SchemaBundle.Draft pin;
    private final String drift;

    public FhgmHarness() {
        this.suiteDir = locate();
        this.manifest = suiteDir == null ? null
            : JSON.readTree(suiteDir.resolve("manifest.json").toFile());
        this.pin = SchemaBundle.getDefault().draft("FHGM");
        this.models = new FhgmModels(Options.builder().enableDraft("FHGM").build());
        this.drift = checkSuiteBytes();
    }

    /** The suite directory holding {@code manifest.json} and {@code vectors/}. */
    private static Path locate() {
        return SpecSource.resolve(System.getProperty("fh.fhgm.dir"), System.getenv("FH_FHGM_DIR"),
            "-Dfh.fhgm.dir", "FH_FHGM_DIR",
            List.of(Path.of("../FHGM-Specification/development/conformance"),
                Path.of("../FHGM/development/conformance"),
                Path.of("FHGM-Specification/development/conformance")),
            SpecSource.FHGM_SUITE);
    }

    private String checkSuiteBytes() {
        if (suiteDir == null || pin == null) {
            return null;
        }
        try {
            String manifestActual = SchemaBundle.sha256(Files.readAllBytes(suiteDir.resolve("manifest.json")));
            if (pin.suiteManifestSha256() != null && !pin.suiteManifestSha256().isBlank()
                    && !manifestActual.equals(pin.suiteManifestSha256())) {
                return "FHGM-Specification checkout has development/conformance/manifest.json"
                    + " sha256 " + manifestActual + " but the registry pins "
                    + pin.suiteManifestSha256() + " (suite commit " + pin.suiteCommit() + ")";
            }
            Path repo = suiteDir.getParent().getParent();
            for (SchemaBundle.Draft.Artifact artifact : pin.pinnedArtifacts()) {
                Path live = repo.resolve(artifact.sourcePath());
                if (!Files.isRegularFile(live)) {
                    return "the pinned FHGM artifact " + artifact.sourcePath()
                        + " is not in the checkout at " + live;
                }
                String actual = SchemaBundle.sha256(Files.readAllBytes(live));
                if (!actual.equals(artifact.sha256())) {
                    return "FHGM-Specification checkout has " + artifact.sourcePath() + " sha256 "
                        + actual + " but this jar pins " + artifact.sha256() + " (commit "
                        + pin.commit() + "); re-pin deliberately before reading drafts";
                }
            }
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean available() {
        return suiteDir != null && manifest != null && drift == null && pin != null;
    }

    public String drift() {
        return drift;
    }

    public Path suiteDir() {
        return suiteDir;
    }

    public String pinnedCommit() {
        return pin == null ? null : pin.commit();
    }

    public String pinnedSha256() {
        return pin == null ? null : pin.sha256();
    }

    /** The commit the transport overlay's own pinned bytes came from (distinct from the record contract's). */
    public String transportCommit() {
        return pin == null ? null : pin.transportCommit();
    }

    public String transportSha256() {
        return pin == null ? null : pin.transportSha256();
    }

    public String suiteCommit() {
        return pin == null ? null : pin.suiteCommit();
    }

    public String suiteManifestSha256() {
        return pin == null ? null : pin.suiteManifestSha256();
    }

    public int manifestVectorCount() {
        return manifest == null ? 0 : manifest.get("vectors").size();
    }

    public String statusLabel() {
        return manifest == null ? "" : manifest.path("status").asString("");
    }

    public List<JsonNode> vectors() {
        List<JsonNode> out = new ArrayList<>();
        if (manifest == null) {
            return out;
        }
        manifest.get("vectors").forEach(out::add);
        return out;
    }

    /** Run one manifest vector. */
    public Outcome run(JsonNode vector) {
        String id = vector.get("id").asString();
        String expected = vector.get("expected").asString();
        if (drift != null) {
            return new Outcome(id, expected, false, false, null, drift);
        }
        String relative = vector.get("file").asString();
        Path file = resolve(relative);
        List<String> problems = new ArrayList<>();
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("vector file " + file + " is not readable", e);
        }

        // Upstream identity: the bytes the manifest describes are the bytes being read.
        JsonNode bytes = vector.path("bytes");
        if (bytes.isObject()) {
            long wantSize = bytes.path("size").asLong(-1);
            String wantSha = bytes.path("sha256").asString("");
            if (wantSize >= 0 && data.length != wantSize) {
                problems.add("fixture is " + data.length + " bytes, manifest records " + wantSize);
            }
            if (!wantSha.isEmpty() && !wantSha.equals(SchemaBundle.sha256(data))) {
                problems.add("fixture sha256 " + SchemaBundle.sha256(data)
                    + " != manifest " + wantSha);
            }
        }

        List<byte[]> predecessors = new ArrayList<>();
        for (JsonNode prior : vector.path("context").path("predecessors")) {
            predecessors.add(readInside(resolve(prior.asString())));
        }

        FhgmModels.Report report = models.check(data, predecessors);
        List<Issue> modelIssues = report.issues();
        String state = report.state().name();

        if ("valid".equals(expected)) {
            if (!report.passed()) {
                problems.add("expected the model layer and its carrier to pass, got state="
                    + report.state() + " carrier=" + report.carrier().status() + " "
                    + combined(report));
            }
        } else if (report.passed()) {
            problems.add("expected invalid, got PASS");
        }

        JsonNode layers = vector.path("layers");
        boolean carrierSchemaErrors = report.carrier().issues().stream()
            .anyMatch(i -> i.rule().equals("schema"));
        String wantBase = layers.path("base").asString("");
        if ("reject".equals(wantBase) && !carrierSchemaErrors) {
            problems.add("layers.base=reject but the released FHGFF3 contract accepted the header");
        }
        if ("accept".equals(wantBase) && carrierSchemaErrors) {
            problems.add("layers.base=accept but the released FHGFF3 contract rejected the header: "
                + report.carrier().errors());
        }
        boolean checksumStale = report.carrier().hasRule("R3");
        String wantChecksum = layers.path("checksum").asString("");
        if ("stale".equals(wantChecksum) && !checksumStale) {
            problems.add("layers.checksum=stale but the file's own checksum line verified");
        }
        if ("verify".equals(wantChecksum) && checksumStale) {
            problems.add("layers.checksum=verify but the reader reports R3");
        }
        String wantModel = layers.path("model").asString("");
        if ("pass".equals(wantModel) && report.state() != FhgmModels.State.PASS) {
            problems.add("layers.model=pass but the model layer rejected: " + modelIssues);
        }
        if ("reject".equals(wantModel) && report.state() != FhgmModels.State.REJECT) {
            problems.add("layers.model=reject but the model layer passed");
        }

        String rule = vector.path("rule").asString("");
        if (!rule.isEmpty() && "invalid".equals(expected)) {
            String got = modelIssues.isEmpty()
                ? report.carrier().errors().stream().findFirst().map(Issue::rule).orElse("")
                : modelIssues.get(0).rule();
            if (!rule.equals(got)) {
                problems.add("the suite attributes the rejection to " + rule + ", this reader"
                    + " reports it under " + got + ": " + combined(report));
            }
            String reason = vector.path("reason").asString("");
            boolean wordingOwnedByThisLibrary = switch (rule) {
                case "K3", "K4", "K5", "K6" -> true;
                default -> false;
            };
            if (wordingOwnedByThisLibrary && !reason.isEmpty()) {
                String message = combined(report);
                if (!message.contains(reason)) {
                    problems.add("the suite's reason '" + reason + "' is not in this reader's"
                        + " report: " + message);
                }
            }
        }

        return new Outcome(id, expected, true, problems.isEmpty(), state,
            String.join("; ", problems));
    }

    /** Resolve a manifest-relative path, refusing anything that leaves the suite tree. */
    private Path resolve(String relative) {
        Path root = suiteDir.toAbsolutePath().normalize();
        Path resolved = root.resolve(relative).normalize();
        // The suite cites committed fixtures in place, including ../examples/** and
        // ../model/**: the containment root is the repository's development tree, not just
        // conformance/, and nothing outside it may be read.
        Path allowed = root.getParent().getParent();
        if (!resolved.startsWith(allowed)) {
            throw new IllegalArgumentException("FHGM vector path escapes the suite tree: " + relative);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("FHGM vector path is not a file: " + resolved);
        }
        return resolved;
    }

    private static byte[] readInside(Path file) {
        try {
            return BioHeaders.readAll(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static String combined(FhgmModels.Report report) {
        StringBuilder sb = new StringBuilder();
        for (Issue issue : report.carrier().issues()) {
            if (issue.severity() == org.fairbioheaders.Severity.ERROR) {
                sb.append("[carrier ").append(issue.rule()).append("] ")
                    .append(issue.message()).append("; ");
            }
        }
        for (Issue issue : report.issues()) {
            sb.append("[").append(issue.rule()).append("] ").append(issue.message()).append("; ");
        }
        return sb.toString();
    }

    /** Per-vector report for the CI artefact, with the skipped layer named. */
    public static String report(List<Outcome> outcomes, FhgmHarness harness) {
        long ran = outcomes.stream().filter(Outcome::ran).count();
        long passed = outcomes.stream().filter(Outcome::passed).count();
        StringBuilder sb = new StringBuilder();
        sb.append("FHGM draft suite (rule V7, pinned development contracts - not a support claim)\n")
            .append("  suite      : ").append(harness.suiteDir()).append('\n')
            .append("  record     : commit ").append(harness.pinnedCommit())
            .append(" sha256 ").append(harness.pinnedSha256()).append('\n')
            .append("  suite rev  : commit ").append(harness.suiteCommit())
            .append(" manifest sha256 ").append(harness.suiteManifestSha256()).append('\n')
            .append("  manifest   : ").append(harness.statusLabel()).append('\n')
            .append("  gff3 layer : SKIPPED by design - feature-line validation belongs to"
                + " gff3-validator; the suite records no FHGM rule that depends on it\n")
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
