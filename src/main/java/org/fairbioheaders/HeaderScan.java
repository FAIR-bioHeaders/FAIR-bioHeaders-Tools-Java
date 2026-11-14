/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

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

    private final Kind kind;
    private final byte[] data;

    private List<String> headerLines = new ArrayList<>();  // decoded content after prefix, no terminator
    private List<String> rawHeaderLines = new ArrayList<>(); // full line content (with prefix), for warnings
    private int checksumLine = -1;                          // index of the excluded line
    private String statedChecksum;
    private String computedChecksum;

    private HeaderScan(Kind kind, byte[] data) {
        this.kind = kind;
        this.data = data;
    }

    /** R7: FASTA/GFA files must not begin with a UTF-8 byte order mark. */
    static void checkBom(Kind kind, byte[] data) {
        if (kind == Kind.GFF3) {
            return; // R7 names FASTA/GFA; a GFF3 BOM fails G1 line 1 anyway.
        }
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            throw new ScanException("R7", "file must not begin with a UTF-8 byte order mark");
        }
    }

    /** Run the scan; fills header lines, checksum state, and both checksum values. */
    static HeaderScan of(Kind kind, byte[] data) {
        HeaderScan scan = new HeaderScan(kind, data);
        scan.run();
        return scan;
    }

    private void run() {
        byte[] prefix = kind.prefix();
        int n = data.length;
        int lineNo = 0;
        int i = 0;
        boolean inBlock = true;
        int headerBytes = 0;
        int headerCount = 0;
        int rootIndent = -1;
        int matches = 0;
        List<Integer> hashedRanges = new ArrayList<>(); // start/end pairs; the checksum line is dropped after

        while (i < n) {
            lineNo++;
            int end = i;
            while (end < n && data[end] != '\n' && data[end] != '\r') {
                end++;
            }
            int next = end;
            if (end < n) {
                if (data[end] == '\r' && end + 1 < n && data[end + 1] == '\n') {
                    next = end + 2;
                } else {
                    next = end + 1;
                }
            }
            boolean isPrefix = startsWith(data, i, end, prefix);

            if (!inBlock) {
                if (isPrefix) {
                    throw new ScanException("R10",
                        "FHR header line after the header block at line " + lineNo);
                }
                hashedRanges.add(i);
                hashedRanges.add(next);
                i = next;
                continue;
            }

            if (kind == Kind.GFF3 && lineNo == 1) {
                // Byte-level guard first: a line 1 that does not even start with the
                // directive is a G1 failure regardless of encoding.
                if (!startsWithAscii(data, i, end, "##gff-version 3")
                        || !GFF3_VERSION_LINE.matcher(decode(data, i, end, "G1")).matches()) {
                    throw new ScanException("G1",
                        "line 1 of a headed GFF3 file must match '##gff-version 3' (optionally 3.MINOR.PATCH)");
                }
            }

            if (isPrefix) {
                headerBytes += next - i;
                if (headerBytes > MAX_HEADER_BYTES) {
                    throw new ScanException("R10", "FHR header lines exceed the size limit");
                }
                headerCount++;
                if (headerCount > MAX_HEADER_LINES) {
                    throw new ScanException("R10", "FHR header lines exceed the line limit");
                }
                String content = decode(data, i, end, "R6");            // line minus terminator, UTF-8 strict
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
                        checksumLine = -1;
                    }
                    if (indent == rootIndent
                            && CHECKSUM_CANDIDATE.matcher(content).matches()) {
                        matches++;
                        if (matches == 1) {
                            checksumLine = hashedRanges.size() / 2; // this line's pair index
                            statedChecksum = strictChecksumValue(metadata, lineNo);
                        }
                    }
                }
            } else {
                boolean endLine = startsWithAscii(data, i, end, ">");
                boolean blank = isBlank(data, i, end);
                boolean hashComment = startsWithAscii(data, i, end, "#");
                if (kind == Kind.FASTA && endLine) {
                    inBlock = false;
                } else if (kind == Kind.GFA && !blank && !hashComment) {
                    inBlock = false;
                } else if (kind == Kind.GFF3
                        && ((!blank && !hashComment) || isFastaDirective(data, i, end))) {
                    inBlock = false;
                }
            }
            hashedRanges.add(i);
            hashedRanges.add(next);
            i = next;
        }

        if (matches != 1) {
            throw new ScanException("R2", "expected exactly one root-level checksum line, found " + matches);
        }

        MessageDigest md = newDigest();
        for (int r = 0; r < hashedRanges.size(); r += 2) {
            if (r / 2 == checksumLine) {
                continue; // R3: the checksum line and its terminator are excluded
            }
            md.update(data, hashedRanges.get(r), hashedRanges.get(r + 1) - hashedRanges.get(r));
        }
        byte[] digest = md.digest();
        computedChecksum = Base64.getEncoder().encodeToString(digest);
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

    String statedChecksum() {
        return statedChecksum;
    }

    String computedChecksum() {
        return computedChecksum;
    }

    // ---- byte helpers -------------------------------------------------------

    private static boolean startsWith(byte[] data, int from, int to, byte[] prefix) {
        if (to - from < prefix.length) {
            return false;
        }
        for (int k = 0; k < prefix.length; k++) {
            if (data[from + k] != prefix[k]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] data, int from, int to, String s) {
        byte[] p = s.getBytes(StandardCharsets.US_ASCII);
        return startsWith(data, from, to, p);
    }

    private static boolean isBlank(byte[] data, int from, int to) {
        for (int k = from; k < to; k++) {
            if (data[k] != ' ' && data[k] != '\t') {
                return false;
            }
        }
        return true;
    }

    /** Byte-level {@code ##FASTA} boundary line (docs/FHGFF3.md G1). */
    private static boolean isFastaDirective(byte[] data, int from, int to) {
        if (!startsWithAscii(data, from, to, "##FASTA")) {
            return false;
        }
        for (int k = from + 7; k < to; k++) {
            if (data[k] != ' ' && data[k] != '\t') {
                return false;
            }
        }
        return true;
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
