/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The prose counts about this repository's own test runs are held to the workflow that enforces
 * them.
 *
 * <p>Round 13 measured the decay this prevents: {@code AGENTS.md} and {@code docs/STATUS.md}
 * stated 318 released executions while the suite measured 351, and {@code docs/NATIVE-ACCEPTANCE.md}
 * stated a draft guard bound of 238 while the workflow guarded 288. Nothing failed, because the
 * CI bound is a floor and prose is compared to nothing — a restated count with no check is a
 * claim, not a control (the class {@code FAIR-bioHeaders-Specification#241} names: a recorded
 * reference is not an executable check). The rule here is single-sourcing: the guard bounds live
 * at the {@code # guard-bound:}-marked invocations in {@code .github/workflows/build.yml}, the
 * file that enforces them; {@code docs/NATIVE-ACCEPTANCE.md}'s machine-readable bounds row must
 * quote exactly that pair plus the measured aggregate of the same-named job.
 *
 * <p>Runs in the released group: reads repository text only — no draft contract, no network.
 */
class ReportBoundConsistencyTest {

    private static Path repoFile(String rel) {
        Path p = Path.of(rel);
        if (Files.isRegularFile(p)) {
            return p;
        }
        for (String prefix : new String[] {"..", "../..", "../../.."}) {
            Path q = Path.of(prefix, rel);
            if (Files.isRegularFile(q)) {
                return q;
            }
        }
        return null;
    }

    private static String read(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The authoritative bounds, read from the marked guard invocations in build.yml. */
    private static Map<String, Integer> workflowBounds() {
        Path workflow = repoFile(".github/workflows/build.yml");
        assertNotNull(workflow, "build.yml must be reachable from the test working directory");
        Map<String, Integer> out = new LinkedHashMap<>();
        Matcher m = Pattern
            .compile("assert-reports\\.sh (\\d+)\\s+# guard-bound: (released|draft)")
            .matcher(read(workflow));
        while (m.find()) {
            String job = m.group(2);
            int bound = Integer.parseInt(m.group(1));
            if (out.containsKey(job)) {
                fail("build.yml marks two guard invocations as '" + job + "'; the markers must be unique");
            }
            out.put(job, bound);
        }
        assertEquals(2, out.size(),
            "build.yml must carry exactly the two marked guard invocations (released, draft); "
                + "a new guarded job must name its marker so this test knows which bound is which");
        return out;
    }

    @Test
    @DisplayName("the docs bounds row quotes exactly the workflow's guard pair")
    void docsBoundsMatchTheWorkflow() {
        Map<String, Integer> wf = workflowBounds();
        Path natDoc = repoFile("docs/NATIVE-ACCEPTANCE.md");
        assertNotNull(natDoc);
        String text = read(natDoc);
        Matcher m = Pattern
            .compile("guard-bound: released=(\\d+), measured=(\\d+); guard-bound: draft=(\\d+), measured=(\\d+)")
            .matcher(text);
        assertTrue(m.find(),
            "docs/NATIVE-ACCEPTANCE.md must carry the machine-readable bounds row "
                + "'guard-bound: released=<n>, measured=<n>; guard-bound: draft=<n>, measured=<n>'");
        assertEquals(wf.get("released"), Integer.parseInt(m.group(1)),
            "released guard bound: build.yml vs docs/NATIVE-ACCEPTANCE.md");
        assertEquals(wf.get("draft"), Integer.parseInt(m.group(3)),
            "draft guard bound: build.yml vs docs/NATIVE-ACCEPTANCE.md");

        int measuredReleased = Integer.parseInt(m.group(2));
        int measuredDraft = Integer.parseInt(m.group(4));
        // A floor may never sit above its own stated measurement: raising a floor to meet
        // reality is a labelled change at build.yml, and lowering reality below the floor is
        // exactly what the guard exists to catch.
        assertTrue(wf.get("released") <= measuredReleased,
            "released floor " + wf.get("released") + " exceeds the measured " + measuredReleased);
        assertTrue(wf.get("draft") <= measuredDraft,
            "draft floor " + wf.get("draft") + " exceeds the measured " + measuredDraft);

        // STATUS.md's measured split, when stated as "(<r> + <d>)", must agree with the row.
        Path status = repoFile("docs/STATUS.md");
        assertNotNull(status);
        Matcher s = Pattern.compile("\\*{0,2}(\\d+) measured in total[^*]*\\*{0,2}\\s*\\((\\d+) \\+ (\\d+)\\)")
            .matcher(read(status).replace("\n", " "));
        assertTrue(s.find(), "docs/STATUS.md must carry a recognizable measured total and split");
        do {
            int total = Integer.parseInt(s.group(1));
            int r = Integer.parseInt(s.group(2));
            int d = Integer.parseInt(s.group(3));
            assertEquals(r + d, total,
                "docs/STATUS.md states " + r + " + " + d + " but totals " + total);
            assertEquals(measuredReleased, r,
                "docs/STATUS.md's released measurement disagrees with the bounds row");
            assertEquals(measuredDraft, d,
                "docs/STATUS.md's draft measurement disagrees with the bounds row");
        } while (s.find());
    }
}
