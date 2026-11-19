/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.fairbioheaders;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.networknt.schema.Schema;

import tools.jackson.databind.JsonNode;

/**
 * The FHGM draft's model-level checks, in Java, against the pinned development bytes.
 *
 * <p><b>What FHGM is, per its own draft.</b> An FHGM record is not a file header: the records ride
 * inside an FHGFF3 header under one extension key ({@code x-fhgm-models}, FORMAT.md R15), the file
 * stays routed by its own released FHGFF3 citation under rules V1-V3, and rule V7 selects only the
 * contract and the validator applied to the records. So there is no FHGM {@code schemaVersion}, no
 * FHGM w3id URL and no FHGM registry row - and calling {@link #check} is not a claim that FHGM is
 * supported. It is only available after the explicit opt-in ({@link Options}).
 *
 * <p>The checks follow the development repository's model-level rules (FHGM-Specification
 * {@code development/conformance/manifest.json}, drafted in {@code development/model/README.md} and
 * observed by {@code validate_fhgm.py}):
 *
 * <ul>
 *   <li><b>B1 boundary</b> - the file must already satisfy the released FHGFF3 base schema and its
 *       exact-byte checksum. Reported by the carrier {@link VerifyResult}; a rejection at this
 *       layer is not evidence about a model.</li>
 *   <li><b>K1 transport</b> - the pinned transport overlay: a released FHGFF3 identity selected by
 *       its cited schema URL (v0.5.0 with schemaVersion 1.1, or v0.6.0 with 1.1 or 1.2, per the
 *       dual-base decision of 2026-11-15 and the URL-keyed selection of FHGM#26) plus a non-empty
 *       {@code x-fhgm-models} array of objects.</li>
 *   <li><b>K2 record shape</b> - every record against the pinned native record contract
 *       ({@code modelId}, {@code modelVersion}, {@code featureId}, {@code reference} required;
 *       nested objects closed), as a pass over the WHOLE array before K3 looks at any field: an
 *       identity the contract has not accepted is not an identity to compare, and reporting two
 *       records that both lack {@code modelId} as one ambiguous ID would invent the string it
 *       claims they share. The contract's text rule is part of this step and is evaluated in the
 *       Unicode terms the contract states - see {@link DraftTextPolicy}.</li>
 *   <li><b>K3 identity</b> - one {@code modelId} per snapshot, compared after NFC normalisation
 *       and before any per-record rule. A collision - same spelling repeated, or distinct
 *       spellings that fold together - makes the identity a revision pointer needs unselectable,
 *       reported as ambiguous, and rejects the snapshot.</li>
 *   <li><b>K4 targeting</b> - the record's {@code featureId} must percent-decode to exactly one
 *       local {@code gene} row (or {@code SO:0000704}) in the enclosing file, and since the
 *       2026-11-18 bytes (FHGM#29) that row must be <em>root-level</em>: a gene carrying a
 *       {@code Parent} attribute does not resolve. Root means the tag is absent. {@code Parent=} is
 *       a present tag with an empty value list under the GFF3 attribute grammar and a bare
 *       {@code Parent} is a token that grammar cannot read, so neither is evidence of a root: both
 *       reject, and this reader does not report a relationship it could not resolve. Whether K4
 *       should abstain instead is an open upstream question (FHGM#22, {@code JS#11}), not something
 *       this class decides.</li>
 *   <li><b>K5 reference</b> - the record's reference must share at least one comparable form with
 *       the file's genome link (accession + SeqCol digest, or the annotated FHR checksum) and must
 *       never contradict it. Both halves are enforced.</li>
 *   <li><b>K6 pointer</b> - a {@code predecessor} claim resolves against the prior snapshots the
 *       caller supplied, and only those: it must name its own model ID, record a different version
 *       that the predecessor really carries, match the assembly link and record reference, skip no
 *       recorded successor, and not point at the current file or at history nobody handed over.</li>
 * </ul>
 *
 * <p>What a resolvable pointer establishes is a predecessor record - not curator acceptance, not a
 * correct model, and not biological validity, which are separate checks in separate places.
 */
public final class FhgmModels {

    /** The model-layer verdict for one file. */
    public enum State {
        PASS,
        REJECT
    }

    /** One revision pointer's verdict. */
    public enum PointerVerdict {
        RESOLVABLE,
        UNRESOLVABLE
    }

    /**
     * The outcome for one file. {@code issues} are ordered as the rules fire, so the first model
     * issue carries the rule id the development suite would name; {@code pointers} reports one
     * verdict per claiming record, and an unsupplied history is {@link PointerVerdict#UNRESOLVABLE}
     * rather than silence.
     */
    public record Report(State state, VerifyResult carrier, List<Issue> issues,
                         List<String> modelIds, Map<String, PointerVerdict> pointers,
                         List<Issue> draftWarnings) {

        public boolean passed() {
            return state == State.PASS && carrier.isValid();
        }
    }

    /** A record's comparable reference forms, and the file's comparable genome-link forms. */
    private record LinkKeys(String accession, String seqcolId, String fhrChecksum) {

        boolean conflictsWith(LinkKeys other) {
            return differs(accession, other.accession) || differs(seqcolId, other.seqcolId)
                || differs(fhrChecksum, other.fhrChecksum);
        }

        boolean matches(LinkKeys other) {
            return !conflictsWith(other) && sharesAForm(other);
        }

        private boolean sharesAForm(LinkKeys other) {
            return equal(accession, other.accession) || equal(seqcolId, other.seqcolId)
                || equal(fhrChecksum, other.fhrChecksum);
        }

        private static boolean differs(String a, String b) {
            return a != null && b != null && !a.equals(b);
        }

        private static boolean equal(String a, String b) {
            return a != null && a.equals(b);
        }

        private static final FhgmModels.LinkKeys EMPTY = new LinkKeys(null, null, null);
    }

    /** One snapshot after B1/K1-K5: its header, its records by model ID, and its checksum. */
    private record Snapshot(JsonNode header, Map<String, JsonNode> models, String checksum) {
    }

    private final SchemaBundle bundle;
    private final Options options;
    private final Schema transportOverlay;
    private final Schema recordContract;
    /** The record paths the pinned contract types as {@code $defs.text}; see DraftTextPolicy. */
    private final java.util.Set<String> textPaths;
    private final String modelsKey;
    private final FhType type;
    private final HeaderTypeDef.DraftPin pin;

    /**
     * Opens the FHGM model layer. Requires the draft lane to be enabled for FHGM; without it this
     * throws, because reading pinned development bytes is never a default.
     */
    public static FhgmModels open(Options options) {
        return new FhgmModels(new SchemaBundle(options), options);
    }

    /** A model layer over the contracts inside this jar, with {@code options} applied. */
    public FhgmModels(Options options) {
        this(new SchemaBundle(options), options);
    }

    public FhgmModels(SchemaBundle bundle, Options options) {
        this.bundle = bundle;
        this.options = options;
        this.type = FhType.of("fhgm", "FHGM", FhType.Status.DRAFT, FhType.Transport.EMBEDDED_RECORDS);
        if (!options.draftEnabled("FHGM")) {
            throw new IllegalStateException("FHGM is an unreleased draft and its lane is not opted"
                + " in: pass Options.builder().enableDraft(\"FHGM\").build() (or the"
                + " org.fairbioheaders.experimental.drafts property). Reading pinned draft bytes is"
                + " never the default behaviour.");
        }
        HeaderTypeDef def = bundle.types().def(type);
        this.pin = def.draft();
        this.modelsKey = pin.modelsKey();
        this.transportOverlay = bundle.transportContract(type);
        this.recordContract = bundle.contract(type, "main");
        this.textPaths = DraftTextPolicy.textPaths(readTree(bundle, pin.file()));
    }

    /** The options this instance was opened with; the lane must be enabled for FHGM. */
    public Options options() {
        return options;
    }

    /** The extension key the records ride under, as the registry declares it. */
    public String modelsKey() {
        return modelsKey;
    }

    /** The pinned revision this instance reads: commit and SHA-256 of the record contract bytes. */
    public String pinnedCommit() {
        return pin.commit();
    }

    public String pinnedSha256() {
        return pin.sha256();
    }

    /**
     * Check the records one FHGFF3 file carries.
     *
     * @param data         the file's decompressed bytes (the carrier header is read and verified
     *                     with the released FHGFF3 contract, exactly as {@link BioHeaders#verify}
     *                     would)
     * @param predecessors the prior snapshot files the caller supplies for pointer resolution;
     *                     nothing else is consulted, and a pointer to anything outside this list is
     *                     unresolvable rather than unchecked
     */
    public Report check(byte[] data, List<byte[]> predecessors) {
        BioHeaders carrier = new BioHeaders(bundle);
        VerifyResult base = carrier.verify(data, Kind.GFF3);
        List<Issue> issues = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();
        if (base.metadata() == null) {
            return new Report(State.REJECT, base, issues, List.of(), Map.of(), warnings);
        }
        JsonNode header = base.metadata();
        warnings.add(Issue.warning("V7", pin.warningMessage(type.fileKey())
            + "; FHGM records are read inside an FHGFF3 header under '" + modelsKey
            + "', which the carrier contract admits as an extension field (FORMAT.md R15)"));
        DraftLog.draft(warnings.get(0).message());

        // B1: the base schema and the file-bound checksum come first, and a failure there is not a
        // model verdict. The carrier result already reports both.
        if (!base.isValid()) {
            issues.add(Issue.error("B1", "the enclosing file does not satisfy the released FHGFF3"
                + " base schema and its exact-byte checksum, so no FHGM verdict exists: "
                + base.errors()));
            return new Report(State.REJECT, base, issues, List.of(), Map.of(), warnings);
        }

        // K1: the transport overlay.
        issues.addAll(labelled(SchemaBundle.validate(transportOverlay, header), "K1"));
        JsonNode models = header.get(modelsKey);
        if (models == null || !models.isArray() || models.size() == 0) {
            // K1 already said so; there is nothing left to check at record level.
            return new Report(issues.isEmpty() ? State.PASS : State.REJECT, base, issues, List.of(),
                Map.of(), warnings);
        }

        Snapshot snapshot;
        try {
            snapshot = validateSnapshot(data, header, models, issues);
        } catch (ModelCheckFailure e) {
            issues.add(e.issue());
            return new Report(State.REJECT, base, issues, List.of(), Map.of(), warnings);
        }
        if (!issues.isEmpty()) {
            return new Report(State.REJECT, base, issues, List.copyOf(snapshot.models.keySet()),
                Map.of(), warnings);
        }

        Map<String, Snapshot> history = new LinkedHashMap<>();
        for (byte[] prior : predecessors) {
            VerifyResult priorBase = carrier.verify(prior, Kind.GFF3);
            if (!priorBase.isValid() || priorBase.metadata() == null) {
                issues.add(Issue.error("B1", "a supplied predecessor snapshot fails the base FHGFF3"
                    + " schema or its own checksum: " + priorBase.errors()));
                return new Report(State.REJECT, priorBase, issues,
                    List.copyOf(snapshot.models.keySet()), Map.of(), warnings);
            }
            JsonNode priorHeader = priorBase.metadata();
            JsonNode priorModels = priorHeader.get(modelsKey);
            List<Issue> priorIssues = new ArrayList<>(
                labelled(SchemaBundle.validate(transportOverlay, priorHeader), "K1"));
            if (priorModels == null || !priorModels.isArray() || priorModels.size() == 0) {
                issues.add(Issue.error("K1", "a supplied predecessor snapshot carries no records"
                    + " under '" + modelsKey + "'"));
                return new Report(State.REJECT, base, issues, List.copyOf(snapshot.models.keySet()),
                    Map.of(), warnings);
            }
            try {
                history.put(priorHeader.path("checksum").asString(),
                    validateSnapshot(prior, priorHeader, priorModels, priorIssues));
            } catch (ModelCheckFailure e) {
                // An ambiguous or malformed history is itself a rejection: a pointer cannot be
                // resolved against a snapshot whose identities are not selectable.
                issues.add(e.issue());
                return new Report(State.REJECT, base, issues, List.copyOf(snapshot.models.keySet()),
                    Map.of(), warnings);
            }
            if (!priorIssues.isEmpty()) {
                issues.addAll(priorIssues);
                return new Report(State.REJECT, base, issues, List.copyOf(snapshot.models.keySet()),
                    Map.of(), warnings);
            }
        }

        Map<String, PointerVerdict> pointers = new LinkedHashMap<>();
        LinkKeys fileKeys = assemblyKeys(header);
        for (Map.Entry<String, JsonNode> entry : snapshot.models.entrySet()) {
            JsonNode model = entry.getValue();
            if (!model.has("predecessor")) {
                continue;
            }
            Map.Entry<PointerVerdict, String> verdict =
                resolvePredecessor(entry.getKey(), model, header, snapshot, history);
            pointers.put(entry.getKey(), verdict.getKey());
            if (verdict.getKey() == PointerVerdict.UNRESOLVABLE) {
                issues.add(Issue.error("K6", "model " + quoted(entry.getKey())
                    + " predecessor is unresolvable: " + verdict.getValue()));
                return new Report(State.REJECT, base, issues,
                    List.copyOf(snapshot.models.keySet()), pointers, warnings);
            }
        }
        return new Report(State.PASS, base, issues, List.copyOf(snapshot.models.keySet()), pointers,
            warnings);
    }

    /** The records of one snapshot after K1-K5, or the first rule that rejects the snapshot. */
    private Snapshot validateSnapshot(byte[] data, JsonNode header, JsonNode models, List<Issue> sink)
        throws ModelCheckFailure {
        sink.addAll(labelled(SchemaBundle.validate(transportOverlay, header), "K1"));
        if (!sink.isEmpty()) {
            throw new ModelCheckFailure(sink.get(0));
        }
        List<JsonNode> records = new ArrayList<>();
        models.forEach(records::add);
        // NOTE on where the feature layer is read. The draft's checker indexes the rows only AFTER the
        // whole-array K2 pass and after K3 (`validate_snapshot`: base selection -> base schema ->
        // transport -> checksum -> K2 for every record -> NFC K3 -> `gene_targets(data)` -> K4/K5 per
        // record). Reading it earlier means a malformed row - nine columns is the first thing this
        // index checks - gets reported as K4 before the records themselves have been looked at, so a
        // missing required field or a duplicate identity disappears behind a feature-layer fault. The
        // index is therefore built here, after both, and nothing before this point touches a row.

        // K2 first, over the WHOLE array: every record must satisfy the contract's shape before any
        // rule reads a field of it. This is the order the draft's own checker uses, and the order the
        // two statements depend on. A rule that compares identities is not entitled to an identity the
        // contract has not accepted - reading a missing `modelId` as "" and then reporting two records
        // as sharing that empty ID invents an identity out of an absent field, and points the finding
        // at the wrong rule. And a record whose shape is already invalid cannot be the reason a
        // different, real collision goes unreported either.
        for (int index = 0; index < records.size(); index++) {
            JsonNode model = records.get(index);
            List<Issue> shape = labelled(SchemaBundle.validate(recordContract, model), "K2");
            if (!shape.isEmpty()) {
                throw new ModelCheckFailure(Issue.error("K2", "model record " + index
                    + " is invalid under the pinned draft contract: " + shape.get(0).message()));
            }
            // The contract's text rule is stated in Unicode terms, which this evaluator's `\s` is not;
            // DraftTextPolicy applies the sentence the schema spells out, before any rule looks at a
            // field, so an invisible edge on one field cannot be reported as another rule's finding.
            String invisible = firstTextViolation(model, "", textPaths);
            if (invisible != null) {
                throw new ModelCheckFailure(Issue.error("K2", "model record " + index
                    + " is invalid under the pinned draft contract: " + invisible));
            }
        }

        // K3 identity next, before any per-record rule, comparing modelIds after NFC normalisation:
        // a collision is the named outcome `ambiguous` and rejects the whole snapshot, so it must not
        // be preempted or masked by whichever K4/K5 check happens to trip on one of the colliding rows
        // first (suite rule K3; the 2026-11-18 vectors
        // duplicate-model-id-reported-before-record-checks and model-ids-equal-under-nfc hold this
        // reader to exactly that precedence and normalisation). Every ID reaching here has cleared K2,
        // so it is a real string the contract accepted.
        Map<String, List<String>> idsByNormalized = new LinkedHashMap<>();
        for (JsonNode model : records) {
            String modelId = model.path("modelId").asString();
            idsByNormalized
                .computeIfAbsent(Normalizer.normalize(modelId, Normalizer.Form.NFC),
                    k -> new ArrayList<>())
                .add(modelId);
        }
        for (List<String> collision : idsByNormalized.values()) {
            if (collision.size() < 2) {
                continue;
            }
            if (collision.stream().distinct().count() == 1) {
                throw new ModelCheckFailure(Issue.error("K3", "Ambiguous model ID "
                    + quoted(collision.get(0)) + ": " + collision.size()
                    + " records share it in one snapshot"));
            }
            throw new ModelCheckFailure(Issue.error("K3", "Ambiguous model ID "
                + quoted(collision.get(0)) + " and " + quoted(collision.get(1))
                + ": distinct spellings equal under NFC normalisation, " + collision.size()
                + " records share one snapshot identity in one snapshot"));
        }

        // K4 and K5 per record, in the order the draft states them, and only now is the feature
        // layer read.
        Gff3FeatureIndex targets = featureIndex(data);
        LinkKeys fileKeys = assemblyKeys(header);
        Map<String, JsonNode> byModelId = new LinkedHashMap<>();
        for (JsonNode model : records) {
            String modelId = model.path("modelId").asString();
            String featureId = model.path("featureId").asString();
            List<String> types = targets.typesOf(featureId);
            if (types.size() != 1 || !isGene(types.get(0))) {
                // Identity is compared as decoded bytes (see Gff3FeatureIndex), which is also why
                // nothing here can name a row whose ID bytes are not valid UTF-8: no text featureId
                // encodes to them. Say so, because "absent" and "unreadable by any text ID" are
                // different faults with different owners, and collapsing them would hide the second.
                String unreadable = targets.undecodableIdCount() == 0 ? ""
                    : "; the feature layer carries " + targets.undecodableIdCount()
                        + " row ID(s) whose percent-decoded bytes are not valid UTF-8, so no text"
                        + " featureId can name them and none was matched by substituting U+FFFD";
                throw new ModelCheckFailure(Issue.error("K4", "model " + quoted(modelId)
                    + " must target one local gene row: " + quoted(featureId)
                    + " resolves to " + types + unreadable));
            }
            Gff3FeatureIndex.ParentState parent = targets.parentOf(featureId);
            if (parent == Gff3FeatureIndex.ParentState.NESTED) {
                // FHGM#29 widened K4's gene target to a ROOT-level gene row: a gene that carries
                // a Parent attribute is nested, and a pointer into it does not resolve as the
                // draft now requires. This is a semantics change inside the pinned bytes, not a
                // new rule label.
                throw new ModelCheckFailure(Issue.error("K4", "model " + quoted(modelId)
                    + " must target one local gene row: " + quoted(featureId)
                    + " has a Parent attribute, so it is not a root gene row"));
            }
            if (parent == Gff3FeatureIndex.ParentState.INDETERMINATE) {
                // The row carries a Parent tag that says nothing readable - `Parent=`, or a bare
                // `Parent` with no value. That is not a root claim: the gene feature the record points
                // at has a parent relationship the reader cannot resolve, and a PASS here would assert
                // the one thing this library did not establish. Feature-line validation belongs to
                // gff3-validator, which is a second reason not to settle the question from here.
                throw new ModelCheckFailure(Issue.error("K4", "model " + quoted(modelId)
                    + " must target one local gene row: " + quoted(featureId)
                    + " carries a Parent attribute with no readable value, so this reader cannot"
                    + " establish that it is a root gene row"));
            }
            byModelId.putIfAbsent(modelId, model);
            LinkKeys recordKeys = referenceKeys(model);
            if (recordKeys.conflictsWith(fileKeys)) {
                throw new ModelCheckFailure(Issue.error("K5", "model " + quoted(modelId)
                    + " carries a reference that contradicts the file genome link"));
            }
            if (!recordKeys.matches(fileKeys)) {
                throw new ModelCheckFailure(Issue.error("K5", "model " + quoted(modelId)
                    + " has no reference form comparable with the file genome link"));
            }
        }
        return new Snapshot(header, byModelId, header.path("checksum").asString());
    }

    /**
     * The first {@code $defs.text} value in one record that violates the contract's text rule,
     * described with the field path it was found under, or {@code null}.
     *
     * <p>Only the paths in {@code textPaths} are checked - the sites the pinned schema actually types
     * as {@code $defs/text}, followed through {@code $ref} and {@code allOf}. Checking every string
     * instead would be the same mistake in the other direction: {@code publication.article} carries a
     * DOI pattern of its own and no text reference, so a blanket walk rejects values the contract
     * accepts, and a false invalidity is as wrong as a false pass.
     */
    private static String firstTextViolation(JsonNode node, String path,
                                                     java.util.Set<String> textPaths) {
        if (node.isString()) {
            if (!textPaths.contains(path)) {
                return null;
            }
            String violation = DraftTextPolicy.violation(node.asString());
            return violation == null ? null : (path.isEmpty() ? "the value" : path) + " " + violation;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                String found = firstTextViolation(field.getValue(),
                    DraftTextPolicy.path(path, field.getKey()), textPaths);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                String found = firstTextViolation(element, path + "[]", textPaths);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The pinned contract's own JSON, for schema-directed scoping (bytes are digest-checked at load). */
    private static JsonNode readTree(SchemaBundle bundle, String file) {
        try (java.io.InputStream in = bundle.open(file)) {
            return new tools.jackson.databind.ObjectMapper().readTree(in);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** K6, the bounded pointer resolution of the draft. Never throws; reports instead. */
    private Map.Entry<PointerVerdict, String> resolvePredecessor(String modelId, JsonNode model,
                                                                 JsonNode header, Snapshot current,
                                                                 Map<String, Snapshot> history) {
        JsonNode pointer = model.get("predecessor");
        String snapshotChecksum = pointer.path("annotationChecksum").asString("");
        if (snapshotChecksum.equals(current.checksum()) || !history.containsKey(snapshotChecksum)) {
            return verdict(PointerVerdict.UNRESOLVABLE,
                "the predecessor snapshot is the current file or was not supplied");
        }
        if (!modelId.equals(pointer.path("modelId").asString(null))) {
            return verdict(PointerVerdict.UNRESOLVABLE, "the pointer names another model ID "
                + quoted(pointer.path("modelId").asString("")));
        }
        Snapshot prior = history.get(snapshotChecksum);
        JsonNode priorModel = prior.models().get(modelId);
        if (priorModel == null) {
            return verdict(PointerVerdict.UNRESOLVABLE,
                "the predecessor snapshot has no model " + quoted(modelId));
        }
        String pointerVersion = pointer.path("modelVersion").asString("");
        String priorVersion = priorModel.path("modelVersion").asString("");
        if (!priorVersion.equals(pointerVersion)) {
            return verdict(PointerVerdict.UNRESOLVABLE, "the predecessor records version "
                + quoted(priorVersion) + ", not " + quoted(pointerVersion));
        }
        String ownVersion = model.path("modelVersion").asString("");
        if (priorVersion.equals(ownVersion)) {
            return verdict(PointerVerdict.UNRESOLVABLE,
                "the revision reuses the predecessor model version");
        }
        for (Map.Entry<String, Snapshot> otherEntry : history.entrySet()) {
            if (otherEntry.getKey().equals(snapshotChecksum)) {
                continue;
            }
            JsonNode other = otherEntry.getValue().models().get(modelId);
            JsonNode otherPointer = other == null ? null : other.get("predecessor");
            if (otherPointer != null
                    && snapshotChecksum.equals(otherPointer.path("annotationChecksum").asString(null))
                    && modelId.equals(otherPointer.path("modelId").asString(null))
                    && pointerVersion.equals(otherPointer.path("modelVersion").asString(null))
                    && !priorVersion.equals(other.path("modelVersion").asString(""))
                    && assemblyKeys(otherEntry.getValue().header()).matches(assemblyKeys(prior.header()))
                    && referenceKeys(other).matches(referenceKeys(priorModel))) {
                return verdict(PointerVerdict.UNRESOLVABLE, "the pointed record already has a"
                    + " recorded successor in supplied snapshot " + abbreviate(otherEntry.getKey()));
            }
        }
        if (!assemblyKeys(header).matches(assemblyKeys(prior.header()))) {
            return verdict(PointerVerdict.UNRESOLVABLE,
                "no matching assembly link with the predecessor snapshot");
        }
        if (!referenceKeys(model).matches(referenceKeys(priorModel))) {
            return verdict(PointerVerdict.UNRESOLVABLE,
                "no matching record reference with the predecessor record");
        }
        return verdict(PointerVerdict.RESOLVABLE, "predecessor " + abbreviate(snapshotChecksum)
            + " version " + quoted(pointerVersion));
    }

    /** The file's comparable genome-link forms: accession plus SeqCol digest, or an FHR parent. */
    private static LinkKeys assemblyKeys(JsonNode header) {
        String accession = null;
        String seqcol = null;
        String fhr = null;
        JsonNode reference = header.get("genomeReference");
        if (reference != null && reference.isObject()) {
            accession = text(reference, "accession");
            seqcol = text(reference, "seqcol_id");
        }
        JsonNode derived = header.get("derivedFrom");
        if (derived != null && derived.isArray()) {
            for (JsonNode link : derived) {
                if ("FHR".equals(link.path("headerType").asString(null))
                        && "annotates".equals(link.path("relationship").asString(null))) {
                    fhr = text(link, "checksum");
                }
            }
        }
        return new LinkKeys(accession, seqcol, fhr);
    }

    /** The record's comparable reference forms. */
    private static LinkKeys referenceKeys(JsonNode model) {
        JsonNode reference = model.get("reference");
        if (reference == null || !reference.isObject()) {
            return LinkKeys.EMPTY;
        }
        String accession = null;
        String seqcol = null;
        JsonNode genome = reference.get("genomeReference");
        if (genome != null && genome.isObject()) {
            accession = text(genome, "accession");
            seqcol = text(genome, "seqcol_id");
        }
        return new LinkKeys(accession, seqcol, text(reference, "annotates"));
    }

    private static Gff3FeatureIndex featureIndex(byte[] data) throws ModelCheckFailure {
        try {
            return Gff3FeatureIndex.of(data);
        } catch (Gff3FeatureIndex.MalformedRow e) {
            throw new ModelCheckFailure(Issue.error("K4",
                "the enclosing GFF3 feature layer cannot be read: " + e.getMessage()));
        }
    }

    private static boolean isGene(String type) {
        return "gene".equals(type) || "SO:0000704".equals(type);
    }

    private static List<Issue> labelled(List<Issue> issues, String rule) {
        List<Issue> out = new ArrayList<>();
        for (Issue issue : issues) {
            out.add(new Issue(issue.severity(), rule, issue.message()));
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() ? null : value.asString();
    }

    private static String quoted(String value) {
        return "'" + value + "'";
    }

    private static String abbreviate(String checksum) {
        return checksum.length() <= 8 ? checksum : checksum.substring(0, 8) + "...";
    }

    private static Map.Entry<PointerVerdict, String> verdict(PointerVerdict state, String detail) {
        return Map.entry(state, detail);
    }

    /** Internal control flow: the first rule that rejects a snapshot, with its labelled issue. */
    private static final class ModelCheckFailure extends Exception {
        private static final long serialVersionUID = 1L;
        private final Issue issue;

        ModelCheckFailure(Issue issue) {
            this.issue = issue;
        }

        Issue issue() {
            return issue;
        }
    }
}
