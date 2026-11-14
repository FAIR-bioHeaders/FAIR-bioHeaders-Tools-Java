/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * Schema selection per FAIR-bioHeaders-Specification docs/SCHEMA-VERSIONS.md rules
 * V1-V6, ported from the reference implementation in FAIR-bioHeaders-Tools
 * {@code bioheaders/selection.py}.
 *
 * <p>Rule V7's opt-in draft lane is deliberately not implemented: this reader
 * bundles released contracts only, FHT and FHGM have no released schema, and no
 * draft citation is recognised (a {@code fht.json} or FHGM URL falls to V5 with the
 * near-miss warning, exactly as the published rules require).
 */
public final class SchemaSelection {

    // Raw URLs under either name of the specification repository (FAIR-bioHeaders-
    // Specification since its #108, FHR-Specification before; old URLs stay supported).
    private static final Pattern RAW = Pattern.compile(
        "https://raw\\.githubusercontent\\.com/FAIR-bioHeaders/"
        + "(?:FHR|FAIR-bioHeaders)-Specification/(.+?)/(fhr|fhp|fhgff3)\\.json");
    private static final Pattern W3ID = Pattern.compile(
        "https://w3id\\.org/fair-bioheaders/(fhr|fhp|fhgff3)/(v[0-9]+\\.[0-9]+\\.[0-9]+)/?");
    private static final Pattern RELEASE = Pattern.compile("v[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final String W3ID_PREFIX = "https://w3id.org/fair-bioheaders/";
    private static final String RAW_PREFIX = "https://raw.githubusercontent.com/FAIR-bioHeaders/";
    private static final Pattern NEAR_MISS_TYPE = Pattern.compile("(?:^|[/._-])(fhp|fhgff3|fht)(?:[/._-]|$)");

    /** The {@code schemaVersion} of the last FHR 1 release and the rule V6 warning. */
    static final String FHR1_RELEASE = "v0.3.1";
    static final String FHR1_WARNING =
        "schemaVersion 1 via the raw-main schema URL is read as FHR 1 (" + FHR1_RELEASE
        + "); cite " + W3ID_PREFIX + "fhr/" + FHR1_RELEASE + " or move to schemaVersion \"2.0\"";

    /** Thrown for rule V4: the URL names a version this reader does not bundle. */
    public static final class Unsupported extends Exception {
        private static final long serialVersionUID = 1L;

        Unsupported(String message) {
            super(message);
        }
    }

    /** Outcome: the header type, the selected release (or "main"), and the warning, if any. */
    public record Selection(FhType type, String release, Issue warning) {
    }

    private final SchemaBundle bundle;

    SchemaSelection(SchemaBundle bundle) {
        this.bundle = bundle;
    }

    private static FhType typeOfUrlKey(String key) {
        switch (key) {
            case "fhr":
                return FhType.FHR;
            case "fhp":
                return FhType.FHP;
            default:
                return FhType.FHGFF3;
        }
    }

    /** The {@code (type, ref)} a FAIR-bioHeaders schema URL names, or null. */
    static Object[] parse(String value) {
        if (value == null) {
            return null;
        }
        Matcher raw = RAW.matcher(value);
        if (raw.matches()) {
            return new Object[] {typeOfUrlKey(raw.group(2)), raw.group(1)};
        }
        Matcher w3id = W3ID.matcher(value);
        if (w3id.matches()) {
            return new Object[] {typeOfUrlKey(w3id.group(1)), w3id.group(2)};
        }
        return null;
    }

    /** Rule V6: the schemaVersion is the JSON number 1 (1 or 1.0, not "1", not a boolean). */
    static boolean isFhr1Version(JsonNode version) {
        if (version == null || !version.isNumber()) {
            return false;
        }
        try {
            return new BigDecimal(version.numberValue().toString()).compareTo(BigDecimal.ONE) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Select the schema a record must be validated against, from its {@code schema}
     * value and (for rule V6) its {@code schemaVersion}.
     *
     * @throws Unsupported when the value names a release this bundle does not carry (V4)
     */
    public Selection selectRecord(JsonNode metadata) throws Unsupported {
        JsonNode schemaNode = metadata == null ? null : metadata.get("schema");
        String value = schemaNode != null && schemaNode.isString() ? schemaNode.asString() : null;
        Object[] parsed = parse(value);

        // Rule V6: FHR 1 record on raw-main.
        if (parsed != null && parsed[0] == FhType.FHR && "main".equals(parsed[1])
                && isFhr1Version(metadata.get("schemaVersion"))) {
            return new Selection(FhType.FHR, FHR1_RELEASE,
                new Issue(Severity.WARNING, "V6", "FHR: warning: " + FHR1_WARNING));
        }
        return select(value);
    }

    /** Rules V1-V5 from the {@code schema} value alone. */
    public Selection select(String value) throws Unsupported {
        Object[] parsed = parse(value);
        if (parsed == null) {
            // V5: any other value (including a missing/non-string schema) is FHR
            // against the current schema.
            return new Selection(FhType.FHR, "main", nearMissWarning(value));
        }
        FhType type = (FhType) parsed[0];
        String ref = (String) parsed[1];
        if ("main".equals(ref)) {
            return new Selection(type, "main", null);
        }
        if (RELEASE.matcher(ref).matches() && bundle.has(type, ref)) {
            return new Selection(type, ref, null);
        }
        throw new Unsupported(type.fileKey() + ": unsupported schema version: " + value
            + " (this tool has " + type.fileKey() + " schemas for "
            + String.join(", ", supported(type)) + ")");
    }

    /** The releases (and {@code main}) bundled for a header type (rule V4 message). */
    public List<String> supported(FhType type) {
        List<String> names = new ArrayList<>();
        String current = bundle.currentRelease();
        names.add(current == null || current.isEmpty() ? "main" : "main (" + current + ")");
        names.addAll(bundle.releasesWith(type));
        return names;
    }

    /**
     * The informational near-miss warning of FAIR-bioHeaders-Tools#125 (toolkit-side
     * convenience, not a specification rule): a V5 value that still looks like a
     * FAIR-bioHeaders URL. It changes no validity and no outcome.
     */
    static Issue nearMissWarning(String value) {
        if (value == null || parse(value) != null) {
            return null;
        }
        String lowered = value.toLowerCase(Locale.ROOT);
        if (!lowered.startsWith(W3ID_PREFIX.toLowerCase(Locale.ROOT))
                && !lowered.startsWith(RAW_PREFIX.toLowerCase(Locale.ROOT))) {
            return null;
        }
        Matcher named = NEAR_MISS_TYPE.matcher(lowered);
        if (named.find()) {
            String kind = typeOfUrlKey(named.group(1)).fileKey();
            return Issue.warning("V5", "schema " + value + " is not a FAIR-bioHeaders schema URL;"
                + " read as FHR, not " + kind + ", against the current schema (rule V5)");
        }
        return Issue.warning("V5", "schema " + value + " is not a FAIR-bioHeaders schema URL;"
            + " read as FHR against the current schema (rule V5)");
    }

    /** The bundled releases map, for diagnostics ({@code release -> type -> file}). */
    Map<String, Map<FhType, String>> releases() {
        return new LinkedHashMap<>(bundle.releaseIndex());
    }
}
