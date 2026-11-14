/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Suite discovery: a named directory is authoritative, an unnamed run may use sibling discovery,
 * and finding nothing is reported as "no suite" rather than as a pass.
 *
 * <p>The first rule was added after the issue #3 deliberate-failure demonstration exposed that it
 * was not the rule: with {@code -Dfh.spec.dir} pointed at a directory with no manifest, the Linux
 * leg went red as intended, but the macOS legs stayed green because sibling discovery resolved
 * case-insensitively to the lowercase {@code fair-bioheaders-specification} checkout the workflow
 * itself had placed in the workspace. Same command, different answer per filesystem - which is
 * exactly what an acceptance job must not do.
 */
class SpecSourceTest {

    private static Path specificationDir(Path root) throws IOException {
        Files.createDirectories(root.resolve("conformance"));
        Files.writeString(root.resolve("conformance/manifest.json"), "{\"vectors\":[]}");
        return root;
    }

    private static Path suiteDir(Path root) throws IOException {
        Files.createDirectories(root.resolve("valid"));
        Files.writeString(root.resolve("manifest.json"), "{\"vectors\":[]}");
        return root;
    }

    @Test
    void aNamedSpecificationDirectoryIsUsed(@TempDir Path dir) throws IOException {
        Path suite = specificationDir(dir.resolve("spec"));
        assertEquals(suite, SpecSource.resolve(suite.toString(), null, "-Dfh.spec.dir", "FH_SPEC_DIR",
            List.of(), SpecSource.SPECIFICATION));
    }

    @Test
    void aNamedDirectoryWithoutTheSuiteFailsInsteadOfFallingBack(@TempDir Path dir)
        throws IOException {
        Path wrong = dir.resolve("not-a-spec");
        Files.createDirectories(wrong.resolve("schemas"));
        // The tempting fallback is present and valid; a named path must still not silently switch
        // to it, which is the case-insensitive-sibling bug in one line.
        Path sibling = specificationDir(dir.resolve("FAIR-bioHeaders-Specification"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> SpecSource.resolve(wrong.toString(), null, "-Dfh.spec.dir", "FH_SPEC_DIR",
                List.of(sibling), SpecSource.SPECIFICATION));
        assertTrue(e.getMessage().contains("-Dfh.spec.dir=" + wrong), e.getMessage());
        assertTrue(e.getMessage().contains("does not"), e.getMessage());
    }

    @Test
    void theEnvironmentVariableIsHonouredWhenNoPropertyIsSet(@TempDir Path dir) throws IOException {
        Path suite = specificationDir(dir.resolve("env-spec"));
        assertEquals(suite, SpecSource.resolve(null, suite.toString(), "-Dfh.spec.dir", "FH_SPEC_DIR",
            List.of(), SpecSource.SPECIFICATION));
        Path wrong = dir.resolve("empty");
        Files.createDirectories(wrong);
        assertThrows(IllegalStateException.class,
            () -> SpecSource.resolve(null, wrong.toString(), "-Dfh.spec.dir", "FH_SPEC_DIR",
                List.of(), SpecSource.SPECIFICATION));
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
    void theDraftSuiteLayoutNeedsItsOwnShape(@TempDir Path dir) throws IOException {
        Path suite = suiteDir(dir.resolve("conformance"));
        assertEquals(suite, SpecSource.resolve(suite.toString(), null, "-Dfh.fht.dir", "FH_FHT_DIR",
            List.of(), SpecSource.SUITE_DIRECTORY));
        // A directory with only valid/ is not a suite; a manifest alone is not either.
        Path halfBuilt = dir.resolve("half");
        Files.createDirectories(halfBuilt.resolve("valid"));
        assertThrows(IllegalStateException.class,
            () -> SpecSource.resolve(halfBuilt.toString(), null, "-Dfh.fht.dir", "FH_FHT_DIR",
                List.of(), SpecSource.SUITE_DIRECTORY));
        // And the specification layout does not satisfy the draft predicate: the two suites are
        // discovered by their own rules, never by whichever directory happens to exist.
        Path specLayout = specificationDir(dir.resolve("spec-layout"));
        assertTrue(!SpecSource.SUITE_DIRECTORY.test(specLayout));
        assertTrue(!SpecSource.SPECIFICATION.test(suite));
    }
}
