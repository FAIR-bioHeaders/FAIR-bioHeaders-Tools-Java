/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders.conformance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/**
 * Where a conformance suite comes from, and what a named-but-wrong location means.
 *
 * <p>The rule is deliberately asymmetric:
 * <ul>
 *   <li>a location that <em>is</em> named (system property, else environment variable) is
 *       authoritative: if it does not hold the suite, that is a configuration error and resolution
 *       fails. Falling through elsewhere is how a run ends up measuring a different checkout than
 *       the one it was told to use;</li>
 *   <li>when nothing is named, sibling discovery is a local convenience, and finding nothing yields
 *       {@code null} - which the caller reports as skipped, never as passed.</li>
 * </ul>
 *
 * <p>The first rule exists because of an observation, not a hypothesis. During the issue #3
 * deliberate-failure demonstration, {@code -Dfh.spec.dir} was pointed at a directory with no
 * manifest. The Linux legs went red as intended; the macOS legs stayed green, because the fallback
 * candidate {@code FAIR-bioHeaders-Specification} resolved case-insensitively to the lowercase
 * {@code fair-bioheaders-specification} checkout the workflow itself had put in the workspace. A
 * named path must therefore never fall through.
 *
 * <p>The three predicates are separate on purpose: the shared specification suite, the FHT draft
 * suite and the FHGM draft suite have different layouts, and a directory that satisfies one must
 * not be accepted as another.
 */
final class SpecSource {

    private SpecSource() {
    }

    /** The shared layout: {@code <dir>/conformance/manifest.json}. */
    static final Predicate<Path> SPECIFICATION =
        dir -> Files.isRegularFile(dir.resolve("conformance/manifest.json"));

    /** FHT's draft suite layout: the directory itself holds {@code manifest.json} and {@code valid/}. */
    static final Predicate<Path> FHT_SUITE =
        dir -> Files.isRegularFile(dir.resolve("manifest.json")) && Files.isDirectory(dir.resolve("valid"));

    /**
     * FHGM's draft suite layout: {@code manifest.json} and {@code vectors/} (which holds
     * {@code valid/} and {@code invalid/}), beside the {@code model/} and {@code examples/} trees it
     * cites in place.
     */
    static final Predicate<Path> FHGM_SUITE =
        dir -> Files.isRegularFile(dir.resolve("manifest.json")) && Files.isDirectory(dir.resolve("vectors"));

    /**
     * @param namedProperty  the {@code fh.*.dir} system property, or null
     * @param namedEnvironment the corresponding environment variable, or null
     * @param propertyName   the property's name, for the failure message
     * @param environmentName the variable's name, for the failure message
     * @param fallbacks      sibling directories consulted only when nothing was named
     * @param isSuite        what makes a directory hold this suite
     * @return the suite directory, or null when nothing was named and no sibling holds a suite
     * @throws IllegalStateException when something was named and does not hold the suite
     */
    static Path resolve(String namedProperty, String namedEnvironment, String propertyName,
                        String environmentName, List<Path> fallbacks, Predicate<Path> isSuite) {
        if (namedProperty != null && !namedProperty.isBlank()) {
            return named(namedProperty.strip(), propertyName, isSuite);
        }
        if (namedEnvironment != null && !namedEnvironment.isBlank()) {
            return named(namedEnvironment.strip(), environmentName, isSuite);
        }
        for (Path candidate : fallbacks) {
            if (isSuite.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path named(String value, String source, Predicate<Path> isSuite) {
        Path dir = Path.of(value);
        if (isSuite.test(dir)) {
            return dir;
        }
        throw new IllegalStateException(source + "=" + value + " names a directory that does not hold"
            + " the conformance suite (looked at " + dir.toAbsolutePath() + "). Fix the path or unset"
            + " it to use sibling discovery; the suite is never read from a location the run did not"
            + " ask for.");
    }
}
