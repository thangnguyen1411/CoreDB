#!/usr/bin/env bash
set -uo pipefail

cd "$(dirname "$0")"

echo "=== CoreDB Test Runner ==="
echo "Running Maven tests..."
mvn test -q 2>&1 | tail -30
echo "=== Tests Complete ==="
