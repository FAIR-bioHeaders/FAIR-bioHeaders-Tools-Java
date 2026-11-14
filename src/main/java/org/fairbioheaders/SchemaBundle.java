/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
 * The FAIR-bioHeaders contract copies this jar carries, keyed by specification release and by
 * header type. The registry is {@code schemas/bundled.json}: its {@code types} array declares
 * the header types (see {@link TypeRegistry}) and its {@code releases} object maps each release
 * tag to the frozen copy it published. The provenance of every byte - source tag, commit and
 * SHA-256 - is {@code schemas/provenance.json}; the copies were additionally verified
 * byte-identical to the frozen copies in the released fair-bioheaders 0.11.0 package on
 * 2026-11-14.
 *
 * <p>Contracts are draft/2020-12 (every released {@code fhr.json}, {@code fhp.json} and
 * {@code fhgff3.json} declares {@code "$schema":
 * "https://json-schema.org/draft/2020-12/schema"}); they are evaluated with networknt
 * json-schema-validator with format assertions enabled, because the contracts rely on the
 * {@code date} and {@code uri} formats.
 *
 * <p>A bundle built with {@link Options} enabling the rule V7 draft lane also carries the
 * development-repository bytes its registry pins. Those bytes are loaded and their SHA-256
 * re-computed when the bundle is built: if the shipped bytes no longer match the digest the
 * registry states, constructing the bundle fails loudly rather than letting a reading that
 * claims to be current move out of sight.
 */
public final class SchemaBundle {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESOURCE_DIR = "/org/fairbioheaders/schemas/";

    private final JsonNode registry;
    private final Options options;
    private final String resourceDir;
    private final TypeRegistry types;
    private final List<String> releaseOrder;
    private final Map<String, Map<FhType, String>> releaseIndex = new LinkedHashMap<>();
    private final Map<String, Schema> cache = new ConcurrentHashMap<>();
    private final SchemaRegistry validatorRegistry =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            b -> b.schemaRegistryConfig(SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(Boolean.TRUE)
                .build()));

    /** The shared instance backed by the schemas inside this jar, released contracts only. */
    public static SchemaBundle getDefault() {
        return Holder.DEFAULT;
    }

    private static final class Holder {
        static final SchemaBundle DEFAULT = new SchemaBundle(Options.defaults());
    }

    /** A bundle of the contracts inside this jar, with the default (released-only) options. */
    public SchemaBundle() {
        this(Options.defaults());
    }

    /**
     * A bundle honouring {@code options}. Draft contracts pinned by the registry are loaded and
     * digest-verified eagerly when the draft lane is enabled.
     */
    public SchemaBundle(Options options) {
        this(options, RESOURCE_DIR, "bundled.json", RESOURCE_DIR);
    }

    /**
     * A bundle whose registry and contract bytes come from elsewhere. Used by the test that
     * proves a new header type is a registry row rather than a code change: the registry lives in
     * one resource directory, the contract bytes it names in another (the shipped ones), and the
     * layout rules are the same as the production bundle.
     */
    SchemaBundle(Options options, String registryDir, String registryName, String contractDir) {
        this.options = options;
        this.resourceDir = contractDir;
        JsonNode loaded;
        try (InputStream in = SchemaBundle.class.getResourceAsStream(registryDir + registryName)) {
            if (in == null) {
                throw new IOException("missing registry resource " + registryDir + registryName);
            }
            loaded = JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.registry = loaded;
        this.types = TypeRegistry.of(this, loaded, options);
        JsonNode releases = loaded.get("releases");
        releaseOrder = new ArrayList<>();
        for (String release : releases.propertyNames()) {
            releaseOrder.add(release);
            Map<FhType, String> byType = new LinkedHashMap<>();
            JsonNode entry = releases.get(release);
            for (HeaderTypeDef def : types.released()) {
                JsonNode file = entry.get(def.type().fileKey());
                if (file != null) {
                    byType.put(def.type(), file.asString());
                }
            }
            releaseIndex.put(release, byType);
        }
        for (HeaderTypeDef draft : types.drafts()) {
            // Fail before reading a single record if the pinned draft bytes are not what the
            // registry says they are. A records-carrying draft has two pinned files, and both are
            // verified: an overlay nobody checked would let the transport rules drift.
            contract(draft.type(), "main");
            if (draft.type().transport() == FhType.Transport.EMBEDDED_RECORDS) {
                transportContract(draft.type());
            }
        }
    }

    InputStream open(String name) {
        InputStream in = SchemaBundle.class.getResourceAsStream(resourceDir + name);
        if (in == null && !RESOURCE_DIR.equals(resourceDir)) {
            // A registry built from another directory (the extensibility-proof test) still
            // resolves the released copies shipped inside this jar.
            in = SchemaBundle.class.getResourceAsStream(RESOURCE_DIR + name);
        }
        return in;
    }

    /** The registry document ({@code types}, {@code current}, {@code releases}). */
    JsonNode registry() {
        return registry;
    }

    /** The options this bundle was built with. */
    public Options options() {
        return options;
    }

    /** The header types this bundle can select: the released ones, plus opted-in drafts. */
    TypeRegistry types() {
        return types;
    }

    /**
     * The draft types the shipped registry declares, whether or not this instance was opted in:
     * the discovery API callers use to decide what to enable. Names carry no release meaning.
     */
    public List<String> declaredDraftTypes() {
        List<String> names = new ArrayList<>();
        for (JsonNode entry : registry.path("types")) {
            if ("draft".equals(entry.path("status").asString(""))) {
                names.add(entry.path("fileKey").asString(""));
            }
        }
        return names;
    }

    /**
     * An unreleased draft contract this jar declares: the citation the lane recognises, the
     * development repository and commit the bytes came from, and their SHA-256. Names here carry
     * no release meaning - see docs/CAPABILITY-MATRIX.md.
     */
    public record Draft(String type, String citation, String repository, String commit,
                        String sha256, String retrieved, String file, String source, String enable,
                        String suiteCommit, String suiteManifestSha256, String sourcePath,
                        String transportFile, String transportSha256, String transportSourcePath,
                        String modelsKey, String carrierFileKey) {

        /**
         * Every contract file this draft pins, with the path it was copied from in its own
         * development repository and the digest recorded for it. A records-carrying draft pins two
         * (the record contract and the transport overlay); a file-bound draft pins one.
         */
        public List<Artifact> pinnedArtifacts() {
            List<Artifact> out = new ArrayList<>();
            out.add(new Artifact(file, sha256, sourcePath));
            if (transportFile != null && !transportFile.isBlank()) {
                out.add(new Artifact(transportFile, transportSha256, transportSourcePath));
            }
            return out;
        }

        /** One pinned contract: its name in this jar, its digest, and its upstream path. */
        public record Artifact(String file, String sha256, String sourcePath) {
        }
    }

    /**
     * Every draft row the shipped registry declares, whether or not this instance is opted in: the
     * discovery call for {@link Options.Builder#enableDraft(String)}, and the source of the
     * commit-and-digest statement rule V7 requires on each record read that way.
     */
    public List<Draft> drafts() {
        List<Draft> out = new ArrayList<>();
        for (JsonNode entry : registry.path("types")) {
            if (!"draft".equals(entry.path("status").asString(""))) {
                continue;
            }
            JsonNode draft = entry.path("draft");
            out.add(new Draft(entry.path("fileKey").asString(""),
                draft.path("citation").asString(""), draft.path("repository").asString(""),
                draft.path("commit").asString(""), draft.path("sha256").asString(""),
                draft.path("retrieved").asString(""), draft.path("file").asString(""),
                draft.path("source").asString(""), draft.path("enable").asString(""),
                draft.path("suiteCommit").asString(""),
                draft.path("suiteManifestSha256").asString(""),
                draft.path("sourcePath").asString(""),
                draft.path("transportFile").asString(""),
                draft.path("transportSha256").asString(""),
                draft.path("transportSourcePath").asString(""),
                draft.path("modelsKey").asString(""),
                draft.path("carrierFileKey").asString("")));
        }
        return out;
    }

    /** The draft contract this bundle pins for {@code fileKey}, or null when it declares none. */
    public Draft draft(String fileKey) {
        for (Draft draft : drafts()) {
            if (draft.type().equalsIgnoreCase(fileKey)) {
                return draft;
            }
        }
        return null;
    }

    /** The SHA-256 of bytes as lower-case hex, the same form the provenance records use. */
    public static String sha256(byte[] bytes) {
        return sha256Hex(bytes);
    }

    /** The release whose copies {@code main} currently equals, e.g. {@code v0.6.0}. */
    public String currentRelease() {
        JsonNode current = registry.get("current").get("release");
        return current == null || current.isNull() ? null : current.asString();
    }

    /** The bundled file name of the current copy of a type's schema. */
    public String currentFile(FhType type) {
        JsonNode files = registry.get("current").get("files");
        JsonNode file = files.get(type.fileKey());
        if (file == null) {
            throw new IllegalArgumentException("registry carries no current " + type.fileKey() + " copy");
        }
        return file.asString();
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
        if (type.isDraft()) {
            if (!"main".equals(release)) {
                throw new IllegalArgumentException(type.fileKey() + " is an unreleased draft: it has "
                    + "no release " + release + " to select, and rule V7 never gives a draft a "
                    + "release form");
            }
            HeaderTypeDef.DraftPin pin = types.def(type).draft();
            return pinnedContract(pin.file(), pin.sha256(), pin.commit(), pin.repository());
        }
        String file = "main".equals(release) ? currentFile(type) : releaseIndexEntry(release).get(type);
        if (file == null) {
            throw new IllegalArgumentException("no bundled " + type.fileKey() + " schema for " + release);
        }
        return cache.computeIfAbsent(type + "@" + file, key -> validatorRegistry.getSchema(
            new ByteArrayInputStream(readBytes(file)), InputFormat.JSON));
    }

    /**
     * The transport overlay of a records-carrying draft (the FHGM shape): the contract that admits
     * the extension key holding the records. Digest-verified the same way as the record contract -
     * an unpinned overlay would let the transport rules drift unnoticed.
     */
    Schema transportContract(FhType type) {
        HeaderTypeDef.DraftPin pin = types.def(type).draft();
        pin.requireCompleteFor(type.transport());
        return pinnedContract(pin.transportFile(), pin.transportSha256(), pin.commit(),
            pin.repository());
    }

    private Schema pinnedContract(String file, String sha256, String commit, String repository) {
        return cache.computeIfAbsent("draft@" + file, key -> {
            byte[] bytes = readBytes(file);
            String actual = sha256Hex(bytes);
            if (!actual.equals(sha256)) {
                throw new IllegalStateException("the draft contract bytes this jar ships as " + file
                    + " have sha256 " + actual + " but the registry pins " + sha256 + " (commit "
                    + commit + " of " + repository + "); refusing to read drafts that do not match"
                    + " their own provenance");
            }
            return validatorRegistry.getSchema(new ByteArrayInputStream(bytes), InputFormat.JSON);
        });
    }

    private byte[] readBytes(String name) {
        try (InputStream in = open(name)) {
            if (in == null) {
                throw new IOException("missing bundled schema " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("no SHA-256 in this JDK", e);
        }
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
