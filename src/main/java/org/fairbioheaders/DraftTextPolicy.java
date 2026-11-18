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
 * <p>Scope. This is a <b>draft-lane</b> check, applied by {@link FhgmModels} to the strings of an FHGM
 * record and to nothing else. It changes no released validation, no released contract bytes, and no
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
