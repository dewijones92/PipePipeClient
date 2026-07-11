#!/usr/bin/env bash
# Code-quality gate, run by .github/workflows/quality.yml (and runnable locally).
#
# Gate 1: no committed "dewidebug" lines — that prefix marks throwaway investigation
#         logging which must be stripped before commit.
# Gate 2: DRY ratchet — the number of duplicated code blocks (PMD CPD, main sources)
#         must not exceed ci/cpd-baseline. New duplication fails the build; if you
#         reduce duplication, lower the baseline in the same PR to lock in the win.
#
# Per-repo settings (source dirs, CPD languages) live in ci/quality.conf.
set -euo pipefail
cd "$(dirname "$0")/.."

# shellcheck source=ci/quality.conf
source ci/quality.conf # defines SRC_DIRS and CPD_LANGUAGES arrays

# --- Gate 1: no dewidebug logging -------------------------------------------------
if grep -rn "dewidebug" "${SRC_DIRS[@]}"; then
    echo "FAIL: committed 'dewidebug' temporary logging found (see above). Strip it."
    exit 1
fi
echo "OK: no dewidebug logging."

# --- Gate 2: duplication ratchet (PMD CPD) -----------------------------------------
PMD_VERSION=7.26.0
PMD_SHA256=9f55cb7ff0e9f9a66dd2f005eaa370e84c8a4cd971b134aa14a930c4a283ebc9
MIN_TOKENS=120
TOOLS_DIR=.quality-tools
PMD_BIN="$TOOLS_DIR/pmd-bin-$PMD_VERSION/bin/pmd"

if [ ! -x "$PMD_BIN" ]; then
    mkdir -p "$TOOLS_DIR"
    curl -sSfL -o "$TOOLS_DIR/pmd.zip" \
        "https://github.com/pmd/pmd/releases/download/pmd_releases/$PMD_VERSION/pmd-dist-$PMD_VERSION-bin.zip"
    echo "$PMD_SHA256  $TOOLS_DIR/pmd.zip" | sha256sum -c -
    unzip -qo "$TOOLS_DIR/pmd.zip" -d "$TOOLS_DIR"
fi

DIR_ARGS=()
for d in "${SRC_DIRS[@]}"; do
    DIR_ARGS+=(--dir "$d")
done

total=0
for lang in "${CPD_LANGUAGES[@]}"; do
    report="$TOOLS_DIR/cpd-$lang.xml"
    "$PMD_BIN" cpd --minimum-tokens "$MIN_TOKENS" --language "$lang" \
        "${DIR_ARGS[@]}" -f xml --no-fail-on-violation > "$report"
    n=$(grep -c "<duplication " "$report" || true)
    echo "CPD ($lang): $n duplicated block(s)"
    if [ "$n" -gt 0 ]; then
        # Human-readable locations for the log.
        "$PMD_BIN" cpd --minimum-tokens "$MIN_TOKENS" --language "$lang" \
            "${DIR_ARGS[@]}" -f text --no-fail-on-violation | head -60
    fi
    total=$((total + n))
done

baseline=$(cat ci/cpd-baseline)
echo "CPD total: $total (baseline: $baseline)"
if [ "$total" -gt "$baseline" ]; then
    echo "FAIL: duplication grew above the baseline. DRY the new code up," \
        "or (only with good reason) raise ci/cpd-baseline."
    exit 1
elif [ "$total" -lt "$baseline" ]; then
    echo "NOTE: duplication is below the baseline — lower ci/cpd-baseline to $total to lock it in."
fi
echo "OK: duplication within baseline."
