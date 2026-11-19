/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.util.regex.Pattern;

import com.networknt.schema.SchemaContext;
import com.networknt.schema.regex.RegularExpression;
import com.networknt.schema.regex.RegularExpressionFactory;
import com.networknt.schema.regex.RegularExpressions;

/**
 * Evaluates JSON Schema {@code pattern} and {@code patternProperties} expressions under the
 * family's normative ECMA-262 regular-expression floor ({@code FAIR-bioHeaders-Specification#264}
 * and {@code docs/REGEX-DIALECT.md}).
 *
 * <p>JSON Schema draft/2020-12 defines {@code pattern} as an ECMA-262 regular expression without
 * flags, tested against the full instance string. {@code java.util.regex.Pattern} differs from
 * ECMA-262 in three places that affect contract evaluation:
 *
 * <ul>
 *   <li><b>{@code $} end anchor:</b> Java's {@code $} matches before a trailing line terminator as
 *       well as at the end of the input; ECMA-262 without {@code m} matches only at the end of the
 *       string ({@code \z}). Handled via {@link RegularExpressions#replaceDollarAnchors(String)}.</li>
 *   <li><b>{@code \s} and {@code \S} whitespace classes:</b> Java's default {@code \s} matches
 *       only the six ASCII whitespace characters {@code [\t\n\x0B\f\r ]}, and
 *       {@link Pattern#UNICODE_CHARACTER_CLASS} instead includes {@code U+0085} (NEL) while
 *       omitting {@code U+FEFF} (ZWNBSP/BOM) and making {@code \d}/{@code \w} Unicode-wide. In
 *       ECMA-262 (even without the {@code u} flag), {@code \d} and {@code \w} remain ASCII while
 *       {@code \s} matches the union of {@code WhiteSpace} and {@code LineTerminator}:
 *       {@code U+0009..U+000D}, {@code U+0020}, {@code U+00A0}, {@code U+1680},
 *       {@code U+2000..U+200A}, {@code U+2028}, {@code U+2029}, {@code U+202F}, {@code U+205F},
 *       {@code U+3000}, and {@code U+FEFF} (and {@code \S} is its exact complement, so {@code U+0085}
 *       is matched by {@code \S} and rejected by {@code \s}). Without this translation, a DOI such
 *       as {@code "10.1234/abc"} followed by {@code U+00A0} or {@code U+202F} satisfies the DOI
 *       pattern in Java while failing ECMA-262 and the reference engine ({@code Tools-Java#16}).</li>
 *   <li><b>Empty character classes {@code []} and {@code [^]}:</b> ECMA-262 allows an empty class
 *       body ({@code []} matches nothing; {@code [^]} matches any code point including line
 *       terminators), whereas {@code java.util.regex.Pattern} treats a leading {@code ]} in
 *       {@code []...]} as a literal member or rejects {@code []} / {@code [^]} as unclosed.</li>
 * </ul>
 */
final class EcmaRegularExpressionFactory implements RegularExpressionFactory {

    static final EcmaRegularExpressionFactory INSTANCE = new EcmaRegularExpressionFactory();

    /**
     * The exact ECMA-262 {@code \s} character-class body ({@code WhiteSpace} union
     * {@code LineTerminator}, ECMA-262 {@code CompileToCharSet}).
     */
    static final String ECMA_WHITESPACE_CHARS =
        "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";

    private static final String ECMA_S = "[" + ECMA_WHITESPACE_CHARS + "]";
    private static final String ECMA_NOT_S = "[^" + ECMA_WHITESPACE_CHARS + "]";

    private EcmaRegularExpressionFactory() {
    }

    @Override
    public RegularExpression getRegularExpression(String regex) {
        String translated = translate(regex);
        Pattern compiled = Pattern.compile(translated);
        return input -> compiled.matcher(input).find();
    }

    @Override
    public RegularExpression getRegularExpression(String regex, SchemaContext schemaContext) {
        return getRegularExpression(regex);
    }

    /**
     * Translates an ECMA-262 JSON Schema pattern into a {@link Pattern}-compatible regex with
     * exact ECMA-262 {@code \s}/{@code \S}, {@code $}, and empty-class semantics.
     */
    static String translate(String regex) {
        String step1 = RegularExpressions.replaceDollarAnchors(regex);
        String step2 = RegularExpressions.replaceLongformCharacterProperties(step1);
        return replaceEcmaClassesAndWhitespace(step2);
    }

    private static String replaceEcmaClassesAndWhitespace(String regex) {
        if (regex.indexOf('\\') == -1 && regex.indexOf('[') == -1) {
            return regex;
        }
        StringBuilder out = new StringBuilder(regex.length() + 32);
        int classDepth = 0;
        boolean inQuote = false;
        int i = 0;
        int len = regex.length();
        while (i < len) {
            char c = regex.charAt(i);
            if (inQuote) {
                if (c == '\\' && i + 1 < len && regex.charAt(i + 1) == 'E') {
                    out.append("\\E");
                    inQuote = false;
                    i += 2;
                } else {
                    out.append(c);
                    i++;
                }
                continue;
            }
            if (c == '\\') {
                if (i + 1 >= len) {
                    out.append('\\');
                    i++;
                    continue;
                }
                char next = regex.charAt(i + 1);
                if (next == 'Q') {
                    out.append("\\Q");
                    inQuote = true;
                    i += 2;
                    continue;
                }
                if (next == 's') {
                    out.append(ECMA_S);
                    i += 2;
                    continue;
                }
                if (next == 'S') {
                    out.append(ECMA_NOT_S);
                    i += 2;
                    continue;
                }
                out.append('\\').append(next);
                i += 2;
                continue;
            }
            if (c == '[') {
                if (classDepth == 0) {
                    if (regex.startsWith("[]", i)) {
                        out.append("(?!)");
                        i += 2;
                        continue;
                    }
                    if (regex.startsWith("[^]", i)) {
                        out.append("[\\s\\S]");
                        i += 3;
                        continue;
                    }
                }
                classDepth++;
                out.append('[');
                i++;
                continue;
            }
            if (c == ']' && classDepth > 0) {
                classDepth--;
                out.append(']');
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
