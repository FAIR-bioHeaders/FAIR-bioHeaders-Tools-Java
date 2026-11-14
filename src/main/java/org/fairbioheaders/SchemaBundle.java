/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The released FAIR-bioHeaders contract copies bundled with this jar, keyed by
 * specification release. The registry is {@code schemas/bundled.json}; the provenance
 * of every byte is {@code schemas/provenance.json} (source tags, commits and SHA-256
 * digests — the copies were additionally verified byte-identical to the frozen copies
 * in the released fair-bioheaders 0.11.0 package on 2026-11-14).
 *
 * <p>Contracts are draft/2020-12 (all released {@code fhr.json}, {@code fhp.json} and
 * {@code fhgff3.json} declare {@code "$schema":
 * "https://json-schema.org/draft/2020-12/schema"}); they are evaluated with
 * networknt json-schema-validator with format assertions enabled, because the
 * contracts rely on the {@code date} and {@code uri} formats.
 */
public final class SchemaBundle {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESOURCE_DIR = "/org/fairbioheaders/schemas/";

    private final JsonNode registry;
    private final List<String> releaseOrder;
    private final Map<String, Map<FhType, String>> releaseIndex = new LinkedHashMap<>();
    private final Map<String, Schema> cache = new ConcurrentHashMap<>();
    private final SchemaRegistry validatorRegistry =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            b -> b.schemaRegistryConfig(SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(Boolean.TRUE)
                .build()));

    /** The shared instance backed by the schemas inside this jar. */
    public static SchemaBundle getDefault() {
        return Holder.DEFAULT;
    }

    private static final class Holder {
        static final SchemaBundle DEFAULT = new SchemaBundle();
    }

    private SchemaBundle() {
        registry = readResource("bundled.json");
        JsonNode releases = registry.get("releases");
        releaseOrder = new ArrayList<>();
        for (String release : releases.propertyNames()) {
            releaseOrder.add(release);
            Map<FhType, String> byType = new EnumMap<>(FhType.class);
            JsonNode types = releases.get(release);
            for (FhType type : FhType.values()) {
                JsonNode file = types.get(type.fileKey());
                if (file != null) {
                    byType.put(type, file.asString());
                }
            }
            releaseIndex.put(release, byType);
        }
    }

    private static JsonNode readResource(String name) {
        try (InputStream in = SchemaBundle.class.getResourceAsStream(RESOURCE_DIR + name)) {
            if (in == null) {
                throw new IOException("missing bundled resource " + name);
            }
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The release whose copies {@code main} currently equals, e.g. {@code v0.6.0}. */
    public String currentRelease() {
        JsonNode current = registry.get("current").get("release");
        return current == null || current.isNull() ? null : current.asString();
    }

    /** The bundled file name of the current copy of a type's schema. */
    public String currentFile(FhType type) {
        return registry.get("current").get("files").get(type.fileKey()).asString();
    }

    public boolean has(FhType type, String release) {
        Map<FhType, String> byType = releaseIndex.get(release);
        return byType != null && byType.containsKey(type);
    }

    /** The bundled releases carrying a type, in registry order. */
    public List<String> releasesWith(FhType type) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Map<FhType, String>> e : releaseIndex.entrySet()) {
            if (e.getValue().containsKey(type)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    Map<FhType, String> releaseIndexEntry(String release) {
        return releaseIndex.getOrDefault(release, Map.of());
    }

    Map<String, Map<FhType, String>> releaseIndex() {
        return releaseIndex;
    }

    /** The compiled contract for a (type, release) selection. */
    Schema contract(FhType type, String release) {
        String file = "main".equals(release) ? currentFile(type) : releaseIndexEntry(release).get(type);
        if (file == null) {
            throw new IllegalArgumentException("no bundled " + type.fileKey() + " schema for " + release);
        }
        return cache.computeIfAbsent(type + "@" + file, key -> {
            try (InputStream in = SchemaBundle.class.getResourceAsStream(RESOURCE_DIR + file)) {
                if (in == null) {
                    throw new IOException("missing bundled schema " + file);
                }
                return validatorRegistry.getSchema(in, InputFormat.JSON);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** Validate metadata against a contract; each schema failure becomes an ERROR issue. */
    public static List<Issue> validate(Schema contract, JsonNode metadata) {
        List<Issue> issues = new ArrayList<>();
        for (Error error : contract.validate(metadata)) {
            issues.add(Issue.error("schema", error.getMessage()));
        }
        return issues;
    }
}
