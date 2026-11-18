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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * Schema selection per FAIR-bioHeaders-Specification docs/SCHEMA-VERSIONS.md rules V1-V7,
 * ported from the reference implementation in FAIR-bioHeaders-Tools
 * {@code bioheaders/selection.py}.
 *
 * <p>Rules V1-V3 recognise only the URL keys of the <em>released</em> types, which this class
 * takes from the {@link TypeRegistry} rather than hard-coding, so a type the family releases
 * later joins the patterns through its registry row. Rule V7's draft lane is off unless the
 * {@link Options} this reader was built with enable it; a draft citation is then recognised
 * only through the exact raw-main URL its registry entry pins, and every record read that way
 * carries the warning naming the draft, its commit and its digest. Without the opt-in, V5
 * governs such a value and the outcome is exactly what it was before V7 existed.
 */
public final class SchemaSelection {

    /** The two repository names the shared specification has had (FAIR-bioHeaders#108 alias). */
    private static final String SPEC_REPO = "(?:FHR|FAIR-bioHeaders)-Specification";

    private static final String RAW_PREFIX = "https://raw.githubusercontent.com/FAIR-bioHeaders/";
    private static final String W3ID_PREFIX = "https://w3id.org/fair-bioheaders/";

    /** Rule V6: the {@code schemaVersion} of the last FHR 1 release and its warning. */
    static final String FHR1_RELEASE = "v0.3.1";
    static final String FHR1_WARNING =
        "schemaVersion 1 via the raw-main schema URL is read as FHR 1 (" + FHR1_RELEASE
        + "); cite " + W3ID_PREFIX + "fhr/" + FHR1_RELEASE + " or move to schemaVersion \"2.0\"";

    /**
     * The near-miss vocabulary of FAIR-bioHeaders-Tools#125: a V5 value that still looks like a
     * FAIR-bioHeaders URL. Types that have no released contract are named here on purpose - the
     * warning must never suggest that a release URL "works" for them.
     */
    private static final Pattern NEAR_MISS_TYPE = Pattern.compile("(?:^|[/._-])(fhp|fhgff3|fht)(?:[/._-]|$)");

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

    /** A schema URL this reader recognises: the type it names and the ref segment it carries. */
    record Citation(FhType type, String ref) {
    }

    private final SchemaBundle bundle;
    private final TypeRegistry types;
    private final Pattern raw;
    private final Pattern w3id;
    private final Pattern release;

    SchemaSelection(SchemaBundle bundle) {
        this(bundle, bundle.types());
    }

    SchemaSelection(SchemaBundle bundle, TypeRegistry types) {
        this.bundle = bundle;
        this.types = types;
        String keys = types.releasedUrlKeyAlternation();
        this.raw = Pattern.compile(Pattern.quote(RAW_PREFIX) + SPEC_REPO + "/(.+?)/(" + keys + ")\\.json");
        this.w3id = Pattern.compile(Pattern.quote(W3ID_PREFIX) + "(" + keys + ")/(v[0-9]+\\.[0-9]+\\.[0-9]+)/?");
        this.release = Pattern.compile("v[0-9]+\\.[0-9]+\\.[0-9]+");
    }

    /** The {@code (type, ref)} a FAIR-bioHeaders schema URL names, or empty. */
    Optional<Citation> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher raw = this.raw.matcher(value);
        if (raw.matches()) {
            return citation(raw.group(2), raw.group(1));
        }
        Matcher w3id = this.w3id.matcher(value);
        if (w3id.matches()) {
            return citation(w3id.group(1), w3id.group(2));
        }
        return draftCitation(value);
    }

    /**
     * Rule V7: the one form in the lane is the owning development repository's raw-main URL,
     * matched against the citation the registry pins. Only types whose records are a file-bound
     * header can be cited by a {@code schema} value: FHGM records carry no {@code schema} field
     * at all (they ride inside an FHGFF3 header under an {@code x-} key), so its draft is
     * selected by the record reader, never by a citation. A release-shaped URL for a type no
     * release registers is not a draft citation either - it stays rule V5, with the near-miss
     * warning.
     */
    private Optional<Citation> draftCitation(String value) {
        for (HeaderTypeDef def : types.drafts()) {
            if (def.type().transport() == FhType.Transport.EMBEDDED_HEADER
                    && def.draft().citation().equals(value)) {
                return Optional.of(new Citation(def.type(), "main"));
            }
        }
        return Optional.empty();
    }

    private Optional<Citation> citation(String urlKey, String ref) {
        return types.byUrlKey(urlKey).map(def -> new Citation(def.type(), ref));
    }

    /** Rule V6: the {@code schemaVersion} is the JSON number 1 (1 or 1.0, not "1", not a boolean). */
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
     * Select the schema a record must be validated against, from its {@code schema} value and
     * (for rule V6) its {@code schemaVersion}.
     *
     * @throws Unsupported when the value names a release this bundle does not carry (V4)
     */
    public Selection selectRecord(JsonNode metadata) throws Unsupported {
        JsonNode schemaNode = metadata == null ? null : metadata.get("schema");
        String value = schemaNode != null && schemaNode.isString() ? schemaNode.asString() : null;
        Optional<Citation> parsed = parse(value);

        // Rule V6: an FHR 1 record citing raw-main. Draft citations are never FHR, so the
        // reference equality test below is exactly the released-FHR constant.
        if (parsed.isPresent() && parsed.get().type() == FhType.FHR && "main".equals(parsed.get().ref())
                && isFhr1Version(metadata.get("schemaVersion"))) {
            return new Selection(FhType.FHR, FHR1_RELEASE,
                new Issue(Severity.WARNING, "V6", "FHR: warning: " + FHR1_WARNING));
        }
        return select(value);
    }

    /** Rules V1-V5, plus the opted-in V7 lane, from the {@code schema} value alone. */
    public Selection select(String value) throws Unsupported {
        Optional<Citation> parsed = parse(value);
        if (parsed.isEmpty()) {
            // V5: any other value (including a missing/non-string schema) is FHR
            // against the current schema.
            return new Selection(FhType.FHR, "main", nearMissWarning(value));
        }
        FhType type = parsed.get().type();
        String ref = parsed.get().ref();
        if ("main".equals(ref)) {
            return new Selection(type, "main", draftWarning(type));
        }
        if (release.matcher(ref).matches() && bundle.has(type, ref)) {
            return new Selection(type, ref, null);
        }
        throw new Unsupported(type.fileKey() + ": unsupported schema version: " + value
            + " (this tool has " + type.fileKey() + " schemas for "
            + String.join(", ", supported(type)) + ")");
    }

    /**
     * The warning rule V7 requires on every record read through the draft lane. It changes no
     * verdict: a draft reading is still the draft's own result, never a released-contract result,
     * and the warning says which pinned revision it came from.
     */
    private Issue draftWarning(FhType type) {
        if (!type.isDraft()) {
            return null;
        }
        String message = types.def(type).draft().warningMessage(type.fileKey());
        DraftLog.draft(message);
        return new Issue(Severity.WARNING, "V7", message);
    }

    /** The releases (and {@code main}) bundled for a header type (rule V4 message). */
    public List<String> supported(FhType type) {
        if (type.isDraft()) {
            // Draft bytes are pinned, not released: there is no release list to name.
            return List.of("main (draft, experimental)");
        }
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
    Issue nearMissWarning(String value) {
        if (value == null || parse(value).isPresent()) {
            return null;
        }
        String lowered = value.toLowerCase(Locale.ROOT);
        if (!lowered.startsWith(W3ID_PREFIX.toLowerCase(Locale.ROOT))
                && !lowered.startsWith(RAW_PREFIX.toLowerCase(Locale.ROOT))) {
            return null;
        }
        Matcher named = NEAR_MISS_TYPE.matcher(lowered);
        if (named.find()) {
            // The type the URL names, as the reference toolkit words it ("not FHT" for an fht
            // near-miss; this reader said "not FHGFF3" until FAIR-bioHeaders-Tools-Java#2 was fixed).
            String kind = named.group(1).toUpperCase(Locale.ROOT);
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

    /** The types this selection knows, released and opted-in drafts, in registry order. */
    List<FhType> knownTypes() {
        return types.types();
    }
}
