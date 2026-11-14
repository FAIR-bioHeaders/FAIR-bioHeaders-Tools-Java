/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.fairbioheaders.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The CLI, driven in-process through {@link Main#run(String[], PrintStream)} - no subprocess, no
 * shell, nothing foreign in the test path. Covers the exit codes, the rule V7 opt-in as an operator
 * sees it, and the parent-link flags.
 */
class CliTest {

    /** Runs the CLI and returns {exitCode, stdout}. */
    private static Result run(String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        int code = Main.run(args, out);
        out.flush();
        return new Result(code, bytes.toString(StandardCharsets.UTF_8));
    }

    private record Result(int code, String out) {
    }

    private static Path write(Path dir, String name, byte[] data) {
        try {
            Path file = dir.resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void versionNamesTheBundledReleaseAndTheRegisteredDraft() {
        Result r = run("version");
        assertEquals(0, r.code());
        assertTrue(r.out().contains("fair-bioheaders-java 0.1.0-SNAPSHOT"), r.out());
        assertTrue(r.out().contains("current = v0.6.0"), r.out());
        assertTrue(r.out().contains("rule V7, opt-in, not released"), r.out());
        assertTrue(r.out().contains("FHT@226f923"), r.out());
        assertTrue(r.out().contains("sha256 59ad9641adcb"), r.out());
        assertTrue(r.out().contains("FHT-Specification main"), r.out());
    }

    @Test
    void usageIsTheFailureModeForNoCommandAndUnknownFlags() {
        assertEquals(2, run().code());
        assertEquals(0, run("help").code());
        assertEquals(0, run("--help").code());
        Result unknown = run("verify", "--nonsense", "x.fa");
        assertEquals(2, unknown.code());
        assertTrue(unknown.out().contains("unknown flag: --nonsense"), unknown.out());
        assertEquals(2, run("verify").code(), "verify needs a file");
        assertEquals(2, run("verify", "--parent").code());
        assertEquals(2, run("verify", "--draft").code());
    }

    @Test
    void verifyReportsAValidFileAndATamperedOne(@TempDir Path dir) {
        byte[] good = Fixtures.fhrFasta(">seq1\nACGT\n");
        Path file = write(dir, "good.fhr.fasta", good);
        Result ok = run("verify", file.toString());
        assertEquals(0, ok.code(), ok.out());
        assertTrue(ok.out().contains("checksum verified; metadata valid"), ok.out());

        byte[] tampered = Fixtures.utf8(good).replace("ACGT", "ACGG").getBytes(StandardCharsets.UTF_8);
        Result bad = run("verify", write(dir, "tampered.fhr.fasta", tampered).toString());
        assertEquals(1, bad.code());
        assertTrue(bad.out().contains("[R3]"), bad.out());

        Result missing = run("verify", dir.resolve("absent.fasta").toString());
        assertEquals(1, missing.code());
        assertTrue(missing.out().contains("cannot read file"), missing.out());
    }

    @Test
    void theDraftLaneIsOffUnlessAskedAndOnWhenAsked(@TempDir Path dir) {
        byte[] fht = Fixtures.fhtFasta(">tx1\nAUGG\n");
        Path file = write(dir, "transcripts.fht.fasta", fht);

        Result closed = run("verify", file.toString());
        assertEquals(1, closed.code(), closed.out());
        assertFalse(closed.out().contains("V7"), closed.out());
        assertTrue(closed.out().contains("[V5]"),
            "the draft citation must still be read as FHR by rule V5: " + closed.out());

        Result open = run("verify", "--experimental-drafts", file.toString());
        assertEquals(0, open.code(), open.out());
        assertTrue(open.out().contains("rule V7 draft lane is ON"), open.out());
        assertTrue(open.out().contains("[V7]"), open.out());
        assertTrue(open.out().contains("commit 226f923c28b680edb3c546e99a751482d06aef1a"), open.out());
        assertTrue(open.out().contains("sha256 59ad9641adcb0e5591045c2d894c066d1adc9984cca84c5ff6502f8dad88487e"),
            open.out());
        assertTrue(open.out().contains("not a released schema"), open.out());
        assertTrue(open.out().contains("checksum verified; metadata valid"), open.out());

        Result named = run("verify", "--draft", "FHT", file.toString());
        assertEquals(0, named.code(), named.out());
        assertTrue(named.out().contains("[V7]"), named.out());
    }

    @Test
    void parentLinksAreOnlyConsultedWhenAParentIsGiven(@TempDir Path dir) {
        byte[] parent = Fixtures.fhrFasta(">seq1\nACGT\n");
        String truth = Fixtures.statedChecksum(parent);
        Path parentFile = write(dir, "parent.fhr.fasta", parent);
        Path childFile = write(dir, "child.fhgff3.gff3", Fixtures.fhgff3LinkedTo(truth));

        Result withoutParent = run("verify", childFile.toString());
        assertEquals(0, withoutParent.code(), withoutParent.out());
        assertFalse(withoutParent.out().contains("parent link"),
            "no --parent means no link verdicts at all: " + withoutParent.out());

        // A candidate parent that satisfies the link: CHECKED_PASS, and nothing is claimed about
        // the second link, whose parent was not supplied.
        Result passing = run("verify", "--parent", parentFile.toString(), childFile.toString());
        assertEquals(0, passing.code(), passing.out());
        assertTrue(passing.out().contains("parent link 0 (annotates FHR) CHECKED_PASS"), passing.out());
        assertTrue(passing.out().contains("parent link 1 (filteredFrom FHR) UNCHECKED"), passing.out());

        // Naming the parent of a link explicitly is what turns a stale claim into a rejection: the
        // child states a value the supplied file does not produce, so the verdict is CHECKED_FAIL
        // and the record is invalid (C3 is a rejection, not a note).
        String stale = "B".repeat(43) + "=";
        Path staleChild = write(dir, "stale.fhgff3.gff3", Fixtures.fhgff3LinkedTo(stale));
        Result failing = run("verify", "--parent", stale + "=" + parentFile, staleChild.toString());
        assertEquals(1, failing.code(), failing.out());
        assertTrue(failing.out().contains("parent link 0 (annotates FHR) CHECKED_FAIL"), failing.out());
        assertTrue(failing.out().contains("[" + "C3" + "]"), failing.out());

        // A candidate that satisfies no link changes nothing.
        byte[] unrelated = Fixtures.fhrFasta(">seq9\nTTTT\n");
        write(dir, "unrelated.fhr.fasta", unrelated);
        Result candidateOnly = run("verify", "--parent", dir.resolve("unrelated.fhr.fasta").toString(),
            staleChild.toString());
        assertEquals(0, candidateOnly.code(), candidateOnly.out());
        assertTrue(candidateOnly.out().contains("UNCHECKED"), candidateOnly.out());
        assertFalse(candidateOnly.out().contains("CHECKED_PASS"), candidateOnly.out());
    }
}
