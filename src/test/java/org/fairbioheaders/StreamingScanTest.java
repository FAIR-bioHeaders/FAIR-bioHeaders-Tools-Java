/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scan reads files as a stream (review finding J3): line splitting, the excluded checksum line
 * and the late-header rule must not depend on where the reads happen to break, and memory must not
 * grow with the file.
 */
class StreamingScanTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** An input stream that hands out the bytes in chunks of the given sizes, cycling. */
    private static InputStream chunked(byte[] data, int... sizes) {
        return new InputStream() {
            private int pos;
            private int turn;

            @Override
            public int read() {
                return pos < data.length ? data[pos++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (pos >= data.length) {
                    return -1;
                }
                int n = Math.min(Math.min(len, sizes[turn++ % sizes.length]), data.length - pos);
                System.arraycopy(data, pos, b, off, n);
                pos += n;
                return n;
            }
        };
    }

    /**
     * An independent statement of R1/R3: split at LF, CRLF or a lone CR, drop the one line that
     * starts with the checksum key, hash the rest.
     */
    private static String reference(byte[] data, String checksumStart) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-512/256");
        byte[] key = utf8(checksumStart);
        int i = 0;
        while (i < data.length) {
            int end = i;
            while (end < data.length && data[end] != '\n' && data[end] != '\r') {
                end++;
            }
            int next = end;
            if (end < data.length) {
                next = data[end] == '\r' && end + 1 < data.length && data[end + 1] == '\n' ? end + 2 : end + 1;
            }
            boolean excluded = end - i >= key.length;
            for (int k = 0; excluded && k < key.length; k++) {
                excluded = data[i + k] == key[k];
            }
            if (!excluded) {
                md.update(data, i, next - i);
            }
            i = next;
        }
        return Base64.getEncoder().encodeToString(md.digest());
    }

    @Test
    void theDigestDoesNotDependOnWhereReadsBreak() throws Exception {
        String body = ">chr1 with\r\nACGT\rAC\r\n\nGT\n" + "N".repeat(70_000) + "\r\n>chr2\rTTTT";
        byte[] file = Fixtures.fhrFasta(body);
        String expected = reference(file, ";~ checksum:");
        assertEquals(expected, HeaderScan.of(Kind.FASTA, file).computedChecksum());
        for (int[] sizes : new int[][] {{1}, {2, 3}, {7}, {4096}, {65535, 1}, {1, 65536}}) {
            HeaderScan scan = HeaderScan.of(Kind.FASTA, chunked(file, sizes));
            assertEquals(expected, scan.computedChecksum(), "chunks " + java.util.Arrays.toString(sizes));
        }
    }

    @Test
    void crlfSplitAcrossTheBufferEdgeIsOneTerminator() throws Exception {
        // Put a CR exactly at the 64 KiB read boundary of the header block, with its LF after it.
        String filler = "# " + "x".repeat(65_536 - 1 - 2 - 1) + "\r\n";
        byte[] file = Fixtures.sealed(Kind.GFA, "schemaVersion: '2.1'\n",
            "S\t1\tACGT\n");
        byte[] withComment = new byte[file.length + filler.length()];
        System.arraycopy(utf8(filler), 0, withComment, 0, filler.length());
        System.arraycopy(file, 0, withComment, filler.length(), file.length);
        String streamed = HeaderScan.of(Kind.GFA, chunked(withComment, 65_535, 65_536)).computedChecksum();
        assertEquals(reference(withComment, "#~ checksum:"), streamed);
    }

    @Test
    void aLateHeaderLineIsFoundAcrossAnyBreakWithItsLineNumber() {
        byte[] sealed = Fixtures.fhrFasta(">chr1\nACGT\n" + "ACGT\n".repeat(20_000));
        byte[] file = (new String(sealed, StandardCharsets.UTF_8) + ";~late: 1\n").getBytes(StandardCharsets.UTF_8);
        int lines = new String(file, StandardCharsets.UTF_8).split("\n", -1).length - 1;
        for (int[] sizes : new int[][] {{1}, {65_536}, {3, 5}}) {
            ScanException e = assertThrows(ScanException.class,
                () -> HeaderScan.of(Kind.FASTA, chunked(file, sizes)));
            assertEquals("R10", e.rule());
            assertTrue(e.getMessage().endsWith("at line " + lines), e.getMessage());
        }
    }

    @Test
    void longLinesStreamInsteadOfBeingHeld() throws Exception {
        // A 3 MiB unwrapped sequence line and a 2 MiB GFA comment inside the header block.
        byte[] fasta = Fixtures.fhrFasta(">chr1\n" + "A".repeat(3 << 20) + "\n");
        assertEquals(reference(fasta, ";~ checksum:"),
            HeaderScan.of(Kind.FASTA, chunked(fasta, 8192)).computedChecksum());
        byte[] gfa = Fixtures.sealed(Kind.GFA, "schemaVersion: '2.1'\n", "S\t1\tACGT\n");
        byte[] commented = new byte[gfa.length + (2 << 20) + 2];
        commented[0] = '#';
        java.util.Arrays.fill(commented, 1, (2 << 20) + 1, (byte) 'c');
        commented[(2 << 20) + 1] = '\n';
        System.arraycopy(gfa, 0, commented, (2 << 20) + 2, gfa.length);
        assertEquals(reference(commented, "#~ checksum:"),
            HeaderScan.of(Kind.GFA, chunked(commented, 8192)).computedChecksum());
    }

    @Test
    void anOversizedHeaderLineIsStillTheSizeLimit() {
        byte[] file = utf8(";~x-note: " + "y".repeat(HeaderScan.MAX_HEADER_BYTES) + "\n>c\nA\n");
        ScanException e = assertThrows(ScanException.class, () -> HeaderScan.of(Kind.FASTA, file));
        assertEquals("R10", e.rule());
        assertTrue(e.getMessage().contains("size limit"), e.getMessage());
    }

    /**
     * Memory does not grow with the file: a generated body just over 2 GiB - more than any Java array can
     * hold - is verified from a stream that is never materialised.
     */
    @Test
    void aFileLargerThanAnyArrayVerifies() throws Exception {
        byte[] head = Fixtures.fhrFasta("");
        long bodyBytes = (2L << 30) + (1 << 20); // past Integer.MAX_VALUE
        byte[] line = utf8("ACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGT\n");
        InputStream stream = new InputStream() {
            private long pos = -head.length - 6;

            @Override
            public int read() {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (pos < -6) {
                    int n = (int) Math.min(len, -6 - pos);
                    System.arraycopy(head, (int) (pos + head.length + 6), b, off, n);
                    pos += n;
                    return n;
                }
                if (pos < 0) {
                    byte[] header = utf8(">chr1\n");
                    int n = (int) Math.min(len, -pos);
                    System.arraycopy(header, (int) (pos + 6), b, off, n);
                    pos += n;
                    return n;
                }
                if (pos >= bodyBytes) {
                    return -1;
                }
                int n = (int) Math.min(len, bodyBytes - pos);
                for (int done = 0; done < n; ) {
                    int at = (int) ((pos + done) % line.length);
                    int step = Math.min(n - done, line.length - at);
                    System.arraycopy(line, at, b, off + done, step);
                    done += step;
                }
                pos += n;
                return n;
            }
        };
        HeaderScan scan = HeaderScan.of(Kind.FASTA, stream);
        assertEquals(44, scan.computedChecksum().length());
        assertFalse(scan.computedChecksum().equals(scan.statedChecksum()), "the body was hashed");
    }

    // ------------------------------------------------------------------ gzip

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    @Test
    void membersAndNulPaddingDecompressAsThePythonReaderDoes(@TempDir Path dir) throws Exception {
        byte[] file = Fixtures.fhrFasta(">chr1\nACGT\n");
        int half = file.length / 2;
        byte[] members = concat(gzip(java.util.Arrays.copyOf(file, half)),
            gzip(java.util.Arrays.copyOfRange(file, half, file.length)));
        byte[] padded = concat(members, new byte[512]);
        for (byte[] gz : new byte[][] {gzip(file), members, padded}) {
            Path p = dir.resolve("g.fasta.gz");
            Files.write(p, gz);
            VerifyResult r = new BioHeaders().verifyFile(p);
            assertTrue(r.isValid(), r.issues().toString());
            assertEquals(new BioHeaders().verify(file, Kind.FASTA).computedChecksum(), r.computedChecksum());
            assertEquals(file.length, BioHeaders.readAll(p).length);
        }
    }

    @Test
    void brokenGzipIsAnInvalidFileNotACrash(@TempDir Path dir) throws Exception {
        byte[] good = gzip(Fixtures.fhrFasta(">chr1\nACGT\n"));
        byte[] badCrc = good.clone();
        badCrc[badCrc.length - 6] ^= 0x01;
        byte[][] cases = {
            java.util.Arrays.copyOf(good, good.length - 3),          // truncated trailer
            java.util.Arrays.copyOf(good, good.length / 2),          // truncated deflate data
            concat(good, utf8("garbage")),                           // trailing garbage
            concat(good, new byte[4], utf8("x")),                    // garbage after padding
            badCrc,
        };
        for (byte[] gz : cases) {
            Path p = dir.resolve("b.fa.gz");
            Files.write(p, gz);
            VerifyResult r = new BioHeaders().verifyFile(p);
            assertEquals(VerifyResult.Status.INVALID, r.status());
            assertTrue(r.hasRule("R1"), r.issues().toString());
        }
    }

    @Test
    void inMemoryDecompressionIsBounded() throws Exception {
        byte[] bomb = gzip(new byte[4 << 20]);
        IOException e = assertThrows(IOException.class, () -> Gunzip.decompress(bomb, 1 << 20));
        assertTrue(e.getMessage().contains("verifyFile"), e.getMessage());
        assertEquals(4 << 20, Gunzip.decompress(bomb, 8 << 20).length);
    }

    // ---------------------------------------------------------- containers

    @Test
    void containersFollowTheToolkitExtensionTable() {
        assertEquals(Kind.GFA, BioHeaders.kindFor("graph.gfa.bgz"));
        assertEquals(Kind.GFF3, BioHeaders.kindFor("genes.gff"));
        assertEquals(Kind.GFF3, BioHeaders.kindFor("GENES.GFF3.GZ"));
        assertEquals(Kind.GFF3, BioHeaders.kindFor("genes.gff.bgz"));
        assertEquals(Kind.FASTA, BioHeaders.kindFor("prot.faa"));
        assertEquals(Kind.FASTA, BioHeaders.kindFor("x.fhr.fasta.bgz"));
        for (String name : new String[] {"noext", "x.txt", "x.gz", "x.fa.bgzip", "x.json", ".fa", "x.fhr"}) {
            assertThrows(IllegalArgumentException.class, () -> BioHeaders.kindFor(name), name);
        }
    }

    @Test
    void aBgzippedGfaIsReadAsGfa(@TempDir Path dir) throws Exception {
        byte[] gfa = Fixtures.sealed(Kind.GFA, "schema: https://w3id.org/fair-bioheaders/fhr/v0.6.0\n"
            + "schemaVersion: '2.1'\n", "S\t1\tACGT\n");
        Path p = dir.resolve("graph.gfa.bgz");
        Files.write(p, gzip(gfa));
        VerifyResult r = new BioHeaders().verifyFile(p);
        assertFalse(r.hasRule("R2"), "read with the GFA prefix: " + r.issues());
        assertEquals(new BioHeaders().verify(gfa, Kind.GFA).computedChecksum(), r.computedChecksum());
    }

    @Test
    void controlCharactersInAQuotedValueAreEscaped() {
        byte[] file = Fixtures.fhrFasta(">c\nA\n", "relatedLink:", "- \"javascript:\\e[2J\\ralert(1)\"");
        VerifyResult r = new BioHeaders().verify(file, Kind.FASTA);
        String message = r.issues().stream().filter(i -> i.rule().equals("R14")).findFirst()
            .orElseThrow().message();
        assertFalse(message.chars().anyMatch(c -> c < 0x20), message);
        assertTrue(message.contains("\\x1B[2J\\x0Dalert(1)"), message);
    }

    @Test
    void randomBreaksAgreeWithTheInMemoryScan() throws Exception {
        Random random = new Random(7);
        byte[] file = Fixtures.fhrFasta(">a\r\nAC\rGT\n\r\n>b\n" + "T".repeat(100_000) + "\r");
        String expected = HeaderScan.of(Kind.FASTA, file).computedChecksum();
        for (int round = 0; round < 20; round++) {
            int[] sizes = new int[5];
            for (int k = 0; k < sizes.length; k++) {
                sizes[k] = 1 + random.nextInt(70_000);
            }
            assertEquals(expected, HeaderScan.of(Kind.FASTA, chunked(file, sizes)).computedChecksum());
        }
    }
}
