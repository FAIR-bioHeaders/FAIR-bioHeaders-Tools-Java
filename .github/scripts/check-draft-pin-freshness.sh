#!/bin/sh
# Advisory draft-pin freshness check (Tools-Java#14 item 1).
#
# Checks every pinned draft target in schemas/bundled.json and schemas/provenance.json:
#   1. FHT:fht.json (contract, shipped as fht-draft.json)
#   2. FHT:conformance/manifest.json (suite manifest, suite-only pin)
#   3. FHGM:development/model/fhgm-model.schema.json (record contract, shipped as fhgm-model-draft.json)
#   4. FHGM:development/model/fhgm-draft.schema.json (transport overlay, shipped as fhgm-transport-draft.json)
#   5. FHGM:development/conformance/manifest.json (suite manifest, suite-only pin)
#   6. FHGM:development/model/base-schemas.json (base registry recorded in provenance.json)
#
# Usage:
#   sh .github/scripts/check-draft-pin-freshness.sh
#   sh .github/scripts/check-draft-pin-freshness.sh --self-test
#
# Environment overrides (used by --self-test):
#   FH_RAW_BASE       Base URL or file:// URL replacing https://raw.githubusercontent.com/FAIR-bioHeaders
#   FH_REGISTRY_FILE  Path to bundled.json
#   FH_PROVENANCE_FILE Path to provenance.json
#   FH_SCHEMA_DIR     Path to directory containing shipped schema files

set -eu

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

extract_field() {
  # Extract the first "key": "value" from stdin.
  key="$1"
  grep -oE "\"$key\": \"[^\"]*\"" | head -1 | cut -d'"' -f4 || true
}

run_check() {
  raw_base="${FH_RAW_BASE:-https://raw.githubusercontent.com/FAIR-bioHeaders}"
  registry="${FH_REGISTRY_FILE:-src/main/resources/org/fairbioheaders/schemas/bundled.json}"
  provenance="${FH_PROVENANCE_FILE:-src/main/resources/org/fairbioheaders/schemas/provenance.json}"
  dir="${FH_SCHEMA_DIR:-src/main/resources/org/fairbioheaders/schemas}"

  if [ ! -s "$registry" ] || [ ! -s "$provenance" ]; then
    echo "missing registry ($registry) or provenance ($provenance)" >&2
    return 1
  fi

  workdir=$(mktemp -d "${TMPDIR:-/tmp}/fh-freshness.XXXXXX")
  trap 'rm -rf "$workdir"' EXIT

  # 1. 404 control probe on every owning draft repository before trusting any 200 read.
  for repo in FHT-Specification FHGM-Specification; do
    control_url="$raw_base/$repo/main/no-such-file-404-control.json"
    control_code=$(curl -s -o /dev/null -w '%{http_code}' "$control_url" || true)
    echo "control ($repo, expect 404): $control_code"
    if [ "$control_code" != "404" ] && [ "$control_code" != "000" ]; then
      echo "probe control failed for $repo (got HTTP $control_code from $control_url); any result below is meaningless" >&2
      return 1
    fi
    # For file:// URLs curl returns 000 when a file does not exist; verify a positive file:// control never returns 404 on everything.
    if [ "$control_code" = "000" ]; then
      case "$raw_base" in
        file://*) ;;
        *)
          echo "probe control failed for $repo (HTTP 000 from $control_url); network or endpoint unreachable" >&2
          return 1
          ;;
      esac
    fi
  done

  drift=0
  unusable=0
  checked=0

  echo "target | pinned | shipped | live | verdict"
  echo "--- | --- | --- | --- | ---"

  check_target() {
    label="$1"
    repo="$2"
    source_path="$3"
    commit="$4"
    pin="$5"
    shipped_file="$6"

    if [ -z "$repo" ] || [ -z "$source_path" ] || [ -z "$commit" ] || [ -z "$pin" ]; then
      echo "$label | ${pin:-MISSING} | - | - | UNUSABLE (missing registry fields)"
      echo "$label: registry fields missing" >&2
      unusable=1
      return 0
    fi

    if [ "$shipped_file" != "-" ]; then
      if [ ! -s "$dir/$shipped_file" ]; then
        echo "$label | $pin ($commit) | MISSING | - | UNUSABLE (missing shipped file $shipped_file)"
        echo "$label: missing shipped file $dir/$shipped_file" >&2
        unusable=1
        return 0
      fi
      shipped=$(sha256_file "$dir/$shipped_file")
      if [ -z "$shipped" ] || [ "$shipped" != "$pin" ]; then
        echo "$label | $pin ($commit) | ${shipped:-EMPTY} | - | UNUSABLE (shipped != pinned)"
        echo "$label: shipped copy ($shipped) does not match its own pin ($pin)" >&2
        unusable=1
        return 0
      fi
    else
      shipped="n/a (suite/registry pin)"
    fi

    url="$raw_base/$repo/main/$source_path"
    out_file="$workdir/live-$(echo "$label" | tr ':/' '__')"
    rm -f "$out_file"
    code=$(curl -s -o "$out_file" -w '%{http_code}' "$url" || true)

    # Guard explicitly against unreadable or empty live downloads: never compare "" = "".
    # (file:// bases are used by --self-test; curl reports HTTP 000 for them on macOS even when
    # the bytes arrive, so for local bases the delivered-bytes test is authoritative and the
    # status code is not.)
    case "$raw_base" in
      file://*)
        if [ ! -s "$out_file" ]; then
          echo "$label | $pin ($commit) | $shipped | FILE_EMPTY | UNKNOWN"
          echo "$label: local read from $url returned no bytes" >&2
          unusable=1
          return 0
        fi
        ;;
      *)
        if [ "$code" != "200" ] || [ ! -s "$out_file" ]; then
          echo "$label | $pin ($commit) | $shipped | HTTP_${code:-000}_EMPTY | UNKNOWN"
          echo "$label: live read from $url failed or returned empty bytes (HTTP ${code:-000})" >&2
          unusable=1
          return 0
        fi
        ;;
    esac

    live=$(sha256_file "$out_file")
    if [ -z "$live" ]; then
      echo "$label | $pin ($commit) | $shipped | EMPTY_DIGEST | UNKNOWN"
      echo "$label: could not compute digest for $url" >&2
      unusable=1
      return 0
    fi

    checked=$((checked + 1))
    if [ "$live" != "$pin" ]; then
      echo "$label | $pin ($commit) | $shipped | $live | DRIFT"
      echo "::warning title=Stale $label draft pin::$label raw main now has sha256 $live; this jar pins $pin (commit $commit). Re-pin deliberately."
      drift=1
    else
      echo "$label | $pin ($commit) | $shipped | $live | CURRENT"
    fi
  }

  # Read each draft block from bundled.json by JSON structure, never by positional
  # whole-file grep (the Tools-R#23 class). python3's json parser locates the row by
  # its fileKey and flattens it to a "key value" per line, so a nested previousPin —
  # or any future sub-object — cannot re-point extraction at a superseded digest.
  row_block() {  # row_block <registry> <fileKey> -> flattened "key value" lines, or empty
    python3 - "$1" "$2" <<'PY'
import json, sys
try:
    with open(sys.argv[1]) as f:
        doc = json.load(f)
    for t in doc.get("types", []):
        if t.get("fileKey") == sys.argv[2] and isinstance(t.get("draft"), dict):
            d = t["draft"]
            scalars = {k: v for k, v in d.items() if isinstance(v, (str, int, float, bool))}
            for k, v in scalars.items():
                print(k, v)
            break
except Exception:
    pass
PY
  }

  fht_row=$(row_block "$registry" "FHT")
  extract_kv() { printf '%s\n' "$1" | awk -v k="$2" '$1==k{print $2; exit}'; }
  fht_repo=$(extract_kv "$fht_row" repository)
  fht_file=$(extract_kv "$fht_row" file)
  fht_source=$(extract_kv "$fht_row" sourcePath)
  fht_commit=$(extract_kv "$fht_row" commit)
  fht_sha=$(extract_kv "$fht_row" sha256)
  fht_suite_commit=$(extract_kv "$fht_row" suiteCommit)
  fht_suite_sha=$(extract_kv "$fht_row" suiteManifestSha256)

  check_target "FHT:$fht_source" "$fht_repo" "$fht_source" "$fht_commit" "$fht_sha" "$fht_file"
  check_target "FHT:conformance/manifest.json" "$fht_repo" "conformance/manifest.json" "$fht_suite_commit" "$fht_suite_sha" "-"

  fhgm_row=$(row_block "$registry" "FHGM")
  fhgm_repo=$(extract_kv "$fhgm_row" repository)
  fhgm_file=$(extract_kv "$fhgm_row" file)
  fhgm_source=$(extract_kv "$fhgm_row" sourcePath)
  fhgm_commit=$(extract_kv "$fhgm_row" commit)
  fhgm_sha=$(extract_kv "$fhgm_row" sha256)
  fhgm_t_file=$(extract_kv "$fhgm_row" transportFile)
  fhgm_t_source=$(extract_kv "$fhgm_row" transportSourcePath)
  fhgm_t_commit=$(extract_kv "$fhgm_row" transportCommit)
  fhgm_t_sha=$(extract_kv "$fhgm_row" transportSha256)
  fhgm_suite_commit=$(extract_kv "$fhgm_row" suiteCommit)
  fhgm_suite_sha=$(extract_kv "$fhgm_row" suiteManifestSha256)

  check_target "FHGM:$fhgm_source" "$fhgm_repo" "$fhgm_source" "$fhgm_commit" "$fhgm_sha" "$fhgm_file"
  check_target "FHGM:$fhgm_t_source" "$fhgm_repo" "$fhgm_t_source" "$fhgm_t_commit" "$fhgm_t_sha" "$fhgm_t_file"
  check_target "FHGM:development/conformance/manifest.json" "$fhgm_repo" "development/conformance/manifest.json" "$fhgm_suite_commit" "$fhgm_suite_sha" "-"

  # The FHGM base-registry pin is required; check_target rejects a missing digest.
  base_note=$(sed -n '/"baseSchemas": {/,/"selectionRule":/p' "$provenance")
  base_sha=$(printf '%s\n' "$base_note" | grep -oE 'sha256 [0-9a-f]{64}' | head -1 | cut -d' ' -f2 || true)
  check_target "FHGM:development/model/base-schemas.json" "$fhgm_repo" "development/model/base-schemas.json" "$fhgm_suite_commit" "$base_sha" "-"

  if [ "$unusable" -ne 0 ]; then
    echo "one or more draft pin targets were UNKNOWN or UNUSABLE; refusing to report pins as current" >&2
    return 1
  fi

  if [ "$checked" -lt 5 ]; then
    echo "expected at least 5 pinned draft targets to be checked, only checked $checked" >&2
    return 1
  fi

  if [ "$drift" -ne 0 ]; then
    echo "at least one pinned draft target ($checked checked) has moved on the owning repository's main:"
    echo "the reading stays valid evidence about the pinned commit, and about nothing newer"
    return 0
  fi

  echo "all $checked pinned draft targets are current"
  return 0
}

run_self_test() {
  testdir=$(mktemp -d "${TMPDIR:-/tmp}/fh-freshness-selftest.XXXXXX")
  # Resolve symlinks (macOS $TMPDIR lives under /var -> /private/var; curl's file://
  # handler does not follow them, and a self-test that cannot read its own fixtures
  # would report UNUSABLE for an environmental reason, not a real one).
  testdir=$(cd "$testdir" && pwd -P)
  trap 'rm -rf "$testdir"' EXIT

  registry="src/main/resources/org/fairbioheaders/schemas/bundled.json"
  provenance="src/main/resources/org/fairbioheaders/schemas/provenance.json"
  dir="src/main/resources/org/fairbioheaders/schemas"

  mkdir -p "$testdir/FHT-Specification/main/conformance"
  mkdir -p "$testdir/FHGM-Specification/main/development/model"
  mkdir -p "$testdir/FHGM-Specification/main/development/conformance"

  cp "$dir/fht-draft.json" "$testdir/FHT-Specification/main/fht.json"
  cp "$dir/fhgm-model-draft.json" "$testdir/FHGM-Specification/main/development/model/fhgm-model.schema.json"
  # The shipped file is the jar's name; the fake live tree must carry the source path.
  cp "$dir/fhgm-transport-draft.json" "$testdir/FHGM-Specification/main/development/model/fhgm-draft.schema.json"

  # Synthetic files for the 2 suite-manifest targets. For base-schemas.json, use the shipped
  # copy ONLY if it digests to the value provenance.json pins (a stale or differently-scoped
  # copy under the resources directory must not fake a CURRENT); otherwise synthesise and
  # re-pin it in the temporary provenance, which still exercises the per-row extraction.
  printf '{"manifest":"fht"}\n' > "$testdir/FHT-Specification/main/conformance/manifest.json"
  printf '{"manifest":"fhgm"}\n' > "$testdir/FHGM-Specification/main/development/conformance/manifest.json"
  base_pinned=$(sed -n '/"baseSchemas": {/,/"selectionRule":/p' "$provenance" \
    | grep -oE 'sha256 [0-9a-f]{64}' | head -1 | cut -d' ' -f2 || true)
  base_live="$testdir/FHGM-Specification/main/development/model/base-schemas.json"
  base_ok=0
  if [ -f "$dir/base-schemas.json" ] && [ -n "$base_pinned" ]; then
    cp "$dir/base-schemas.json" "$base_live"
    [ "$(sha256_file "$base_live")" = "$base_pinned" ] && base_ok=1
  fi
  if [ "$base_ok" -ne 1 ]; then
    printf '{"baseSchemas":"selftest"}\n' > "$base_live"
    base_sha=$(sha256_file "$base_live")
    sed -e "s/${base_pinned:-0000000000000000000000000000000000000000000000000000000000000000}/$base_sha/g" \
        "$provenance" > "$testdir/provenance.json"
  else
    cp "$provenance" "$testdir/provenance.json"
  fi

  fht_m_sha=$(sha256_file "$testdir/FHT-Specification/main/conformance/manifest.json")
  fhgm_m_sha=$(sha256_file "$testdir/FHGM-Specification/main/development/conformance/manifest.json")

  sed -e "s/e5a4a5d027259e29a7e3cfb42da5e1ef07fa78d4a0e604ef73f28b8e5a59a508/$fht_m_sha/g" \
      -e "s/e41f05fd8bb8b8ec175b0192b80832041c84e15c325876aa66a86ba6e59413ce/$fhgm_m_sha/g" \
      "$registry" > "$testdir/bundled.json"

  # Case 1: all 6 targets match -> CURRENT, prints "all 6 pinned draft targets are current"
  out1=$(FH_RAW_BASE="file:///$testdir" FH_REGISTRY_FILE="$testdir/bundled.json" FH_PROVENANCE_FILE="$testdir/provenance.json" run_check 2>&1)
  echo "$out1" | grep -q "all 6 pinned draft targets are current" || {
    echo "SELF-TEST FAIL (case 1): expected all 6 current, got:\n$out1" >&2
    return 1
  }

  # Case 2: moved transport overlay -> DRIFT on FHGM:development/model/fhgm-draft.schema.json, never "are current"
  printf ' ' >> "$testdir/FHGM-Specification/main/development/model/fhgm-draft.schema.json"
  out2=$(FH_RAW_BASE="file:///$testdir" FH_REGISTRY_FILE="$testdir/bundled.json" FH_PROVENANCE_FILE="$testdir/provenance.json" run_check 2>&1)
  if echo "$out2" | grep -q "are current"; then
    echo "SELF-TEST FAIL (case 2): moved overlay still printed 'are current':\n$out2" >&2
    return 1
  fi
  echo "$out2" | grep -q "FHGM:development/model/fhgm-draft.schema.json .* | DRIFT" || {
    echo "SELF-TEST FAIL (case 2): moved overlay did not report DRIFT row:\n$out2" >&2
    return 1
  }
  cp "$dir/fhgm-transport-draft.json" "$testdir/FHGM-Specification/main/development/model/fhgm-draft.schema.json"

  # Case 3: moved FHT suite manifest -> DRIFT on FHT:conformance/manifest.json, never "are current"
  printf '{"manifest":"fht-moved"}\n' > "$testdir/FHT-Specification/main/conformance/manifest.json"
  out3=$(FH_RAW_BASE="file:///$testdir" FH_REGISTRY_FILE="$testdir/bundled.json" FH_PROVENANCE_FILE="$testdir/provenance.json" run_check 2>&1)
  if echo "$out3" | grep -q "are current"; then
    echo "SELF-TEST FAIL (case 3): moved FHT suite manifest still printed 'are current':\n$out3" >&2
    return 1
  fi
  echo "$out3" | grep -q "FHT:conformance/manifest.json .* | DRIFT" || {
    echo "SELF-TEST FAIL (case 3): moved FHT suite manifest did not report DRIFT row:\n$out3" >&2
    return 1
  }
  printf '{"manifest":"fht"}\n' > "$testdir/FHT-Specification/main/conformance/manifest.json"

  # Case 4: moved FHGM suite manifest -> DRIFT on FHGM:development/conformance/manifest.json
  printf '{"manifest":"fhgm-moved"}\n' > "$testdir/FHGM-Specification/main/development/conformance/manifest.json"
  out4=$(FH_RAW_BASE="file:///$testdir" FH_REGISTRY_FILE="$testdir/bundled.json" FH_PROVENANCE_FILE="$testdir/provenance.json" run_check 2>&1)
  if echo "$out4" | grep -q "are current"; then
    echo "SELF-TEST FAIL (case 4): moved FHGM suite manifest still printed 'are current':\n$out4" >&2
    return 1
  fi
  echo "$out4" | grep -q "FHGM:development/conformance/manifest.json .* | DRIFT" || {
    echo "SELF-TEST FAIL (case 4): moved FHGM suite manifest did not report DRIFT row:\n$out4" >&2
    return 1
  }
  printf '{"manifest":"fhgm"}\n' > "$testdir/FHGM-Specification/main/development/conformance/manifest.json"

  # Case 5: empty live read -> UNKNOWN and non-zero exit, never CURRENT
  : > "$testdir/FHT-Specification/main/conformance/manifest.json"
  set +e
  out5=$(FH_RAW_BASE="file:///$testdir" FH_REGISTRY_FILE="$testdir/bundled.json" FH_PROVENANCE_FILE="$testdir/provenance.json" run_check 2>&1)
  rc5=$?
  set -e
  if [ "$rc5" -eq 0 ]; then
    echo "SELF-TEST FAIL (case 5): empty live read exited 0:\n$out5" >&2
    return 1
  fi
  if echo "$out5" | grep -q "are current"; then
    echo "SELF-TEST FAIL (case 5): empty live read printed 'are current':\n$out5" >&2
    return 1
  fi
  echo "$out5" | grep -q "FHT:conformance/manifest.json .* | UNKNOWN" || {
    echo "SELF-TEST FAIL (case 5): empty live read did not report UNKNOWN:\n$out5" >&2
    return 1
  }

  # Case 6: missing live target -> UNKNOWN and non-zero exit
  rm -f "$testdir/FHT-Specification/main/conformance/manifest.json"
  set +e
  out6=$(FH_RAW_BASE="file:///$testdir" FH_REGISTRY_FILE="$testdir/bundled.json" FH_PROVENANCE_FILE="$testdir/provenance.json" run_check 2>&1)
  rc6=$?
  set -e
  if [ "$rc6" -eq 0 ]; then
    echo "SELF-TEST FAIL (case 6): missing live target exited 0:\n$out6" >&2
    return 1
  fi
  echo "$out6" | grep -q "FHT:conformance/manifest.json .* | UNKNOWN" || {
    echo "SELF-TEST FAIL (case 6): missing live target did not report UNKNOWN:\n$out6" >&2
    return 1
  }

  # Case 7: a `previous`/history block inserted FIRST inside the FHT draft row must not
  # re-point extraction (the Tools-R#23 ordinal-shift class: positional reads take the
  # superseded digest, per-row structured reads cannot). The live pin stays the jar's;
  # if the extractor took the inserted field, FHT:fht.json could not print CURRENT.
  printf '{"manifest":"fht"}\n' > "$testdir/FHT-Specification/main/conformance/manifest.json"
  python3 - "$testdir/bundled.json" "$testdir/bundled_prev.json" <<'PY'
import json, sys
src, dst = sys.argv[1], sys.argv[2]
with open(src) as f:
    doc = json.load(f)
for t in doc["types"]:
    if t.get("fileKey") == "FHT":
        d = t["draft"]
        # rebuild with a previousPin block inserted before the live fields (the
        # superseded digest sits at the front, exactly where an ordinal reader errs)
        reordered = {"status": d["status"], "previousPin": {"sha256": "0"*64, "commit": "1"*40}}
        for k, v in d.items():
            if k == "status":
                continue
            reordered[k] = v
        t["draft"] = reordered
with open(dst, "w") as f:
    json.dump(doc, f, indent=2)
    f.write("\n")
PY
  out7=$(FH_RAW_BASE="file:///$testdir" FH_REGISTRY_FILE="$testdir/bundled_prev.json" FH_PROVENANCE_FILE="$testdir/provenance.json" run_check 2>&1)
  if ! echo "$out7" | grep -q "all 6 pinned draft targets are current"; then
    echo "SELF-TEST FAIL (case 7): a leading previousPin block re-pointed the extraction:"
    echo "$out7" >&2
    return 1
  fi

  echo "draft-pin-freshness self-test ok (7 cases: current, moved overlay, moved FHT manifest, moved FHGM manifest, empty read, missing read, previousPin-first)"
}

if [ "${1:-}" = "--self-test" ]; then
  run_self_test
else
  run_check
fi
