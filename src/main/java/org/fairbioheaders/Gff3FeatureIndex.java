/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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

    /** What one row's attribute column says about that row's parent. */
    enum ParentState {
        /** No {@code Parent} tag on the row at all: the only state that can support a root claim. */
        ABSENT,
        /** A {@code Parent} tag with a readable value: the row is nested. */
        NESTED,
        /**
         * A {@code Parent} tag that is present but carries no value ({@code Parent=}), or a
         * {@code Parent} token the attribute grammar cannot parse at all (a bare {@code Parent} with
         * no {@code =}). Per the GFF3 attribute grammar {@code Parent=} is a <em>present</em> tag with
         * an empty value list, which is not the same assertion as the tag being absent - and neither
         * is an assertion this index is willing to read either way. A caller that needs to know
         * whether a row is a root gets "unknown" here, and must not report a root.
         */
        INDETERMINATE
    }

    /**
     * The decoded ID bytes, as the key. A {@link ByteBuffer} compares and hashes by remaining bytes,
     * which is what identity here means: the draft's checker indexes the percent-decoded bytes and
     * looks them up by the record ID's UTF-8 encoding ({@code validate_fhgm.py}: {@code targets.get(
     * feature_id.encode(), [])}), so matching two byte strings is the whole operation. It is NOT the
     * same operation as decoding each side to text first: a row ID of {@code %FF} is not valid UTF-8,
     * and a decoder that substitutes U+FFFD for it would then "match" a record that names U+FFFD -
     * a target invented out of a decoding failure, and a K4 PASS on nothing.
     */
    private final Map<ByteBuffer, List<String>> typesById;
    private final Map<ByteBuffer, ParentState> parentById;
    /** How many distinct decoded IDs in this file are not valid UTF-8 at all. */
    private final int undecodableIds;

    private Gff3FeatureIndex(Map<ByteBuffer, List<String>> typesById,
                             Map<ByteBuffer, ParentState> parentById, int undecodableIds) {
        this.typesById = typesById;
        this.parentById = parentById;
        this.undecodableIds = undecodableIds;
    }

    /**
     * Index the feature rows of a file's (decompressed) bytes.
     *
     * @throws MalformedRow when a data row does not have nine tab-separated columns
     */
    static Gff3FeatureIndex of(byte[] data) {
        Map<ByteBuffer, List<String>> index = new LinkedHashMap<>();
        Map<ByteBuffer, ParentState> parents = new LinkedHashMap<>();
        Set<ByteBuffer> undecodable = new HashSet<>();
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
            List<ByteBuffer> rowIds = new ArrayList<>();
            ParentState rowParent = ParentState.ABSENT;
            for (byte[] attribute : split(columns.get(8), (byte) ';')) {
                int eq = indexOf(attribute, (byte) '=');
                // A token with no '=' is not a tag/value pair: keep the whole token as the key and no
                // value, so a bare `Parent` is seen as a Parent tag that says nothing readable rather
                // than being skipped as unrecognised. Key matching stays exact-as-written, the way the
                // draft's own index does it, so ` Parent` with a leading space is another tag.
                byte[] key = eq < 0 ? attribute : Arrays.copyOfRange(attribute, 0, eq);
                byte[] value = eq < 0 ? new byte[0]
                    : Arrays.copyOfRange(attribute, eq + 1, attribute.length);
                if (Arrays.equals(key, "ID".getBytes(StandardCharsets.UTF_8))) {
                    byte[] decoded = unquote(value);
                    ByteBuffer id = ByteBuffer.wrap(decoded);
                    rowIds.add(id);
                    index.computeIfAbsent(id, k -> new ArrayList<>())
                        .add(new String(type, StandardCharsets.UTF_8));
                    if (!isValidUtf8(decoded)) {
                        // No record can name this ID: a record's text is valid UTF-8 by construction,
                        // and these bytes are not. It is still counted, so a K4 finding can say the
                        // target was not merely absent but unreadable.
                        undecodable.add(id);
                    }
                } else if (Arrays.equals(key, "Parent".getBytes(StandardCharsets.UTF_8))
                        && rowParent != ParentState.NESTED) {
                    // FHGM#29's gene-target condition reads this: a non-empty Parent makes the row
                    // nested; an empty or unparseable one makes the row's parenthood unknown, which is
                    // not the same as absent and must not be reported as a root.
                    rowParent = value.length > 0 ? ParentState.NESTED : ParentState.INDETERMINATE;
                }
            }
            for (ByteBuffer id : rowIds) {
                ParentState known = parents.get(id);
                parents.put(id, strongest(known, rowParent));
            }
        }
        return new Gff3FeatureIndex(index, parents, undecodable.size());
    }

    /**
     * Strict UTF-8 decoding, and nothing like {@code new String(bytes, UTF_8)}, which never fails:
     * it replaces every undecodable sequence with U+FFFD and so turns a read failure into a value.
     */
    private static boolean isValidUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)) != null;
        } catch (CharacterCodingException expected) {
            return false;
        }
    }

    /**
     * When several rows declare one ID, the most informative statement about parenthood wins: nested
     * outranks indeterminate outranks silent. Merging keeps a row elsewhere in the file from being
     * forgotten, and it never upgrades an indeterminate row into a root.
     */
    private static ParentState strongest(ParentState seen, ParentState next) {
        if (seen == null) {
            return next;
        }
        if (seen == ParentState.NESTED || next == ParentState.NESTED) {
            return ParentState.NESTED;
        }
        return seen == ParentState.INDETERMINATE || next == ParentState.INDETERMINATE
            ? ParentState.INDETERMINATE
            : ParentState.ABSENT;
    }

    /**
     * The feature types carrying the record's {@code featureId}, compared as the ID's UTF-8 bytes -
     * the same comparison the draft's checker makes. Empty when no local row declares it, including
     * when a row declares bytes this file cannot spell as text.
     */
    List<String> typesOf(String featureId) {
        return typesById.getOrDefault(ByteBuffer.wrap(featureId.getBytes(StandardCharsets.UTF_8)),
            List.of());
    }

    /**
     * How many decoded row IDs in this file are not valid UTF-8. A K4 finding quotes it so an
     * unresolvable target says whether the ID was absent or unreadable, which are different faults
     * with different owners.
     */
    int undecodableIdCount() {
        return undecodableIds;
    }

    /**
     * What the rows declaring {@code id} say about its parent. {@link ParentState#ABSENT} is also what
     * an unknown ID reports, and a caller must have resolved the ID before reading that as anything.
     */
    ParentState parentOf(String featureId) {
        return parentById.getOrDefault(
            ByteBuffer.wrap(featureId.getBytes(StandardCharsets.UTF_8)), ParentState.ABSENT);
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
