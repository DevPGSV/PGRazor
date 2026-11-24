#!/usr/bin/env bash
set -euo pipefail
umask 077
case "${1:-}" in inspect|catalog) ;; *) exit 2 ;; esac
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"
source scripts/build-config.env
export RESEARCH_INPUT
RESEARCH_INPUT=$(realpath -- "../files/$INPUT_NAME")
test -f "$RESEARCH_INPUT"
export RESEARCH_UID=$(id -u) RESEARCH_GID=$(id -g)
destination="$(dirname "$root")/research/$GAME_VERSION"
mkdir -p -- "$destination"
export RESEARCH_OUTPUT
RESEARCH_OUTPUT=$(mktemp -d -- "$destination/$1-XXXXXXXX")
printf 'Research output: %s\n' "$RESEARCH_OUTPUT"
docker image inspect --format '{{.Id}}' pgrazor-research:cpp2il21-jadx157 >"$RESEARCH_OUTPUT/image-id.txt"
docker compose --env-file /dev/null -f compose.research.yaml run --rm --no-deps -T --pull never analysis \
    python3 -B /tools/analyze.py "$1" "$INPUT_SHA"
