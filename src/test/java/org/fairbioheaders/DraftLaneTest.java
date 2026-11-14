/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

/**
 * Rule V7 read through the API, using the proof registry: with the lane off nothing changes, with
 * the lane on the pinned draft bytes validate the record and every record says so - in its
 * {@link VerifyResult} and on the {@link DraftLog} logger.
 */
class DraftLaneTest {

    private static final String TESTTYPES = "/org/fairbioheaders/testtypes/";
    private static final String PRT_CITATION =
        "https://raw.githubusercontent.com/FAIR-bioHeaders/Proof-Specification/main/prt.json";
    private static final String COMMIT = "000102030405060708090a0b0c0d0e0f10111213";
    private static final String SHA256 =
        "3490101dc053a38d07399185e6dfd28a3ecbb6bc966b813cf8f8e90359e1fed9";

    private static BioHeaders proof(Options options) {
        return new BioHeaders(new SchemaBundle(options, TESTTYPES, "bundled-proof.json", TESTTYPES));
    }

    /** A FASTA header whose checksum line is a placeholder: the pipeline runs, R3 reports. */
    private static byte[] prtRecord(String yamlBody) {
        StringBuilder sb = new StringBuilder();
        for (String line : yamlBody.split("\n", -1)) {
            if (!line.isEmpty()) {
                sb.append(";~ ").append(line).append('\n');
            }
        }
        sb.append(";~ checksum: ").append("A".repeat(43)).append("=\n");
        sb.append(">seq1\nACGT\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void laneOffNeverSelectsTheDraft() {
        VerifyResult result = proof(Options.defaults())
            .verify(prtRecord("schema: " + PRT_CITATION + "\nprotocol: https://example.org/p"),
                Kind.FASTA);
        assertFalse(result.hasRule("V7"), "no draft warning without the opt-in");
        // Rule V5 reads it as FHR, so the FHR contract is what reports on it: no 'protocol'
        // requirement, and 'protocol' is an unknown key there.
        assertTrue(result.errors().stream().anyMatch(i -> i.rule().equals("schema")
            && i.message().contains("protocol")), result.issues().toString());
    }

    @Test
    void laneOnValidatesAgainstThePinnedBytesAndWarns() {
        BioHeaders reader = proof(Options.builder().enableDraftContracts(true).build());
        VerifyResult ok = reader.verify(
            prtRecord("schema: " + PRT_CITATION + "\nprotocol: https://example.org/p\n"
                + "marker: proof-draft"),
            Kind.FASTA);
        // The proof contract requires 'protocol' and closes the top level; none of those errors
        // appear, so the record was read against the pinned draft bytes. The only error left is
        // the deliberate placeholder checksum, and the only warning is the lane's own.
        assertEquals(List.of("R3", "V7"), codes(ok), ok.issues().toString());
        assertTrue(ok.hasRule("V7"), "every record read through the lane carries the V7 warning");
        Issue warning = ok.issues().stream().filter(i -> i.rule().equals("V7")).findFirst().orElseThrow();
        assertEquals(Severity.WARNING, warning.severity());
        assertEquals("PRT: warning: schema " + PRT_CITATION + " is an unreleased draft (commit "
            + COMMIT + ", sha256 " + SHA256 + " of those bytes); read against that pinned draft, "
            + "not a released schema", warning.message());
    }

    @Test
    void theDraftContractRejectsWhatTheReleasedContractsWouldAccept() {
        BioHeaders reader = proof(Options.builder().enableDraftContracts(true).build());
        VerifyResult missing = reader.verify(
            prtRecord("schema: " + PRT_CITATION + "\nmarker: proof-draft"), Kind.FASTA);
        assertTrue(missing.errors().stream().anyMatch(i -> i.message().contains("protocol")),
            "'protocol' is required by the draft: " + missing.issues());

        VerifyResult unknownKey = reader.verify(
            prtRecord("schema: " + PRT_CITATION + "\nprotocol: https://example.org/p\nnotAField: 1"),
            Kind.FASTA);
        assertTrue(unknownKey.errors().stream().anyMatch(i -> i.message().contains("notAField")),
            "the draft closes its top level: " + unknownKey.issues());

        VerifyResult badConst = reader.verify(
            prtRecord("schema: " + PRT_CITATION + "\nprotocol: https://example.org/p\n"
                + "marker: something-else"), Kind.FASTA);
        assertFalse(badConst.isValid());
        assertTrue(badConst.errors().stream().anyMatch(i -> i.message().contains("constant value")),
            "the draft's const keyword fires: " + badConst.issues());
    }

    @Test
    void theR14FieldTableComesFromTheRegistryRow() {
        BioHeaders reader = proof(Options.builder().enableDraftContracts(true).build());
        VerifyResult result = reader.verify(
            prtRecord("schema: " + PRT_CITATION + "\nprotocol: javascript:alert(1)\n"
                + "relatedLink:\n- javascript:alert(2)\nmarker: proof-draft"), Kind.FASTA);
        List<String> r14 = result.warnings().stream().filter(i -> i.rule().equals("R14"))
            .map(i -> i.message()).toList();
        assertEquals(2, r14.size(), result.issues().toString());
        assertTrue(r14.get(0).contains("protocol"), r14.toString());
        assertTrue(r14.get(1).contains("relatedLink[]"), r14.toString());
    }

    @Test
    void theWarningIsAlsoLogged() {
        Logger logger = Logger.getLogger(DraftLog.LOGGER_NAME);
        Level previous = logger.getLevel();
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        Collecting handler = new Collecting();
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        try {
            BioHeaders reader = proof(Options.builder().enableDraftContracts(true).build());
            VerifyResult a = reader.verify(
                prtRecord("schema: " + PRT_CITATION + "\nprotocol: https://example.org/a"), Kind.FASTA);
            VerifyResult b = reader.verify(
                prtRecord("schema: " + PRT_CITATION + "\nprotocol: https://example.org/b"), Kind.FASTA);
            assertEquals(2, handler.records.size(),
                "one log record per record read through the lane, not one per JVM");
            assertTrue(handler.records.stream().allMatch(r -> r.getLevel() == Level.WARNING));
            assertTrue(handler.records.stream().allMatch(r -> r.getMessage().contains("sha256 " + SHA256)));
            assertNotNull(a.metadata());
            assertNotNull(b.metadata());
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
            logger.setUseParentHandlers(previousUseParentHandlers);
        }
    }

    @Test
    void releasedReadsAreNotLogged() {
        Logger logger = Logger.getLogger(DraftLog.LOGGER_NAME);
        Collecting handler = new Collecting();
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        try {
            BioHeaders reader = proof(Options.builder().enableDraftContracts(true).build());
            reader.verify(prtRecord("schema: https://w3id.org/fair-bioheaders/fhr/v0.6.0"), Kind.FASTA);
            assertTrue(handler.records.isEmpty(), "released readings stay quiet");
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(true);
        }
    }

    private static List<String> codes(VerifyResult result) {
        return result.issues().stream().map(Issue::rule).toList();
    }

    private static final class Collecting extends Handler {
        final List<LogRecord> records = new java.util.ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
