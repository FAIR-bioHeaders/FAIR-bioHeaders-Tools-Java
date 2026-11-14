/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Suite discovery: a named directory is authoritative, an unnamed run may use sibling discovery, and
 * each suite layout is recognised only by its own rule.
 *
 * <p>The authority rule was added after the issue #3 deliberate-failure demonstration showed the
 * old behaviour was wrong: with {@code -Dfh.spec.dir} pointed at a directory holding no manifest,
 * the Linux legs failed as intended while the macOS legs passed - sibling discovery had resolved,
 * case-insensitively, to the differently-cased checkout the workflow itself had placed in the
 * workspace. {@link #aNamedDirectoryWithoutTheSuiteFailsInsteadOfFallingBack} is that bug, pinned.
 */
class SpecSourceTest {

    private static Path specificationDir(Path root) throws IOException {
        Files.createDirectories(root.resolve("conformance"));
        Files.writeString(root.resolve("conformance/manifest.json"), "{\"vectors\":[]}");
        return root;
    }

    private static Path fhtSuite(Path root) throws IOException {
        Files.createDirectories(root.resolve("valid"));
        Files.writeString(root.resolve("manifest.json"), "{\"vectors\":[]}");
        return root;
    }

    private static Path fhgmSuite(Path root) throws IOException {
        Files.createDirectories(root.resolve("vectors"));
        Files.writeString(root.resolve("manifest.json"), "{\"vectors\":[]}");
        return root;
    }

    @Test
    void aNamedSpecificationDirectoryIsUsed(@TempDir Path dir) throws IOException {
        Path suite = specificationDir(dir.resolve("spec"));
        assertEquals(suite, SpecSource.resolve(suite.toString(), null, "-Dfh.spec.dir",
            "FH_SPEC_DIR", List.of(), SpecSource.SPECIFICATION));
    }

    @Test
    void aNamedDirectoryWithoutTheSuiteFailsInsteadOfFallingBack(@TempDir Path dir)
        throws IOException {
        Path wrong = dir.resolve("not-a-spec");
        Files.createDirectories(wrong.resolve("schemas"));
        Path validSibling = specificationDir(dir.resolve("FAIR-bioHeaders-Specification"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> SpecSource.resolve(wrong.toString(), null, "-Dfh.spec.dir", "FH_SPEC_DIR",
                List.of(validSibling), SpecSource.SPECIFICATION));
        assertTrue(e.getMessage().contains("-Dfh.spec.dir=" + wrong), e.getMessage());
        assertTrue(e.getMessage().contains("does not hold"), e.getMessage());
    }

    @Test
    void theEnvironmentVariableIsUsedWhenNoPropertyIsSet(@TempDir Path dir) throws IOException {
        Path suite = specificationDir(dir.resolve("env-spec"));
        assertEquals(suite, SpecSource.resolve(null, suite.toString(), "-Dfh.spec.dir",
            "FH_SPEC_DIR", List.of(), SpecSource.SPECIFICATION));
        Path wrong = dir.resolve("empty");
        Files.createDirectories(wrong);
        assertThrows(IllegalStateException.class,
            () -> SpecSource.resolve(null, wrong.toString(), "-Dfh.spec.dir", "FH_SPEC_DIR",
                List.of(), SpecSource.SPECIFICATION));
    }

    @Test
    void thePropertyWinsOverTheEnvironment(@TempDir Path dir) throws IOException {
        Path fromProperty = specificationDir(dir.resolve("a"));
        Path fromEnvironment = specificationDir(dir.resolve("b"));
        assertEquals(fromProperty, SpecSource.resolve(fromProperty.toString(), fromEnvironment.toString(),
            "-Dfh.spec.dir", "FH_SPEC_DIR", List.of(), SpecSource.SPECIFICATION));
    }

    @Test
    void aBlankNameIsTreatedAsUnset(@TempDir Path dir) throws IOException {
        Path suite = specificationDir(dir.resolve("sibling"));
        assertEquals(suite, SpecSource.resolve("   ", "  ", "-Dfh.spec.dir", "FH_SPEC_DIR",
            List.of(suite), SpecSource.SPECIFICATION));
    }

    @Test
    void siblingDiscoveryStillWorksWithNothingNamed(@TempDir Path dir) throws IOException {
        Path suite = specificationDir(dir.resolve("sibling"));
        assertEquals(suite, SpecSource.resolve(null, null, "-Dfh.spec.dir", "FH_SPEC_DIR",
            List.of(dir.resolve("absent"), suite), SpecSource.SPECIFICATION));
    }

    @Test
    void nothingNamedAndNothingNearbyMeansNoSuite(@TempDir Path dir) {
        assertNull(SpecSource.resolve(null, null, "-Dfh.spec.dir", "FH_SPEC_DIR",
            List.of(dir.resolve("absent")), SpecSource.SPECIFICATION));
    }

    @Test
    void eachSuiteLayoutIsDiscoveredByItsOwnRule(@TempDir Path dir) throws IOException {
        Path fht = fhtSuite(dir.resolve("fht-conformance"));
        Path fhgm = fhgmSuite(dir.resolve("fhgm-conformance"));
        Path spec = specificationDir(dir.resolve("spec"));

        assertEquals(fht, SpecSource.resolve(fht.toString(), null, "-Dfh.fht.dir", "FH_FHT_DIR",
            List.of(), SpecSource.FHT_SUITE));
        assertEquals(fhgm, SpecSource.resolve(fhgm.toString(), null, "-Dfh.fhgm.dir", "FH_FHGM_DIR",
            List.of(), SpecSource.FHGM_SUITE));

        assertFalse(SpecSource.SPECIFICATION.test(fht), "the shared suite is elsewhere");
        assertFalse(SpecSource.SPECIFICATION.test(fhgm));
        assertFalse(SpecSource.FHT_SUITE.test(fhgm), "FHGM has vectors/, not valid/");
        assertFalse(SpecSource.FHGM_SUITE.test(fht), "FHT has valid/, not vectors/");
        assertFalse(SpecSource.FHT_SUITE.test(spec));
        assertFalse(SpecSource.FHGM_SUITE.test(spec));
        assertTrue(SpecSource.SPECIFICATION.test(spec));
    }
}
