#!/usr/bin/env bash
# Measures which action(s) EscalationService's LLM actually chooses across
# repeated runs of each LLM-escalation demo scenario, to answer README's
# "Paging-bias question" with real numbers instead of a hunch.
#
# Requires: the app running locally (default localhost:8099), curl, jq.
# Each run is a real Gemini API call - N=20 across 3 scenarios is 60 calls,
# not instant and not free. Adjust N below if you want more/less confidence.
#
# Usage: ./scripts/measure-paging-bias.sh [N] [base_url]

set -euo pipefail

N="${1:-20}"
BASE_URL="${2:-http://localhost:8099}"

SCENARIOS=(
  "unknown-pattern"          # HALF_OPEN + real backlog - SHOULD favor PAGE_ONCALL
  "dlq-backlog-recovered"    # CLOSED breakers + backlog - SHOULD favor REPLAY_DLQ_BATCH
  "transient-backpressure"   # HALF_OPEN, no other symptoms - SHOULD favor ADJUST_RETRY_BUDGET
)

echo "Running $N calls per scenario against $BASE_URL ..."
echo

for scenario in "${SCENARIOS[@]}"; do
  echo "=== $scenario ==="
  for i in $(seq 1 "$N"); do
    curl -s -X POST "$BASE_URL/api/v1/agent/demo/$scenario" \
      | jq -r '.actionsInvoked | if length == 0 then "NONE" else join(",") end'
  done | sort | uniq -c | sort -rn
  echo
done

echo "Interpretation:"
echo "  - unknown-pattern dominated by PAGE_ONCALL: correct, not bias."
echo "  - dlq-backlog-recovered or transient-backpressure dominated by PAGE_ONCALL:"
echo "    that's the real signal - those scenarios are structurally suited to the"
echo "    other two actions, so heavy paging there means the model is defaulting"
echo "    to the 'safe' choice rather than actually exercising the gated actions."
echo "  - NONE (no tool called at all): worth investigating separately - means the"
echo "    model responded in prose without calling any RemediationTools method."
