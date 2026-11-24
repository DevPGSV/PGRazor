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

mkdir -p ../cache/native-deps ../cache/artifacts ../dist/logs
exec 9>../cache/build.lock
flock -n 9 || { echo 'Another workspace operation is running' >&2; exit 1; }

# Match the host ownership of all bind mounts.
if [[ ! -f .env ]]; then
    printf 'PGRAZOR_UID=%s\nPGRAZOR_GID=%s\n' "$(id -u)" "$(id -g)" > .env
fi
compose=(docker compose -f compose.yaml)
log="$(mktemp "../dist/logs/prepare-$(date -u +%Y%m%d-%H%M%S)-XXXXXXXX.log")"
trap 'rc=$?; printf "Prepare exit status: %s; log: %s\n" "$rc" "$log" >&2' EXIT

{
    "${compose[@]}" config --quiet
    "${compose[@]}" build builder
    "${compose[@]}" run --rm --no-deps -T --pull never builder bash -s <<'CONTAINER'
set -Eeuo pipefail
cd /workspace
python -B tools/prepare_native.py --download
python -B -c "import sys; sys.path.insert(0, 'pgrazor'); import dex_bridges; dex_bridges.dependencies(download=True)"
python -B tools/prepare_native.py
python -B pgrazor/art_build.py --check
python --version
java -version
node --version
cmake --version
ninja --version
sdkmanager --version
CONTAINER
} 2>&1 | tee "$log"
printf '\nPreparation completed.\n'
