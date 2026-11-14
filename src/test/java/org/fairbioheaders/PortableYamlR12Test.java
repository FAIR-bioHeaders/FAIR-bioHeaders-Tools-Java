/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * FORMAT.md R12: the plain-scalar type agreement table. Every expectation below was
 * produced on 2026-11-14 by calling {@code bioheaders.portable_plain_tag()} from the
 * released fair-bioheaders 0.11.0 package (PyYAML 6.0.3 under the hood) on the same
 * input string; {@code ERROR} means the reference loader raises (the value reads
 * differently under YAML 1.1 and 1.2, or is a leading-zero integer), which makes the
 * record invalid. This is the Python/Java equivalence proof for the hardest rule.
 */
class PortableYamlR12Test {

    // value -> reference tag, from bioheaders.portable_plain_tag (0.11.0, 2026-11-14)
    static final List<String[]> TABLE = List.of(
        new String[] {"010", "ERROR"},
        new String[] {"00", "ERROR"},
        new String[] {"0", "int"},
        new String[] {"-010", "ERROR"},
        new String[] {"+010", "ERROR"},
        new String[] {"1_000", "ERROR"},
        new String[] {"1:30", "ERROR"},
        new String[] {"1:30:00", "ERROR"},
        new String[] {"yes", "ERROR"},
        new String[] {"Yes", "ERROR"},
        new String[] {"NO", "ERROR"},
        new String[] {"no", "ERROR"},
        new String[] {"on", "ERROR"},
        new String[] {"Off", "ERROR"},
        new String[] {"OFF", "ERROR"},
        new String[] {"y", "ERROR"},
        new String[] {"Y", "ERROR"},
        new String[] {"n", "ERROR"},
        new String[] {"N", "ERROR"},
        new String[] {"true", "bool"},
        new String[] {"True", "bool"},
        new String[] {"TRUE", "bool"},
        new String[] {"false", "bool"},
        new String[] {"FALSE", "bool"},
        new String[] {"1e3", "ERROR"},
        new String[] {"1.5e3", "ERROR"},
        new String[] {"1.5e+3", "float"},
        new String[] {"0x1F", "int"},
        new String[] {"0o17", "ERROR"},
        new String[] {"0b101", "ERROR"},
        new String[] {"~", "null"},
        new String[] {"null", "null"},
        new String[] {"Null", "null"},
        new String[] {"NULL", "null"},
        new String[] {"", "null"},
        new String[] {"2024-01-01", "str"},
        new String[] {"2024-1-2", "str"},
        new String[] {"2024-01-01T10:00:00Z", "str"},
        new String[] {"2024-01-01 10:00:00", "str"},
        new String[] {".5", "float"},
        new String[] {"1.", "float"},
        new String[] {"12.5", "float"},
        new String[] {"-3.25e-7", "float"},
        new String[] {"0755", "ERROR"},
        new String[] {"0.10", "float"},
        new String[] {"10", "int"},
        new String[] {"-1", "int"},
        new String[] {"+1.0e-3", "float"},
        new String[] {"abc", "str"},
        new String[] {"A", "str"},
        new String[] {".inf", "float"},
        new String[] {".Inf", "float"},
        new String[] {"-.inf", "float"},
        new String[] {".nan", "float"},
        new String[] {".NAN", "float"},
        new String[] {"2:3:4", "ERROR"},
        new String[] {"12:34:56.78", "ERROR"});

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "010      | ERROR", "1_000 | ERROR", "1:30 | ERROR", "yes | ERROR",
        "y | ERROR", "n | ERROR", "1e3 | ERROR", "1.5e3 | ERROR", "0o17 | ERROR",
        "2:3:4 | ERROR", "12:34:56.78 | ERROR", "0755 | ERROR", "00 | ERROR"})
    void disagreementIsR12Error(String value, String expected) {
        ScanException e = assertThrows(ScanException.class,
            () -> PortableYaml.portablePlainTag(value));
        assertEquals("R12", e.rule());
    }

    @Test
    void agreesWithReferenceTable() {
        for (String[] row : TABLE) {
            String value = row[0];
            String want = row[1];
            if (want.equals("ERROR")) {
                ScanException e = assertThrows(ScanException.class,
                    () -> PortableYaml.portablePlainTag(value), "for '" + value + "'");
                assertTrue(e.rule().equals("R12"), "for '" + value + "' got rule " + e.rule());
            } else {
                PortableYaml.Tag tag = PortableYaml.portablePlainTag(value);
                assertEquals(want, tag.name().toLowerCase(), "for '" + value + "'");
            }
        }
    }

    @Test
    void nonFiniteNumbersAreR13Errors() {
        for (String v : List.of(".inf", ".Inf", "-.inf", ".nan", ".NAN")) {
            PortableYaml.Tag tag = PortableYaml.portablePlainTag(v);
            ScanException e = assertThrows(ScanException.class,
                () -> PortableYaml.construct(v, tag));
            assertEquals("R13", e.rule(), "for '" + v + "'");
        }
        // overflow to infinity also fails (R13): a portable float that overflows
        ScanException e = assertThrows(ScanException.class,
            () -> PortableYaml.construct("1.0e+999", PortableYaml.portablePlainTag("1.0e+999")));
        assertEquals("R13", e.rule());
        // and the signless exponent form is an R12 disagreement, matching the reference
        assertEquals("R12", assertThrows(ScanException.class,
            () -> PortableYaml.portablePlainTag("1.0e999")).rule());
    }

    @Test
    void headerYamlRejectsAnchorsAliasesTagsAndDuplicates() {
        assertRule("R8", "a: &anchor 1\n");
        assertRule("R8", "a: 1\na: 2\n");
        assertRule("R12", "a: !!str 1\n");
        assertRule("R8", "a: 1\nb: *missing\n");
        assertRule("R8", "base: {x: 1}\nitem:\n  <<: 2\n");
    }

    private static void assertRule(String rule, String yaml) {
        ScanException e = assertThrows(ScanException.class, () -> PortableYaml.loadSafe(yaml));
        assertEquals(rule, e.rule(), "for YAML:\n" + yaml + "\ngot: " + e.getMessage());
    }
}
