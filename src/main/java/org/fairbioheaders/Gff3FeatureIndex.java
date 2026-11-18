/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The local feature identifiers a GFF3 body declares, in the form the FHGM draft's model-level
 * rules need: a decoded {@code ID=} value mapped to the feature types that carry it.
 *
 * <p>This is not a GFF3 validator - syntax, Sequence Ontology and biological checks stay in
 * {@code gff3-validator}. It answers one narrower question, "does this record's {@code featureId}
 * resolve to exactly one local gene row", and it reads only as much of the feature layer as that
 * needs: the tab-separated rows before {@code ##FASTA}, comment lines (including the {@code #~}
 * header block) skipped.
 */
final class Gff3FeatureIndex {

    /** A row that is not nine columns: the feature layer cannot be read as GFF3. */
    static final class MalformedRow extends RuntimeException {
        private static final long serialVersionUID = 1L;

        MalformedRow(int lineNumber) {
            super("expected nine columns in each GFF3 feature row, row " + lineNumber
                + " has a different count");
        }
    }

    private final Map<String, List<String>> typesById;
    private final Set<String> idsWithParent;

    private Gff3FeatureIndex(Map<String, List<String>> typesById,
                             Set<String> idsWithParent) {
        this.typesById = typesById;
        this.idsWithParent = idsWithParent;
    }

    /**
     * Index the feature rows of a file's (decompressed) bytes.
     *
     * @throws MalformedRow when a data row does not have nine tab-separated columns
     */
    static Gff3FeatureIndex of(byte[] data) {
        Map<String, List<String>> index = new LinkedHashMap<>();
        Set<String> withParent = new HashSet<>();
        int lineNumber = 0;
        for (byte[] line : splitLines(data)) {
            lineNumber++;
            if (isExact(line, "##FASTA")) {
                break;
            }
            if (isBlank(line) || line[0] == '#') {
                continue;
            }
            List<byte[]> columns = split(line, (byte) '\t');
            if (columns.size() != 9) {
                throw new MalformedRow(lineNumber);
            }
            byte[] type = columns.get(2);
            List<String> rowIds = new ArrayList<>();
            boolean rowHasParent = false;
            for (byte[] attribute : split(columns.get(8), (byte) ';')) {
                int eq = indexOf(attribute, (byte) '=');
                if (eq < 0) {
                    continue;
                }
                byte[] key = Arrays.copyOfRange(attribute, 0, eq);
                byte[] value = Arrays.copyOfRange(attribute, eq + 1, attribute.length);
                if (Arrays.equals(key, "ID".getBytes(StandardCharsets.UTF_8))) {
                    String decoded = new String(unquote(value), StandardCharsets.UTF_8);
                    rowIds.add(decoded);
                    index.computeIfAbsent(decoded, k -> new ArrayList<>())
                        .add(new String(type, StandardCharsets.UTF_8));
                } else if (Arrays.equals(key, "Parent".getBytes(StandardCharsets.UTF_8))
                        && value.length > 0) {
                    // A non-empty Parent attribute: whatever ID this row carries, it names a
                    // nested feature, not a root-level one (FHGM#29's gene-target condition).
                    rowHasParent = true;
                }
            }
            if (rowHasParent) {
                withParent.addAll(rowIds);
            }
        }
        return new Gff3FeatureIndex(index, withParent);
    }

    /** The feature types carrying {@code id}; empty when no local row declares it. */
    List<String> typesOf(String id) {
        return typesById.getOrDefault(id, List.of());
    }

    /** Whether any row declaring {@code id} carries a non-empty {@code Parent} attribute. */
    boolean hasParent(String id) {
        return idsWithParent.contains(id);
    }

    int size() {
        return typesById.size();
    }

    /** R1's line model: LF, CRLF, or a CR not followed by LF. */
    private static List<byte[]> splitLines(byte[] data) {
        List<byte[]> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            byte b = data[i];
            if (b == '\n') {
                lines.add(Arrays.copyOfRange(data, start, i));
                start = i + 1;
            } else if (b == '\r') {
                boolean crlf = i + 1 < data.length && data[i + 1] == '\n';
                lines.add(Arrays.copyOfRange(data, start, i));
                start = crlf ? i + 2 : i + 1;
                if (crlf) {
                    i++;
                }
            }
        }
        if (start < data.length) {
            lines.add(Arrays.copyOfRange(data, start, data.length));
        }
        return lines;
    }

    private static boolean isBlank(byte[] line) {
        for (byte b : line) {
            if (b != ' ' && b != '\t') {
                return false;
            }
        }
        return true;
    }

    private static boolean isExact(byte[] line, String text) {
        return Arrays.equals(line, text.getBytes(StandardCharsets.UTF_8));
    }

    private static List<byte[]> split(byte[] data, byte separator) {
        List<byte[]> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == separator) {
                parts.add(Arrays.copyOfRange(data, start, i));
                start = i + 1;
            }
        }
        parts.add(Arrays.copyOfRange(data, start, data.length));
        return parts;
    }

    private static int indexOf(byte[] data, byte value) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Percent-decoding over raw bytes - the same rule GFF3 states, and the reason this is written
     * here rather than delegating to a URL codec: {@code %} not followed by two hex digits stays
     * literal, and no character set or {@code +}-as-space interpretation is applied.
     */
    static byte[] unquote(byte[] value) {
        ByteBuffer out = ByteBuffer.allocate(value.length);
        for (int i = 0; i < value.length; i++) {
            if (value[i] == '%' && i + 2 < value.length
                    && hex(value[i + 1]) >= 0 && hex(value[i + 2]) >= 0) {
                out.put((byte) ((hex(value[i + 1]) << 4) | hex(value[i + 2])));
                i += 2;
            } else {
                out.put(value[i]);
            }
        }
        out.flip();
        byte[] result = new byte[out.remaining()];
        out.get(result);
        return result;
    }

    private static int hex(byte b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        return -1;
    }
}
