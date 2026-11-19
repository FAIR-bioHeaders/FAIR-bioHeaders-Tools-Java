/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

/**
 * The draft record contract's text rule, evaluated in the terms the contract itself states.
 *
 * <p>The pinned record contract defines {@code $defs.text} as "A non-empty opaque string with no
 * leading or trailing whitespace (<em>Unicode whitespace included</em>) and no control, zero-width or
 * bidirectional formatting character anywhere (C0, DEL, C1, U+200B-U+200F, U+202A-U+202E,
 * U+2060-U+2064, U+FEFF)" - {@code fhgm-model-draft.json} lines 155-161, the definition every
 * {@code modelId}, {@code modelVersion} and {@code featureId} is checked against. It writes that
 * sentence as two patterns, {@code "pattern": "^\\S"} and
 * {@code "not": {"pattern": "\\s$|[\\u0000-\\u001f\\u007f-\\u009f\\u200b-\\u200f\\u202a-\\u202e\\u2060-\\u2064\\ufeff]"}}.
 *
 * <p>Those two escapes are where a reader has to decide what they mean, because the answer depends on
 * the engine. The draft's reference checker evaluates them in Python, where {@code \s} is a Unicode
 * class. This jar evaluates the same bytes with {@code java.util.regex} semantics, whose {@code \s} is
 * only {@code [ \t\n\x0B\f\r]} unless {@code UNICODE_CHARACTER_CLASS} is set - so a modelId of
 * {@code "model-a\u00A0"} satisfies the pattern here while the contract's sentence refuses it, and the
 * invisible character travels on into the next comparison. That is the same hidden-character class this
 * round has met on both sides of the family (the concealed-URI case in the FHT draft, the leading
 * U+00A0 case in the shared suite), and it is the failure direction a draft lane must not have: a pass
 * asserted from a constraint the evaluator never applied.
 *
 * <p>So the sets below are not Java's {@code \s} and not {@code String.isBlank()}: they are the code
 * points the contract itself enumerates. The whitespace set is the one {@code $defs.link} spells out
 * inline two definitions later (line 166: {@code \t-\r}, space, {@code \x1c-\x1f}, {@code \x85},
 * {@code \xa0}, {@code \u1680}, {@code \u2000-\u200a}, {@code \u2028}, {@code \u2029}, {@code \u202f},
 * {@code \u205f}, {@code \u3000}, {@code \ufeff}), which is the contract's own statement of what it
 * counts as whitespace; the invisible set is the character class of {@code $defs.text}'s own
 * {@code not} pattern, plus the empty value its {@code minLength: 1} rules out. Internal ordinary
 * whitespace stays legal, exactly as the contract leaves it: the rule is about the edges and about
 * characters that never show.
 *
 * <p>Scope, and it is the contract's scope and not a wider one. The rule applies at the properties the
 * pinned record contract actually types as {@code $defs/text} - the fourteen sites that reference it,
 * followed through {@code $ref} and {@code allOf} (so {@code $defs/curie} counts, because its
 * {@code allOf[0]} is {@code $defs/text}, and {@code reference.genomeReference.assemblyName} counts
 * through the {@code $defs/genomeReference} reference). It does NOT apply to every string reachable by
 * recursion: {@code publication.article} carries its own DOI pattern and no text reference, and
 * applying the text rule there rejected {@code 10.1234/ab&#x200D;cd}, a value the pinned contract
 * accepts. Over-rejection is a false invalidity, which the {@code #264} ruling weighs as heavily as a
 * false pass, so {@link #textPaths(JsonNode)} derives the paths from the schema instead of a list kept
 * by hand.
 *
 * <p>Scope in the other direction: this is a <b>draft-lane</b> check, applied by {@link FhgmModels} to
 * an FHGM record and to nothing else. It changes no released validation, no released contract bytes, and no
 * frozen draft bytes: the pinned schema is vendored verbatim and digest-checked, so the portable fix -
 * writing the patterns as explicit code-point classes the way {@code $defs.link} already does, upstream
 * in FHGM-Specification with a deliberate re-pin and fixtures - is owning-repository work. Until that
 * lands, the constraint is enforced here, reported under the rule the contract's shape falls under (K2),
 * and named in {@code docs/STATUS.md} rather than left to an engine default.
 */
final class DraftTextPolicy {

    private DraftTextPolicy() {
    }

    /**
     * The paths in an FHGM record where the contract applies {@code $defs.text}, derived from the
     * pinned schema rather than a hand-kept list. Array segments are normalised to {@code []}, so a
     * contributor name is {@code contributors[].name} on both sides of the comparison.
     *
     * <p>Walking it out of the schema is the point. A list of field names goes stale on the next
     * deliberate re-pin in exactly the wrong direction - it silently stops covering a field the
     * contract did constrain - while {@code $ref} is a fact about the bytes this jar digests.
     */
    static java.util.Set<String> textPaths(tools.jackson.databind.JsonNode contract) {
        java.util.Set<String> found = new java.util.LinkedHashSet<>();
        // Start at the schema root, so the paths collected are record paths. Walking $defs instead
        // would add the empty prefix (a definition is not an instance location) and miss every field.
        collectTextPaths(contract, contract, "", found, 0);
        found.remove("");
        return java.util.Collections.unmodifiableSet(found);
    }

    private static void collectTextPaths(tools.jackson.databind.JsonNode root,
                                         tools.jackson.databind.JsonNode node, String prefix,
                                         java.util.Set<String> found, int depth) {
        if (node == null || node.isMissingNode() || node.isNull() || depth > 24) {
            return;
        }
        String ref = node.path("$ref").asString("");
        if (!ref.isEmpty()) {
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            if (name.equals("text")) {
                found.add(prefix);
                return;
            }
            if (ref.startsWith("#/$defs/")) {
                collectTextPaths(root, root.path("$defs").path(name), prefix, found, depth + 1);
            }
            return;
        }
        for (tools.jackson.databind.JsonNode branch : node.path("allOf")) {
            collectTextPaths(root, branch, prefix, found, depth + 1);
        }
        for (tools.jackson.databind.JsonNode branch : node.path("anyOf")) {
            collectTextPaths(root, branch, prefix, found, depth + 1);
        }
        for (tools.jackson.databind.JsonNode branch : node.path("oneOf")) {
            collectTextPaths(root, branch, prefix, found, depth + 1);
        }
        if (node.has("items")) {
            collectTextPaths(root, node.path("items"), prefix + "[]", found, depth + 1);
        }
        tools.jackson.databind.JsonNode properties = node.path("properties");
        if (properties.isObject()) {
            for (java.util.Map.Entry<String, tools.jackson.databind.JsonNode> field
                : properties.properties()) {
                collectTextPaths(root, field.getValue(),
                    prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey(), found,
                    depth + 1);
            }
        }
    }

    /** The instance path of one value inside a record, with array indices normalised to {@code []}. */
    static String path(String parent, String field) {
        return parent.isEmpty() ? field : parent + "." + field;
    }

    /**
     * Whether {@code cp} is whitespace in the sense the contract states: "Unicode whitespace included",
     * spelled out as the enumeration {@code $defs.link} uses (line 166). U+00A0 and U+202F are in it;
     * they are not in Java's {@code \s}.
     */
    static boolean isWhitespace(int cp) {
        return (cp >= 0x09 && cp <= 0x0d)
            || cp == 0x1c || cp == 0x1d || cp == 0x1e || cp == 0x1f
            || cp == 0x20
            || cp == 0x85 || cp == 0xa0 || cp == 0x1680
            || (cp >= 0x2000 && cp <= 0x200a)
            || cp == 0x2028 || cp == 0x2029 || cp == 0x202f || cp == 0x205f || cp == 0x3000
            || cp == 0xfeff;
    }

    /**
     * Whether {@code cp} is one of the characters {@code $defs.text} forbids anywhere in a value: C0,
     * DEL, C1, the zero-width and bidi formatting characters, and the word joiners/BOM range.
     */
    static boolean isInvisible(int cp) {
        return cp <= 0x1f
            || (cp >= 0x7f && cp <= 0x9f)
            || (cp >= 0x200b && cp <= 0x200f)
            || (cp >= 0x202a && cp <= 0x202e)
            || (cp >= 0x2060 && cp <= 0x2064)
            || cp == 0xfeff;
    }

    /**
     * Why {@code value} fails the contract's text rule, in words, or {@code null} when it satisfies it.
     *
     * <p>The finding names the code point instead of reprinting the character: reprinting an invisible
     * character is how it survives into the next log line, the next receipt and the next reader's
     * terminal, where it stays invisible.
     */
    static String violation(String value) {
        if (value == null) {
            return null;
        }
        if (value.isEmpty()) {
            return "is empty, and the contract requires a non-empty value (minLength 1)";
        }
        int first = value.codePointAt(0);
        int last = value.codePointBefore(value.length());
        if (isInvisible(first)) {
            return "starts with U+" + hex(first)
                + ", a control or formatting character the contract forbids anywhere in a value";
        }
        if (isInvisible(last)) {
            return "ends with U+" + hex(last)
                + ", a control or formatting character the contract forbids anywhere in a value";
        }
        if (isWhitespace(first)) {
            return "starts with whitespace (U+" + hex(first) + ")";
        }
        if (isWhitespace(last)) {
            return "ends with whitespace (U+" + hex(last)
                + "); the contract's rule is Unicode whitespace included";
        }
        int at = 0;
        while (at < value.length()) {
            int cp = value.codePointAt(at);
            if (isInvisible(cp)) {
                return "contains U+" + hex(cp) + " at offset " + at
                    + ", a control or formatting character the contract forbids anywhere in a value";
            }
            at += Character.charCount(cp);
        }
        return null;
    }

    private static String hex(int codePoint) {
        return String.format("%04X", codePoint);
    }
}
