#!/bin/sh
# Fail the build unless the native test reports are present, non-empty and free of skips.
#
# Usage: sh .github/scripts/assert-reports.sh <minimum-total-tests> [report-dir]
#
# A green Maven exit is not by itself acceptance: a missing fixture directory, a test class that
# selected nothing, or a whole suite turned into "skipped" all still exit 0. This script reads the
# surefire XML (the native report), sums the per-class counters and requires:
#   * the report directory to exist and contain at least one TEST-*.xml,
#   * every report file to have executed at least one test,
#   * skipped=0 everywhere,
#   * failures=0 and errors=0 everywhere (surefire already fails on these; the check is here so a
#     changed configuration cannot lose the property silently),
#   * at least <minimum-total-tests> cases in aggregate.

set -eu

min=${1:?usage: assert-reports.sh <minimum-total-tests> [report-dir]}
dir=${2:-target/surefire-reports}

if [ ! -d "$dir" ]; then
  echo "no surefire report directory at $dir - the test phase never ran" >&2
  exit 1
fi

files=$(find "$dir" -name 'TEST-*.xml' | sort)
if [ -z "$files" ]; then
  echo "no TEST-*.xml reports in $dir - zero tests ran" >&2
  exit 1
fi

total=0
skipped=0
failed=0
classes=0
for f in $files; do
  line=$(grep -m1 -o '<testsuite[^>]*' "$f" || true)
  if [ -z "$line" ]; then
    echo "$f has no testsuite element" >&2
    exit 1
  fi
  tests=$(printf '%s' "$line" | sed -n 's/.*tests="\([0-9]*\)".*/\1/p')
  skip=$(printf '%s' "$line" | sed -n 's/.*skipped="\([0-9]*\)".*/\1/p')
  fails=$(printf '%s' "$line" | sed -n 's/.*failures="\([0-9]*\)".*/\1/p')
  errs=$(printf '%s' "$line" | sed -n 's/.*errors="\([0-9]*\)".*/\1/p')
  [ -n "$tests" ] || { echo "$f: no tests= attribute" >&2; exit 1; }
  if [ "${tests:-0}" -eq 0 ]; then
    echo "$f: a test class that executed nothing" >&2
    exit 1
  fi
  classes=$((classes + 1))
  total=$((total + tests))
  skipped=$((skipped + ${skip:-0}))
  failed=$((failed + ${fails:-0} + ${errs:-0}))
done

echo "surefire: $classes report file(s), $total test cases, $skipped skipped, $failed failed/errored"

if [ "$skipped" -ne 0 ]; then
  echo "expected zero skipped tests: a skipped vector or suite is a missing fixture, not a pass" >&2
  for f in $files; do
    if grep -m1 -o '<testsuite[^>]*skipped="[1-9][0-9]*"' "$f" >/dev/null 2>&1; then
      echo "  skipped in: $f" >&2
    fi
  done
  exit 1
fi
if [ "$failed" -ne 0 ]; then
  echo "expected zero failures and zero errors" >&2
  exit 1
fi
if [ "$total" -lt "$min" ]; then
  echo "expected at least $min test cases, got $total" >&2
  exit 1
fi
echo "report guard ok ($total >= $min, no skips)"
