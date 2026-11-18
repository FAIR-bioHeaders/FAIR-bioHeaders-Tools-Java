#!/bin/sh
# Local dry run of the two CI guard scripts, against the receipts this working tree produced.
set -e
cd "$(dirname "$0")/../.."
registry=src/main/resources/org/fairbioheaders/schemas/bundled.json
url=$(grep -oE '"citation": "https[^"]*"' "$registry" | head -1 | cut -d'"' -f4)
pin=$(grep -oE '"sha256": "[0-9a-f]{64}"' "$registry" | head -1 | cut -d'"' -f4)
commit=$(grep -oE '"commit": "[0-9a-f]{40}"' "$registry" | head -1 | cut -d'"' -f4)
echo "pinned citation=$url"
echo "pinned commit=$commit"
echo "pinned sha256=$pin"
[ -n "$url" ] && [ -n "$pin" ] && [ -n "$commit" ] || { echo "registry pin fields missing" >&2; exit 1; }
shipped=src/main/resources/org/fairbioheaders/schemas/fht-draft.json
shipped_digest=$(shasum -a 256 "$shipped" | cut -d' ' -f1)
echo "shipped bytes: $shipped_digest ($(wc -c < "$shipped") bytes)"
[ "$shipped_digest" = "$pin" ] || { echo "shipped copy does not match its pin" >&2; exit 1; }
control=$(curl -s -o /dev/null -w '%{http_code}' \
  https://raw.githubusercontent.com/FAIR-bioHeaders/FHT-Specification/main/no-such-file.json)
echo "control (expect 404): $control"
[ "$control" = "404" ] || { echo "probe control failed" >&2; exit 1; }
code=$(curl -s -o /tmp/live-fht.json -w '%{http_code}' "$url")
echo "GET $url -> $code"
[ "$code" = "200" ] || { echo "citation did not resolve" >&2; exit 1; }
live=$(shasum -a 256 /tmp/live-fht.json | cut -d' ' -f1)
echo "live raw main: $live"
if [ "$live" != "$pin" ]; then echo "DRIFT (advisory)"; else echo "pin is current"; fi
echo "--- receipt guards ---"
receipt=target/fht-draft-vectors.properties
grep -qx 'vectorsInManifest=82' "$receipt"
grep -qx 'vectorsRan=82' "$receipt"
grep -qx 'vectorsPassed=82' "$receipt"
grep -qx 'contractCommit=86438f21c3a17a58334c97aed4955be1a2393522' "$receipt"
grep -qx 'contractSha256=4156353aa6d077fee2d2c69be4e17859b8e4f81bb10824f3c87f6701de196e7a' "$receipt"
grep -qx 'suiteCommit=86438f21c3a17a58334c97aed4955be1a2393522' "$receipt"
grep -qx 'suiteManifestSha256=e5a4a5d027259e29a7e3cfb42da5e1ef07fa78d4a0e604ef73f28b8e5a59a508' "$receipt"
grep -qx 'note=' "$receipt"
count=$(grep -h 'tests=' target/surefire-reports/TEST-org.fairbioheaders.conformance.FhtDraftVectorTest.xml | head -1 | sed -E 's/.*tests="([0-9]+)".*/\1/')
echo "FhtDraftVectorTest executed $count tests"
[ "$count" -eq 82 ]
echo "ALL LOCAL GUARD CHECKS PASSED"
