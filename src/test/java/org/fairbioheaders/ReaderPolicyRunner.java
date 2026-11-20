/* MPL-2.0. Supplementary shared reader/runtime corpus, separate from the 291. */
package org.fairbioheaders;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class ReaderPolicyRunner {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("name the exact reader-policy directory");
        Path root = Path.of(args[0]).toRealPath();
        ObjectMapper json = new ObjectMapper();
        JsonNode cases = json.readTree(Files.readString(root.resolve("cases.json"))).get("cases");
        if (cases.size() != 64) throw new IllegalStateException("unexpected corpus count");
        SchemaBundle bundle = SchemaBundle.getDefault();
        SchemaSelection selector = new SchemaSelection(bundle);
        BioHeaders reader = new BioHeaders();
        var probe = (tools.jackson.databind.node.ObjectNode) json.readTree(Files.readString(root.resolve(cases.get(0).path("file").asString())));
        probe.putObject("x-probe").put("checksum", "B".repeat(43) + "=");
        var selectedProbe = selector.selectRecord(probe);
        if (!SchemaBundle.validate(bundle.contract(selectedProbe.type(), selectedProbe.release()), probe).isEmpty())
            throw new IllegalStateException("an arbitrary extension checksum must remain opaque");
        int failed = 0;
        for (JsonNode c : cases) {
            Path file = root.resolve(c.path("file").asString());
            byte[] bytes = Files.readAllBytes(file);
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    .equals(c.path("sha256").asString())) throw new IllegalStateException("corpus digest mismatch");
            String actual;
            try {
                if (c.path("operation").asString().equals("metadata")) {
                    JsonNode record = c.path("serialization").asString("").equals("yaml") ?
                        PortableYaml.load(Files.readString(file)) : json.readTree(bytes);
                    var selected = selector.selectRecord(record);
                    actual = SchemaBundle.validate(bundle.contract(selected.type(), selected.release()), record).isEmpty() ? "valid" : "invalid";
                } else {
                    Kind kind = switch (c.path("format").asString()) {
                        case "gfa" -> Kind.GFA;
                        case "fhgff3" -> Kind.GFF3;
                        default -> Kind.FASTA;
                    };
                    var result = reader.verify(BioHeaders.readAll(file), kind);
                    actual = result.status() == VerifyResult.Status.UNSUPPORTED_VERSION ? "unsupported-version"
                        : result.isValid() ? "valid" : "invalid";
                }
            } catch (SchemaSelection.Unsupported e) {
                actual = "unsupported-version";
            } catch (ScanException | Gunzip.FormatException e) {
                actual = "invalid";
            }
            String expected = c.path("result").asString(c.path("expected").asString());
            boolean ok = actual.equals(expected);
            System.out.println(c.path("id").asString() + (ok ? " PASS" : " FAIL expected=" + expected + " actual=" + actual));
            if (!ok) failed++;
        }
        System.out.println("reader-policy: compared=64 failed=" + failed + " not-run=0");
        if (failed != 0) System.exit(1);
        System.setProperty("fh.spec.dir", root.getParent().getParent().toString());
        var outcomes = new org.fairbioheaders.conformance.ConformanceHarness().runAll();
        long ran = outcomes.stream().filter(o -> o.ran()).count();
        long bad = outcomes.stream().filter(o -> o.ran() && !o.passed()).count();
        long notRun = outcomes.size() - ran;
        System.out.println("current carriers: compared=" + ran + " failed=" + bad + " not-run=" + notRun
            + " (JSON-LD/microdata unsupported)");
        outcomes.stream().filter(o -> o.ran() && !o.passed()).forEach(System.out::println);
        if (ran != 254 || bad != 0 || notRun != 65) System.exit(1);
    }
}
