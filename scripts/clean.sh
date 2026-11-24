#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT"
if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
    echo "Usage: $0 [--apply] [--keep-latest N] [--min-age-minutes N]"
    echo 'Default: dry-run; keep 3 complete runs per package/ABI; minimum age 30 min.'
    echo 'Local pins: ../cache/nonroot-retention.json (required).'
    exit 0
fi
mkdir -p ../cache
exec 9>../cache/build.lock
flock -n 9 || { echo 'Another workspace operation is running' >&2; exit 1; }
[[ -f ../cache/nonroot-retention.json && ! -L ../cache/nonroot-retention.json ]] || {
    echo 'Missing or unsafe ../cache/nonroot-retention.json' >&2; exit 1;
}
# No signing override, no network; final release mount is read-only.
docker compose -f compose.yaml -f - run --rm --no-deps -T --pull never \
    builder python -B scripts/clean.py "$@" <<'COMPOSE'
services:
  builder:
    network_mode: none
    volumes:
      - ../cache:/cleanup-cache:ro
      - ../dist:/output:ro
COMPOSE
