/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Byte-level header scan of a FASTA, GFA or GFF3 file per FAIR-bioHeaders-Specification
 * docs/FORMAT.md rules R1-R7 and R10, plus docs/FHGFF3.md rule G1, and the
 * SHA-512/256 checksum coverage computation (R3/R4).
 *
 * <p>The scan mirrors the reference behaviour of {@code bioheaders.SequenceScan} and
 * {@code bioheaders.sequence_parts} in the Python toolkit: the file is read as bytes,
 * lines end at LF, CRLF, or a CR not followed by LF, and the terminator belongs to the
 * line (R1). Every byte except the one root-level checksum line and its terminator is
 * hashed (R3). Blank and ordinary comment lines inside the leading block are hashed but
 * are not part of the YAML text.
 */
final class HeaderScan {

    /** Defensive limits, matching the Python toolkit (FAIR-bioHeaders-Tools). */
    static final int MAX_HEADER_BYTES = 1 << 20;
    static final int MAX_HEADER_LINES = 10_000;

    // FORMAT.md R5, applied to the line content after the ";~"/"#~" prefix.
    // The value grammar is the canonical padded base64 of the 32-byte digest (R4).
    private static final String B64 = "[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=";
    static final Pattern CHECKSUM_LINE = Pattern.compile(
        " *checksum *: +(?:" + B64 + "|'" + B64 + "'|\"" + B64 + "\") *");
    // Loose root-level candidate, as recognized while streaming (R2): prefix, then
    // spaces/tabs, the key, optional spaces, a colon. Content is checked by R5 after.
    private static final Pattern CHECKSUM_CANDIDATE = Pattern.compile(
        "^[;#]~[ \\t]*checksum[ \\t]*:.*", Pattern.DOTALL);
    // FHGFF3.md G1 line 1.
    static final Pattern GFF3_VERSION_LINE = Pattern.compile("^##gff-version 3(?:\\.[0-9]+\\.[0-9]+)?[ \\t]*$");
    // YAML document markers and comments are not content lines (reference scan).
    private static final Pattern MARKER_LINE = Pattern.compile("^(?:%|---(?:[ \\t]|$)|\\.\\.\\.(?:[ \\t]|$)).*", Pattern.DOTALL);

    /** Chunk size for reading. */
    private static final int BUFFER = 1 << 16;

    private final Kind kind;

    private List<String> headerLines = new ArrayList<>();  // decoded content after prefix, no terminator
    private List<String> rawHeaderLines = new ArrayList<>(); // full line content (with prefix), for warnings
    private String checksumLineText;                        // content after the prefix of the excluded line
    private String statedChecksum;
    private String computedChecksum;

    // Digest state, as the reference SequenceScan keeps it: 'all' hashes every byte while the
    // header block can still produce a checksum line; 'excluded' is a copy taken just before the
    // current candidate line and updated with everything after it, so it is the R3 digest once the
    // file ends with exactly one candidate.
    private MessageDigest all = newDigest();
    private MessageDigest excluded;

    private HeaderScan(Kind kind) {
        this.kind = kind;
    }

    /** R7: FASTA/GFA files must not begin with a UTF-8 byte order mark. */
    static void checkBom(Kind kind, byte[] data) {
        checkBom(kind, data, data.length);
    }

    private static void checkBom(Kind kind, byte[] data, int length) {
        if (kind == Kind.GFF3) {
            return; // R7 names FASTA/GFA; a GFF3 BOM fails G1 line 1 anyway.
        }
        if (length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            throw new ScanException("R7", "file must not begin with a UTF-8 byte order mark");
        }
    }

    /** Run the scan over in-memory bytes; fills header lines, checksum state and both values. */
    static HeaderScan of(Kind kind, byte[] data) {
        try {
            return of(kind, new java.io.ByteArrayInputStream(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e); // not reachable for an in-memory stream
        }
    }

    /**
     * Run the scan over a stream, in one pass. Only the header lines are held (at most
     * {@link #MAX_HEADER_BYTES}); every other byte goes through the digest and is dropped, so a
     * file of any size is verified in bounded memory.
     */
    static HeaderScan of(Kind kind, InputStream in) throws IOException {
        HeaderScan scan = new HeaderScan(kind);
        scan.run(new Lines(in));
        return scan;
    }

    private void update(byte[] b, int off, int len) {
        if (all != null) {
            all.update(b, off, len);
        }
        if (excluded != null) {
            excluded.update(b, off, len);
        }
    }

    private void run(Lines lines) throws IOException {
        lines.ensure(3);
        checkBom(kind, lines.buf, lines.lim);
        byte[] prefix = kind.prefix();
        int lineNo = 0;
        int headerBytes = 0;
        int headerCount = 0;
        int rootIndent = -1;
        int matches = 0;

        Line line;
        while ((line = lines.next(this::update)) != null) {
            lineNo++;
            boolean isPrefix = line.startsWith(prefix);

            if (kind == Kind.GFF3 && lineNo == 1) {
                // Byte-level guard first: a line 1 that does not even start with the
                // directive is a G1 failure regardless of encoding.
                if (line.truncated || !line.startsWith(GFF3_VERSION)
                        || !GFF3_VERSION_LINE.matcher(decode(line.bytes, 0, line.contentLength, "G1")).matches()) {
                    throw new ScanException("G1",
                        "line 1 of a headed GFF3 file must match '##gff-version 3' (optionally 3.MINOR.PATCH)");
                }
            }

            if (isPrefix) {
                headerBytes += line.length;
                if (line.truncated || headerBytes > MAX_HEADER_BYTES) {
                    throw new ScanException("R10", "FHR header lines exceed the size limit");
                }
                headerCount++;
                if (headerCount > MAX_HEADER_LINES) {
                    throw new ScanException("R10", "FHR header lines exceed the line limit");
                }
                String content = decode(line.bytes, 0, line.contentLength, "R6"); // UTF-8 strict
                String metadata = content.substring(prefix.length);   // after the prefix
                rawHeaderLines.add(content);
                headerLines.add(stripTrailing(metadata));
                int indent = leadingSpacesTabs(metadata);
                String trimmed = metadata.strip();
                boolean tracks = !trimmed.isEmpty()
                    && trimmed.charAt(0) != '#'
                    && !MARKER_LINE.matcher(stripLeadingTabs(metadata)).matches();
                if (tracks) {
                    if (rootIndent < 0 || indent < rootIndent) {
                        rootIndent = indent;
                        matches = 0;
                        excluded = null;
                        checksumLineText = null;
                    }
                    if (indent == rootIndent
                            && CHECKSUM_CANDIDATE.matcher(content).matches()) {
                        matches++;
                        excluded = null;
                        if (matches == 1) {
                            excluded = copy(all);
                            checksumLineText = metadata;
                            statedChecksum = strictChecksumValue(metadata, lineNo);
                        }
                        all.update(line.bytes, 0, line.length); // not into 'excluded' (R3)
                        continue;
                    }
                }
            } else {
                boolean endLine = line.startsWith(GT);
                boolean hashComment = line.startsWith(HASH);
                boolean ends = (kind == Kind.FASTA && endLine)
                    || (kind == Kind.GFA && !line.blank && !hashComment)
                    || (kind == Kind.GFF3 && ((!line.blank && !hashComment) || line.isFastaDirective()));
                if (ends) {
                    endBlock(line);
                    break;
                }
            }
            if (!line.truncated) {
                update(line.bytes, 0, line.length);
            }
        }
        if (line != null) {
            lineNo++;
            lines.drainBody(prefix, lineNo, this::bodyUpdate);
        }

        if (matches != 1) {
            throw new ScanException("R2", "expected exactly one root-level checksum line, found " + matches);
        }
        computedChecksum = Base64.getEncoder().encodeToString(excluded.digest());
    }

    /** The line that ends the header block: hashed, and from here on no candidate can appear. */
    private void endBlock(Line line) {
        if (line != null && !line.truncated) {
            update(line.bytes, 0, line.length);
        }
        all = null; // no more checksum candidates: only 'excluded' is still needed
    }

    private void bodyUpdate(byte[] b, int off, int len) {
        if (excluded != null) {
            excluded.update(b, off, len);
        }
    }

    private static MessageDigest copy(MessageDigest digest) {
        try {
            return (MessageDigest) digest.clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException("SHA-512/256 digest cannot be copied", e);
        }
    }

    private static final byte[] GT = {'>'};
    private static final byte[] HASH = {'#'};
    private static final byte[] GFF3_VERSION = "##gff-version 3".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FASTA_DIRECTIVE = "##FASTA".getBytes(StandardCharsets.US_ASCII);

    /** Where a hashed byte range goes. */
    private interface Sink {
        void update(byte[] b, int off, int len);
    }

    /**
     * One line of the header block: its bytes (content and terminator) when it fits within
     * {@link #MAX_HEADER_BYTES}, or else the first bytes of it, with the whole line already
     * streamed to the digest.
     */
    private static final class Line {
        byte[] bytes;
        int length;          // bytes held, terminator included
        int contentLength;   // bytes held before the terminator
        boolean truncated;   // longer than the hold limit; the whole line went to the sink
        boolean blank;       // every content byte is a space or a tab
        boolean blankAfterDirective; // every content byte after "##FASTA" is a space or a tab

        boolean startsWith(byte[] p) {
            int have = truncated ? bytes.length : contentLength;
            if (have < p.length) {
                return false;
            }
            for (int k = 0; k < p.length; k++) {
                if (bytes[k] != p[k]) {
                    return false;
                }
            }
            return true;
        }

        /** Byte-level {@code ##FASTA} boundary line (docs/FHGFF3.md G1). */
        boolean isFastaDirective() {
            return startsWith(FASTA_DIRECTIVE) && blankAfterDirective;
        }
    }

    /**
     * Line splitting over a stream (R1): a line ends at LF, CRLF, or a CR not followed by LF, and
     * the terminator belongs to the line. Lines up to {@link #MAX_HEADER_BYTES} are held whole;
     * a longer one is streamed to the sink as it is read and only its start is kept.
     */
    private static final class Lines {
        private final InputStream in;
        final byte[] buf = new byte[BUFFER];
        int pos;
        int lim;
        private boolean eof;
        private byte[] hold = new byte[256];

        Lines(InputStream in) {
            this.in = in;
        }

        /** Make at least {@code n} bytes available unless the stream ends first. */
        void ensure(int n) throws IOException {
            if (lim - pos >= n) {
                return;
            }
            System.arraycopy(buf, pos, buf, 0, lim - pos);
            lim -= pos;
            pos = 0;
            while (lim < n && !eof) {
                int r = in.read(buf, lim, buf.length - lim);
                if (r < 0) {
                    eof = true;
                } else {
                    lim += r;
                }
            }
        }

        private boolean fill() throws IOException {
            pos = 0;
            lim = 0;
            ensure(1);
            return lim > 0;
        }

        Line next(Sink sink) throws IOException {
            if (pos == lim && !fill()) {
                return null;
            }
            Line line = new Line();
            line.blank = true;
            line.blankAfterDirective = true;
            int held = 0;
            long seen = 0;          // content bytes seen so far
            boolean truncated = false;
            byte[] head = null;
            while (true) {
                int end = pos;
                while (end < lim && buf[end] != '\n' && buf[end] != '\r') {
                    end++;
                }
                // Flags over the content bytes of this stretch.
                for (int k = pos; k < end; k++) {
                    byte c = buf[k];
                    boolean ws = c == ' ' || c == '\t';
                    if (!ws) {
                        line.blank = false;
                        if (seen + (k - pos) >= FASTA_DIRECTIVE.length) {
                            line.blankAfterDirective = false;
                        }
                    }
                }
                int stretch = end - pos;
                int term = 0;
                boolean done = false;
                if (end < lim) {
                    term = 1;
                    done = true;
                }
                // Append content (and the first terminator byte) to the hold, or to the sink.
                int count = stretch + term;
                if (!truncated && held + count > MAX_HEADER_BYTES) {
                    truncated = true;
                    head = java.util.Arrays.copyOf(hold, held);
                    sink.update(hold, 0, held);
                }
                if (truncated) {
                    sink.update(buf, pos, count);
                } else {
                    if (held + count > hold.length) {
                        hold = java.util.Arrays.copyOf(hold,
                            Math.max(held + count, Math.min(MAX_HEADER_BYTES, hold.length * 2)));
                    }
                    System.arraycopy(buf, pos, hold, held, count);
                    held += count;
                }
                seen += stretch;
                boolean cr = term == 1 && buf[end] == '\r';
                pos = end + term;
                if (done) {
                    if (cr) {
                        if (pos == lim) {
                            fill();
                        }
                        if (pos < lim && buf[pos] == '\n') {
                            if (truncated) {
                                sink.update(buf, pos, 1);
                            } else {
                                if (held + 1 > hold.length) {
                                    hold = java.util.Arrays.copyOf(hold, held + 1);
                                }
                                hold[held++] = '\n';
                            }
                            pos++;
                            term = 2;
                        }
                    }
                    line.contentLength = truncated ? -1 : held - term;
                    break;
                }
                if (!fill()) {
                    line.contentLength = truncated ? -1 : held;
                    break;
                }
            }
            line.truncated = truncated;
            if (truncated) {
                line.bytes = head;
                line.length = head.length;
            } else {
                line.bytes = java.util.Arrays.copyOf(hold, held);
                line.length = held;
            }
            return line;
        }

        /**
         * Everything after the header block: hashed in whole buffers, with only the line starts
         * looked at, for the R10 rule that no FHR line may follow the block.
         */
        void drainBody(byte[] prefix, int firstLineNo, Sink sink) throws IOException {
            int lineNo = firstLineNo;
            // 0 = at a line start, 1 = at a line start after prefix[0], 2 = inside a line,
            // 3 = just after a CR (a following LF belongs to the same terminator).
            int state = 0;
            while (pos < lim || fill()) {
                for (int k = pos; k < lim; k++) {
                    byte c = buf[k];
                    if (state == 3) {
                        state = 0;
                        if (c == '\n') {
                            continue;
                        }
                    }
                    if (state == 0) {
                        if (c == prefix[0]) {
                            state = 1;
                            continue;
                        }
                        state = 2;
                    } else if (state == 1) {
                        if (c == prefix[1]) {
                            throw new ScanException("R10",
                                "FHR header line after the header block at line " + lineNo);
                        }
                        state = 2;
                    }
                    if (c == '\n') {
                        lineNo++;
                        state = 0;
                    } else if (c == '\r') {
                        lineNo++;
                        state = 3;
                    }
                }
                sink.update(buf, pos, lim - pos);
                pos = lim;
            }
        }
    }

    /** Apply R5 to the loose candidate: the line must hold the value and nothing else. */
    private static String strictChecksumValue(String metadata, int lineNo) {
        Matcher m = CHECKSUM_LINE.matcher(metadata);
        if (!m.matches()) {
            throw new ScanException("R5",
                "checksum line at line " + lineNo + " must hold the value and nothing else");
        }
        String rest = metadata.replaceFirst("^ *checksum *: +", "");
        while (rest.endsWith(" ") || rest.endsWith("\t")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        if (rest.startsWith("'") || rest.startsWith("\"")) {
            return rest.substring(1, rest.length() - 1);
        }
        return rest;
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-512/256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK does not provide SHA-512/256", e);
        }
    }

    /** YAML text of the header: the decoded prefix-stripped lines joined by LF. */
    String yamlText() {
        return String.join("\n", headerLines);
    }

    /** Full content (minus terminator) of each FHR line, for R11 warning scans. */
    List<String> rawHeaderLines() {
        return rawHeaderLines;
    }

    /** The excluded line's text after the prefix, or null when there is no single candidate. */
    String checksumLineText() {
        return checksumLineText;
    }

    String statedChecksum() {
        return statedChecksum;
    }

    String computedChecksum() {
        return computedChecksum;
    }

    /** R6: header lines must decode as UTF-8 and must not hold U+0085, U+2028, U+2029. */
    private static String decode(byte[] data, int from, int to, String rule) {
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(data, from, to - from))
                .toString();
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                if (c == '\u0085' || c == '\u2028' || c == '\u2029') {
                    throw new ScanException("R6",
                        "FHR header lines must not contain U+0085, U+2028, or U+2029");
                }
            }
            return s;
        } catch (CharacterCodingException e) {
            throw new ScanException("R6", "FHR header lines must be UTF-8");
        }
    }

    private static int leadingSpacesTabs(String s) {
        int k = 0;
        while (k < s.length() && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) {
            k++;
        }
        return k;
    }

    private static String stripLeadingTabs(String s) {
        int k = 0;
        while (k < s.length() && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) {
            k++;
        }
        return s.substring(k);
    }

    /** rstrip of CR/LF only, as the reference header_text does. */
    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\r' || s.charAt(end - 1) == '\n')) {
            end--;
        }
        return s.substring(0, end);
    }
}
