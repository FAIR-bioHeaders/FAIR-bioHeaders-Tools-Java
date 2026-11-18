/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * The header types a {@link BioHeaders} instance knows about, read from the {@code types}
 * array of a bundled registry ({@code schemas/bundled.json}).
 *
 * <p>Two groups:
 * <ul>
 *   <li><b>released</b> types - contracts FAIR-bioHeaders-Specification has published under a
 *       release tag with a row in {@code schemas/versions.json}. Their URL keys are what rules
 *       V1-V3 recognise, and they are the only types a reader recognises by default;</li>
 *   <li><b>drafts</b> - development contracts (rule V7) that are active only with explicit
 *       opt-in ({@link Options}) and whose contract bytes are pinned by commit and SHA-256 in
 *       the registry entry.</li>
 * </ul>
 *
 * <p>The family's five types (FHR, FHP, FHGFF3, FHT, FHGM) are five rows of this registry, not
 * five branches of the reader: a type contributes its URL key, file key, transport, {@code
 * format: uri} field list and - if it is a draft - its pinned bytes. {@code
 * src/test/resources/org/fairbioheaders/testtypes/bundled-proof.json} is a second registry,
 * shipped as test data, that declares an extra released-style type and an extra draft type;
 * {@code TypeRegistryTest} runs the reader over it to prove a new type needs no change to this
 * class.
 */
final class TypeRegistry {

    private final Map<FhType, HeaderTypeDef> defs;

    private TypeRegistry(List<HeaderTypeDef> types) {
        Map<FhType, HeaderTypeDef> byType = new LinkedHashMap<>();
        for (HeaderTypeDef def : types) {
            if (byType.putIfAbsent(def.type(), def) != null) {
                throw new IllegalStateException("duplicate type in registry: " + def.type());
            }
        }
        this.defs = byType;
    }

    /** The released types only - exactly the default behavior. */
    static TypeRegistry releasedOnly(SchemaBundle bundle, JsonNode registry) {
        return of(bundle, registry, Options.defaults());
    }

    static TypeRegistry of(SchemaBundle bundle, JsonNode registry, Options options) {
        JsonNode types = registry.get("types");
        if (types == null || !types.isArray() || types.size() == 0) {
            throw new IllegalStateException("bundled registry has no 'types' array");
        }
        List<HeaderTypeDef> active = new ArrayList<>();
        for (JsonNode entry : types) {
            String fileKey = required(entry, "fileKey");
            String urlKey = required(entry, "urlKey").toLowerCase(Locale.ROOT);
            String status = required(entry, "status").toLowerCase(Locale.ROOT);
            FhType.Transport transport = transport(required(entry, "transport").toLowerCase(Locale.ROOT));
            List<String> uriFields = new ArrayList<>();
            JsonNode fields = entry.get("uriFields");
            if (fields != null) {
                for (JsonNode field : fields) {
                    uriFields.add(field.asString());
                }
            }
            java.util.Set<Kind> containers = new java.util.LinkedHashSet<>();
            JsonNode declared = entry.get("containers");
            if (declared != null) {
                for (JsonNode container : declared) {
                    containers.add(Kind.valueOf(container.asString().toUpperCase(Locale.ROOT)));
                }
            }
            switch (status) {
                case "released" -> active.add(new HeaderTypeDef(
                    releasedConstant(urlKey, fileKey, transport), uriFields, null, containers));
                case "draft" -> {
                    FhType type = FhType.of(urlKey, fileKey, FhType.Status.DRAFT, transport);
                    if (options.draftEnabled(fileKey)) {
                        HeaderTypeDef.DraftPin draftPin = pin(fileKey, entry.get("draft"));
                        draftPin.requireCompleteFor(transport);
                        // A draft's R14 field list is read off the draft's own bytes unless the row
                        // states one, so registering a type never means hand-copying a table from
                        // a schema nobody diffed it against.
                        List<String> derivedFields = uriFields.isEmpty()
                            ? UriFields.derive(bundle, draftPin.file())
                            : uriFields;
                        active.add(new HeaderTypeDef(type, derivedFields, draftPin, containers));
                    }
                }
                default -> throw new IllegalStateException(
                    "registry entry " + fileKey + " has unknown status " + status);
            }
        }
        return new TypeRegistry(active);
    }

    /**
     * A released row must name one of the released {@link FhType} constants, because a released
     * type is public API and a resource file cannot mint public API. Making a draft released is
     * the shared specification's action, and this reader then adds the constant deliberately.
     */
    private static FhType releasedConstant(String urlKey, String fileKey, FhType.Transport transport) {
        for (FhType candidate : FhType.releasedTypes()) {
            if (candidate.urlKey().equals(urlKey) && candidate.fileKey().equals(fileKey)
                    && candidate.transport() == transport) {
                return candidate;
            }
        }
        throw new IllegalStateException("no released FhType constant for " + fileKey + " (" + urlKey
            + ", " + transport + ")");
    }

    private static FhType.Transport transport(String name) {
        return switch (name) {
            case "embedded-header" -> FhType.Transport.EMBEDDED_HEADER;
            case "embedded-records" -> FhType.Transport.EMBEDDED_RECORDS;
            default -> throw new IllegalStateException("unknown transport: " + name);
        };
    }

    /** The pinned draft bytes a registry row declares, or nothing if the row is incomplete. */
    private static HeaderTypeDef.DraftPin pin(String fileKey, JsonNode draft) {
        if (draft == null || !draft.isObject()) {
            throw new IllegalStateException("draft registry entry " + fileKey + " has no 'draft' object");
        }
        String status = draft.path("status").asString("");
        if (!"draft".equals(status)) {
            throw new IllegalStateException(
                "draft registry entry " + fileKey + " lacks status: draft");
        }
        return new HeaderTypeDef.DraftPin(
            required(draft, "file", fileKey),
            required(draft, "citation", fileKey),
            required(draft, "repository", fileKey),
            required(draft, "commit", fileKey),
            required(draft, "sha256", fileKey),
            required(draft, "retrieved", fileKey),
            optional(draft, "source"),
            optional(draft, "enable"),
            optional(draft, "suiteCommit"),
            optional(draft, "suiteManifestSha256"),
            optional(draft, "transportFile"),
            optional(draft, "transportSha256"),
            optional(draft, "transportCommit"),
            optional(draft, "modelsKey"),
            optional(draft, "carrierFileKey"));
    }

    private static String required(JsonNode node, String field) {
        return required(node, field, null);
    }

    private static String required(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalStateException("registry entry " + (context == null ? "" : context + " ")
                + "is missing '" + field + "'");
        }
        return value.asString();
    }

    private static String optional(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() ? null : value.asString();
    }

    List<HeaderTypeDef> all() {
        return List.copyOf(defs.values());
    }

    List<HeaderTypeDef> released() {
        return defs.values().stream().filter(d -> !d.isDraft()).toList();
    }

    /** The drafts this instance was opted into; empty unless rule V7 was enabled. */
    List<HeaderTypeDef> drafts() {
        return defs.values().stream().filter(HeaderTypeDef::isDraft).toList();
    }

    HeaderTypeDef def(FhType type) {
        HeaderTypeDef def = defs.get(type);
        if (def == null) {
            throw new IllegalArgumentException("type is not registered in this reader: " + type);
        }
        return def;
    }

    Optional<HeaderTypeDef> find(FhType type) {
        return Optional.ofNullable(defs.get(type));
    }

    /** A registered type by its URL key, released or active draft. */
    Optional<HeaderTypeDef> byUrlKey(String urlKey) {
        String key = urlKey.toLowerCase(Locale.ROOT);
        return all().stream().filter(d -> d.type().urlKey().equals(key)).findFirst();
    }

    /**
     * The alternation of released URL keys that rules V1-V3 match, regex-quoted and in registry
     * order. Draft URL keys are never in it: rule V7 recognises a draft only through its exact
     * raw-main citation, so {@code fht} must not match the released-URL forms.
     */
    String releasedUrlKeyAlternation() {
        List<String> keys = released().stream().map(d -> Pattern.quote(d.type().urlKey())).toList();
        return String.join("|", keys);
    }

    /** Every registered type, released and active draft, in registry order. */
    List<FhType> types() {
        return List.copyOf(defs.keySet());
    }
}
