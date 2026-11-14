/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.fairbioheaders.JsonBytes;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The acceptance ledger: one entry for <b>every</b> vector of the shared
 * {@code conformance/manifest.json}, classified as passed, failed or not-run, with the reason for
 * every not-run one. Its whole purpose is that nothing is silently omitted - the 291-vector
 * baseline is accounted for as a number, and the holes are named rows in
 * {@code target/acceptance-ledger.json} rather than an absence of evidence.
 *
 * <p>Written by Java, read by CI: the primary job asserts {@code vectorsTotal=291},
 * {@code vectorsRun=228}, {@code vectorsNotRun=63}, {@code vectorsFailed=0} and that each declared
 * {@code declaredNotRun} reason is non-empty. If the shared suite grows, or a binding is
 * implemented here, this test moves and the CI guard moves with it: a stale declared total is a
 * build failure, never a quiet pass.
 */
class AcceptanceLedgerTest {

    /** The v0.6.0 baseline this library's claims are written against. */
    private static final int DECLARED_TOTAL = 291;
    private static final int DECLARED_RUN = 228;
    private static final int DECLARED_NOT_RUN = 63;

    private static final Map<String, String> NOT_RUN_REASONS = new LinkedHashMap<>();

    static {
        NOT_RUN_REASONS.put("microdata",
            "the HTML microdata binding (FAIR-bioHeaders-Specification docs/MICRODATA.md rules"
                + " M1-M5: itemtype/itemprop token lists, implied end tags, first-attribute-wins,"
                + " typed values) is not implemented in this library; see"
                + " docs/CAPABILITY-MATRIX.md section 2");
        NOT_RUN_REASONS.put("jsonld",
            "the JSON-LD binding (docs/JSONLD.md rules J1-J9: context handling, canonical form,"
                + " @id/@type, datetime expansion) is not implemented in this library; see"
                + " docs/CAPABILITY-MATRIX.md section 2");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void everySharedVectorIsAccountedFor() {
        ConformanceHarness harness = new ConformanceHarness();
        if (!harness.vectorsAvailable()) {
            // The repository convention for a missing checkout is "skip, never pass" (AGENTS.md);
            // CI's report guard turns any skip into a failure, so this stays visible instead of
            // becoming a green run with nothing in it.
            writeEmpty("no FAIR-bioHeaders-Specification checkout (-Dfh.spec.dir)");
            assumeTrue(false, "the shared manifest is the ledger's source; set -Dfh.spec.dir");
            return;
        }
        List<ConformanceHarness.Outcome> outcomes = run(harness);

        ObjectNode ledger = JSON.createObjectNode();
        Map<String, int[]> byFormat = new TreeMap<>();
        int run = 0;
        int passed = 0;
        int failed = 0;
        int notRun = 0;
        ArrayNode vectors = ledger.putArray("vectors");
        for (ConformanceHarness.Outcome outcome : outcomes) {
            String state = !outcome.ran() ? "not-run" : (outcome.passed() ? "passed" : "failed");
            ObjectNode entry = vectors.addObject();
            entry.put("id", outcome.id());
            entry.put("format", outcome.format());
            entry.put("expected", outcome.expectedValid() ? "valid" : "invalid");
            entry.put("state", state);
            if (!outcome.ran()) {
                entry.put("reason", NOT_RUN_REASONS.getOrDefault(outcome.format(),
                    "format " + outcome.format() + " has no declared reason in AcceptanceLedgerTest"));
            } else if (!outcome.passed()) {
                entry.put("detail", outcome.detail());
            } else {
                entry.put("status", String.valueOf(outcome.status()));
            }
            int[] counts = byFormat.computeIfAbsent(outcome.format(), k -> new int[3]);
            counts[0]++;
            if ("passed".equals(state)) {
                counts[1]++;
                passed++;
                run++;
            } else if ("failed".equals(state)) {
                counts[2]++;
                failed++;
                run++;
            } else {
                notRun++;
            }
        }

        ObjectNode formats = ledger.putObject("formats");
        byFormat.forEach((format, counts) -> {
            ObjectNode entry = formats.putObject(format);
            entry.put("total", counts[0]);
            entry.put("passed", counts[1]);
            entry.put("failed", counts[2]);
            entry.put("notRun", counts[0] - counts[1] - counts[2]);
        });
        ObjectNode declared = ledger.putObject("declaredNotRun");
        NOT_RUN_REASONS.forEach(declared::put);
        ledger.put("baseline", "FAIR-bioHeaders-Specification v0.6.0 conformance/manifest.json");
        ledger.put("vectorsTotal", outcomes.size());
        ledger.put("vectorsRun", run);
        ledger.put("vectorsPassed", passed);
        ledger.put("vectorsFailed", failed);
        ledger.put("vectorsNotRun", notRun);
        ledger.put("nativeRunner", "org.fairbioheaders.conformance.ConformanceHarness (Java)"
            + ", java.specification.version " + System.getProperty("java.specification.version")
            + ", " + System.getProperty("java.vendor"));

        write(ledger);
        System.out.println("acceptance ledger: " + outcomes.size() + " shared vectors, "
            + run + " run, " + passed + " passed, " + failed + " failed, " + notRun
            + " not run (reasons: " + NOT_RUN_REASONS.keySet() + ")");

        // The ledger is a claim about completeness, so it is checked, not just printed.
        assertEquals(DECLARED_TOTAL, outcomes.size(),
            "the shared manifest no longer has the declared baseline count; update the declared"
                + " numbers and docs/CAPABILITY-MATRIX.md deliberately, never to make a build pass");
        assertEquals(DECLARED_RUN, run, "the run set is the embedded-header formats");
        assertEquals(DECLARED_NOT_RUN, notRun, "the not-run set is microdata and JSON-LD");
        assertEquals(0, failed, () -> "a vector that ran must pass: " + failures(outcomes));
        assertEquals(DECLARED_RUN, passed);
        // Every declared reason must actually explain a hole, and no hole may be undeclared.
        for (ConformanceHarness.Outcome outcome : outcomes) {
            if (!outcome.ran()) {
                String reason = NOT_RUN_REASONS.get(outcome.format());
                assertTrue(reason != null && !reason.isBlank(),
                    "vector " + outcome.id() + " of format " + outcome.format()
                        + " is not run and carries no declared reason");
            }
        }
        for (String format : NOT_RUN_REASONS.keySet()) {
            assertTrue(byFormat.containsKey(format),
                "a reason is declared for format " + format + " which the manifest does not list");
        }
    }

    private static List<ConformanceHarness.Outcome> run(ConformanceHarness harness) {
        try {
            return harness.runAll();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String failures(List<ConformanceHarness.Outcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        for (ConformanceHarness.Outcome outcome : outcomes) {
            if (outcome.ran() && !outcome.passed()) {
                sb.append('\n').append(outcome.id()).append(": ").append(outcome.detail());
            }
        }
        return sb.toString();
    }

    private static void writeEmpty(String reason) {
        Path dir = Path.of(System.getProperty("fh.report.dir", "target"));
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("acceptance-ledger.properties"),
                "available=false\nreason=" + reason + "\nvectorsTotal=0\nvectorsRun=0\n"
                    + "vectorsPassed=0\nvectorsFailed=0\nvectorsNotRun=0\n",
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(ObjectNode ledger) {
        Path dir = Path.of(System.getProperty("fh.report.dir", "target"));
        String text = JsonBytes.canonical(ledger);
        Map<String, String> receipt = new LinkedHashMap<>();
        receipt.put("available", "true");
        receipt.put("vectorsTotal", String.valueOf(ledger.get("vectorsTotal").asInt()));
        receipt.put("vectorsRun", String.valueOf(ledger.get("vectorsRun").asInt()));
        receipt.put("vectorsPassed", String.valueOf(ledger.get("vectorsPassed").asInt()));
        receipt.put("vectorsFailed", String.valueOf(ledger.get("vectorsFailed").asInt()));
        receipt.put("vectorsNotRun", String.valueOf(ledger.get("vectorsNotRun").asInt()));
        receipt.put("vectorsDeclaredNotRunWithReason",
            String.valueOf(notRunWithReason(ledger.get("vectors"))));
        receipt.put("fasta", formatLine(ledger, "fasta"));
        receipt.put("gfa", formatLine(ledger, "gfa"));
        receipt.put("fhp-fasta", formatLine(ledger, "fhp-fasta"));
        receipt.put("fhgff3", formatLine(ledger, "fhgff3"));
        receipt.put("microdata", formatLine(ledger, "microdata"));
        receipt.put("jsonld", formatLine(ledger, "jsonld"));
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("acceptance-ledger.json"), text, StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder("baseline=FAIR-bioHeaders-Specification v0.6.0\n");
            receipt.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
            Files.writeString(dir.resolve("acceptance-ledger.properties"), sb.toString(),
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Not-run vectors that actually carry a non-blank reason - the completeness half. */
    private static long notRunWithReason(JsonNode vectors) {
        return java.util.stream.StreamSupport.stream(vectors.spliterator(), false)
            .filter(v -> "not-run".equals(v.path("state").asString("")))
            .filter(v -> !v.path("reason").asString("").isBlank())
            .count();
    }

    private static String formatLine(JsonNode ledger, String format) {
        JsonNode entry = ledger.path("formats").get(format);
        return entry == null ? "absent"
            : entry.get("passed").asInt() + "/" + entry.get("total").asInt() + " passed, "
                + entry.get("notRun").asInt() + " not run";
    }
}
