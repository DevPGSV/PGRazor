#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT"
case "${1:-}" in
    -h|--help) echo "Usage: $0"; exit 0 ;;
    '') ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
esac
(( $# == 0 )) || { echo 'Too many arguments' >&2; exit 2; }

mkdir -p ../cache ../dist/logs
exec 9>../cache/build.lock
flock -n 9 || { echo 'Another workspace operation is running' >&2; exit 1; }

log="$(mktemp "../dist/logs/check-$(date -u +%Y%m%d-%H%M%S)-XXXXXXXX.log")"
trap 'rc=$?; printf "Check exit status: %s; log: %s\n" "$rc" "$log" >&2' EXIT

# An inline Compose override disables networking without enabling signing.
docker compose -f compose.yaml -f - run --rm --no-deps -T --pull never \
    builder bash -Eeuo pipefail -c '
        cd /workspace
        python -B pgrazor/build.py
        python -B tools/check.py
    ' <<'COMPOSE' 2>&1 | tee "$log"
services:
  builder:
    network_mode: none
COMPOSE
printf '\nChecks completed.\n'
