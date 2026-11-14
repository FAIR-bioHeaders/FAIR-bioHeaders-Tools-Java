/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.DocumentStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.MappingStartEvent;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.events.SequenceStartEvent;
import org.yaml.snakeyaml.parser.Parser;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.StreamReader;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * FHR-header YAML loading per FORMAT.md R8, R12 and R13, mirroring the reference
 * {@code bioheaders._Loader} of the Python toolkit: plain scalars are typed with the
 * YAML 1.2 core schema and must read the same way under YAML 1.1 (the R12 portability
 * rule); anchors, aliases, merge keys, explicit tags, duplicate keys and document
 * markers beyond one document are rejected (R8, R12); non-finite numbers are rejected
 * (R13).
 *
 * <p>Scalar resolution is implemented here and does not rely on any parser's implicit
 * resolver, so behavior matches the toolkit's {@code portable_plain_tag()} regardless
 * of the SnakeYAML resolver version. The patterns below are transcribed from PyYAML
 * 6.0.3's resolver table (YAML 1.1, re.VERBOSE: ignorable whitespace removed) and the
 * toolkit's YAML 1.2 core table; the toolkit maps YAML 1.1 timestamps to strings
 * ("plain scalars that look like dates or times are strings", R12) and adds
 * {@code y/Y/n/N} to the YAML 1.1 boolean set.
 */
final class PortableYaml {

    static final int MAX_YAML_NODES = 20_000;
    static final int MAX_YAML_DEPTH = 64;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    // ---- YAML 1.2 core schema (toolkit _YAML12_TYPES) ----------------------
    private static final Pattern N12 = Pattern.compile("(?:~|null|Null|NULL|)");
    private static final Pattern B12 = Pattern.compile("true|True|TRUE|false|False|FALSE");
    private static final Pattern I12 = Pattern.compile("[-+]?[0-9]+|0o[0-7]+|0x[0-9a-fA-F]+");
    private static final Pattern F12 = Pattern.compile(
        "[-+]?(?:\\.[0-9]+|[0-9]+(?:\\.[0-9]*)?)(?:[eE][-+]?[0-9]+)?"
        + "|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN)");

    // ---- YAML 1.1 as PyYAML resolves it (timestamp -> string, plus y/n) ----
    private static final Pattern N11 = N12;
    private static final Pattern B11 = Pattern.compile(
        "yes|Yes|YES|no|No|NO|true|True|TRUE|false|False|FALSE|on|On|ON|off|Off|OFF");
    private static final Pattern I11 = Pattern.compile(
        "[-+]?0b[0-1_]+|[-+]?0[0-7_]+|[-+]?(?:0|[1-9][0-9_]*)"
        + "|[-+]?0x[0-9a-fA-F_]+|[-+]?[1-9][0-9_]*(?::[0-5]?[0-9])+");
    private static final Pattern F11 = Pattern.compile(
        "[-+]?(?:[0-9][0-9_]*)\\.[0-9_]*(?:[eE][-+][0-9]+)?"
        + "|\\.[0-9][0-9_]*(?:[eE][-+][0-9]+)?"
        + "|[-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+\\.[0-9_]*"
        + "|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN)");
    private static final Pattern TS11 = Pattern.compile(
        "[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]"
        + "|[0-9][0-9][0-9][0-9]-[0-9][0-9]?-[0-9][0-9]?(?:[Tt]|[ \\t]+)[0-9][0-9]?"
        + ":[0-9][0-9]:[0-9][0-9](?:\\.[0-9]*)?"
        + "(?:[ \\t]*(?:Z|[-+][0-9][0-9]?(?::[0-9][0-9])?))?");

    private static final Set<String> TRUE_WORDS =
        Set.of("true", "True", "TRUE");

    enum Tag { NULL, BOOL, INT, FLOAT, STR }

    private PortableYaml() {
    }

    /** The YAML 1.2 core tag of a plain scalar, or the disagreement/leading-zero fault. */
    static Tag portablePlainTag(String value) {
        Tag t12 = tag12(value);
        Tag t11 = tag11(value);
        if (t11 != t12) {
            throw new ScanException("R12", "the plain YAML value '" + value
                + "' is read differently by YAML 1.1 and YAML 1.2 readers; quote it");
        }
        if (t12 == Tag.INT) {
            String digits = value.startsWith("-") || value.startsWith("+")
                ? value.substring(1) : value;
            if (digits.length() > 1 && digits.charAt(0) == '0'
                    && (digits.charAt(1) != 'x' && digits.charAt(1) != 'X')) {
                throw new ScanException("R12", "the plain YAML value '" + value
                    + "' has a leading zero: YAML 1.1 reads it as octal; quote it");
            }
        }
        return t12;
    }

    private static Tag tag12(String v) {
        if (N12.matcher(v).matches()) {
            return Tag.NULL;
        }
        if (B12.matcher(v).matches()) {
            return Tag.BOOL;
        }
        if (I12.matcher(v).matches()) {
            return Tag.INT;
        }
        if (F12.matcher(v).matches()) {
            return Tag.FLOAT;
        }
        return Tag.STR;
    }

    private static Tag tag11(String v) {
        if (v.equals("y") || v.equals("Y") || v.equals("n") || v.equals("N")) {
            return Tag.BOOL;
        }
        if (N11.matcher(v).matches()) {
            return Tag.NULL;
        }
        if (B11.matcher(v).matches()) {
            return Tag.BOOL;
        }
        if (I11.matcher(v).matches()) {
            return Tag.INT;
        }
        if (F11.matcher(v).matches()) {
            return Tag.FLOAT;
        }
        if (TS11.matcher(v).matches()) {
            return Tag.STR; // dates stay strings, exactly as written (R12)
        }
        return Tag.STR;
    }

    /** Build a JsonNode for a plain scalar under the agreed tag. */
    static JsonNode construct(String value, Tag tag) {
        switch (tag) {
            case NULL:
                return NODES.nullNode();
            case BOOL:
                return NODES.booleanNode(TRUE_WORDS.contains(value));
            case INT:
                return NODES.numberNode(parseInteger(value));
            case FLOAT: {
                String lower = value.toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(".nan") || lower.endsWith(".inf")
                        || value.startsWith(".nan") || value.startsWith(".inf")
                        || value.startsWith("+.inf") || value.startsWith("-.inf")) {
                    // .nan/.inf/-.inf and their case variants (R13): never finite.
                    throw new ScanException("R13", "numeric value '" + value
                        + "' is not a finite number");
                }
                double d = Double.parseDouble(value);
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new ScanException("R13", "numeric value '" + value
                        + "' is not a finite number");
                }
                return NODES.numberNode(d);
            }
            default:
                return NODES.textNode(value);
        }
    }

    private static BigInteger parseInteger(String v) {
        String s = v;
        int sign = 1;
        if (s.startsWith("-")) {
            sign = -1;
            s = s.substring(1);
        } else if (s.startsWith("+")) {
            s = s.substring(1);
        }
        BigInteger value;
        if (s.startsWith("0x") || s.startsWith("0X")) {
            value = new BigInteger(s.substring(2), 16);
        } else if (s.startsWith("0o")) {
            value = new BigInteger(s.substring(2), 8);
        } else {
            value = new BigInteger(s);
        }
        return value.multiply(BigInteger.valueOf(sign));
    }

    // ---- composition -------------------------------------------------------

    private int nodeCount;

    /**
     * Load the header YAML text as one JSON-compatible document.
     *
     * @throws ScanException on any R8/R12/R13 violation or malformed YAML
     */
    static JsonNode load(String text) {
        PortableYaml py = new PortableYaml();
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(2 * HeaderScan.MAX_HEADER_BYTES);
        Parser parser = new ParserImpl(new StreamReader(text), options);
        Event first = parser.getEvent(); // StreamStart
        if (!first.is(Event.ID.StreamStart)) {
            throw new ScanException("yaml", "expected the start of the YAML stream");
        }
        Event ev = parser.getEvent();
        if (ev.is(Event.ID.StreamEnd)) {
            return NODES.nullNode(); // no header content at all
        }
        if (!(ev instanceof DocumentStartEvent)) {
            throw new ScanException("yaml", "expected a YAML document");
        }
        JsonNode root = py.compose(parser, 0);
        ev = parser.getEvent();
        if (!ev.is(Event.ID.DocumentEnd)) {
            throw new ScanException("yaml", "expected the document to end");
        }
        ev = parser.getEvent();
        if (!ev.is(Event.ID.StreamEnd)) {
            throw new ScanException("R8", "expected a single YAML document in the header");
        }
        return root;
    }

    private JsonNode compose(Parser parser, int depth) {
        nodeCount++;
        if (nodeCount > MAX_YAML_NODES) {
            throw new ScanException("R8", "FHR metadata exceeds " + MAX_YAML_NODES + " YAML nodes");
        }
        if (depth >= MAX_YAML_DEPTH) {
            throw new ScanException("R8", "metadata is nested too deeply");
        }
        Event ev = parser.getEvent();
        if (ev instanceof AliasEvent) {
            throw new ScanException("R8", "YAML anchors and aliases are not allowed in FHR metadata");
        }
        if (ev instanceof ScalarEvent) {
            return composeScalar((ScalarEvent) ev);
        }
        if (ev instanceof SequenceStartEvent) {
            SequenceStartEvent ss = (SequenceStartEvent) ev;
            requireNoAnchorOrTag(ss.getAnchor(), ss.getTag());
            ArrayNode array = NODES.arrayNode();
            while (true) {
                Event next = parser.peekEvent();
                if (next.is(Event.ID.SequenceEnd)) {
                    parser.getEvent();
                    break;
                }
                array.add(compose(parser, depth + 1));
            }
            return array;
        }
        if (ev instanceof MappingStartEvent) {
            MappingStartEvent ms = (MappingStartEvent) ev;
            requireNoAnchorOrTag(ms.getAnchor(), ms.getTag());
            ObjectNode map = NODES.objectNode();
            Set<String> seen = new HashSet<>();
            while (true) {
                Event next = parser.peekEvent();
                if (next.is(Event.ID.MappingEnd)) {
                    parser.getEvent();
                    break;
                }
                JsonNode key = compose(parser, depth + 1);
                if (!key.isTextual()) {
                    throw new ScanException("R8",
                        "FHR mapping keys must be strings, found " + key.getNodeType());
                }
                String name = key.asText();
                if (!seen.add(name)) {
                    throw new ScanException("R8", "found duplicate key '" + name + "'");
                }
                map.set(name, compose(parser, depth + 1));
            }
            return map;
        }
        throw new ScanException("yaml", "unexpected YAML event: " + ev.getEventId());
    }

    private static JsonNode composeScalar(ScalarEvent ev) {
        requireNoAnchorOrTag(ev.getAnchor(), ev.getTag());
        String value = ev.getValue();
        if (ev.getScalarStyle() == DumperOptions.ScalarStyle.PLAIN
                && ev.getImplicit().canOmitTagInPlainScalar()) {
            if (value.equals("<<")) {
                throw new ScanException("R8", "YAML merge keys are not allowed in FHR metadata");
            }
            return construct(value, portablePlainTag(value));
        }
        return NODES.textNode(value);
    }

    private static void requireNoAnchorOrTag(String anchor, String tag) {
        if (anchor != null && !anchor.isEmpty()) {
            throw new ScanException("R8", "YAML anchors and aliases are not allowed in FHR metadata");
        }
        if (tag != null) {
            throw new ScanException("R12", "YAML tags such as " + tag
                + " are not allowed in FHR metadata");
        }
    }

    /** Convert any SnakeYAML syntax failure into the common ScanException shape. */
    static JsonNode loadSafe(String text) {
        try {
            return load(text);
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            throw new ScanException("yaml", "malformed YAML header: " + e.getMessage());
        }
    }
}
