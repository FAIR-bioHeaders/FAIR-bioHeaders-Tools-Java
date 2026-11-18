/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * FORMAT.md R14: a {@code uri}-format metadata value should be an http/https URL.
 * {@code ftp}, {@code urn:}, {@code doi:} and {@code info:} are accepted; anything
 * else (for example {@code javascript:}) stays schema-valid but earns a warning.
 * The checked fields are the {@code format: uri} leaves of each released contract
 * (see docs/STATUS.md for how the table was derived).
 */
final class UriSchemes {

    private static final Set<String> ANY_SCHEME_OK = Set.of("urn", "doi", "info");
    private static final Set<String> HOST_SCHEMES = Set.of("http", "https", "ftp");
    // Leading or trailing whitespace of any kind, or a control / bidi-format
    // character anywhere, means "no accepted scheme" (R14, checked as written).
    private static final Pattern BIDI = Pattern.compile("[\\u202A-\\u202E\\u2066-\\u2069\\u200E\\u200F]");

    /**
     * Fields whose string values carry {@code format: uri}, per header type. The released rows
     * of the table are the reviewed derivation of the three current contracts (docs/STATUS.md);
     * a registry row may declare its own list, and a draft type must, because its contract is not
     * one of the released files this table was derived from.
     */
    static final Map<FhType, List<String>> URI_FIELDS = Map.of(
        FhType.FHR, List.of(
            "accessionID.url", "assemblyProtocol", "assemblySoftware[].uri",
            "relatedLink[]", "taxon.uri"),
        FhType.FHP, List.of(
            "accessionID.url", "derivedFrom[].accessionID.url", "metadataAuthor[].uri",
            "predictionProtocol", "predictionSoftware[].uri", "relatedLink[]",
            "source[].url", "taxon.uri"),
        FhType.FHGFF3, List.of(
            "accessionID.url", "annotationProtocol", "annotationSoftware[].uri",
            "derivedFrom[].accessionID.url", "metadataAuthor[].uri", "relatedLink[]",
            "source[].url", "taxon.uri"));

    static List<String> fieldsFor(HeaderTypeDef def) {
        if (!def.uriFields().isEmpty()) {
            return def.uriFields();
        }
        return URI_FIELDS.getOrDefault(def.type(), List.of());
    }

    private UriSchemes() {
    }

    static boolean schemeAccepted(String value) {
        if (value.isEmpty()) {
            return false;
        }
        String trimmed = value.strip();
        if (!trimmed.equals(value)) {
            return false; // whitespace at either end: no accepted scheme
        }
        for (int k = 0; k < value.length(); k++) {
            char c = value.charAt(k);
            if (Character.isISOControl(c)) {
                return false;
            }
        }
        if (BIDI.matcher(value).find()) {
            return false;
        }
        Matcher m = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):").matcher(value);
        if (!m.find()) {
            return false;
        }
        String scheme = m.group(1).toLowerCase(Locale.ROOT);
        if (ANY_SCHEME_OK.contains(scheme)) {
            return true;
        }
        if (HOST_SCHEMES.contains(scheme)) {
            String rest = value.substring(m.end());
            if (!rest.startsWith("//")) {
                return false;
            }
            String authority = rest.substring(2).split("[/?#]")[0];
            String host = authority.replaceFirst("^.*@", "");
            host = host.replaceFirst(":[0-9]*$", "");
            return !host.isEmpty();
        }
        return false;
    }

    /** Walk the metadata and warn on R14-rejected values in the checked fields. */
    static void collect(HeaderTypeDef def, JsonNode metadata, List<Issue> issues) {
        if (metadata == null || !metadata.isObject()) {
            return;
        }
        for (String field : fieldsFor(def)) {
            for (JsonNode node : resolve(metadata, field)) {
                if (node.isString()) {
                    String value = node.asString();
                    if (!schemeAccepted(value)) {
                        issues.add(Issue.warning("R14",
                            "the " + field + " value '" + Text.printable(value)
                                + "' has no accepted scheme (http, https, ftp, urn, doi, info)"));
                    }
                }
            }
        }
    }

    /** Resolve a dotted path with one optional {@code []} list step, e.g. {@code source[].url}. */
    private static List<JsonNode> resolve(JsonNode root, String path) {
        List<JsonNode> current = List.of(root);
        String[] steps = path.split("\\.");
        for (String step : steps) {
            List<JsonNode> next = new java.util.ArrayList<>();
            if (step.endsWith("[]")) {
                String name = step.substring(0, step.length() - 2);
                for (JsonNode n : current) {
                    JsonNode arr = n.path(name);
                    if (arr.isArray()) {
                        for (JsonNode item : arr) {
                            next.add(item);
                        }
                    }
                }
            } else {
                for (JsonNode n : current) {
                    JsonNode child = n.get(step);
                    if (child != null && !child.isNull()) {
                        next.add(child);
                    }
                }
            }
            current = next;
        }
        return current;
    }
}
