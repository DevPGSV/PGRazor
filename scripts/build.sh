#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT"

source "$ROOT/scripts/build-config.env"

allow_dirty=false
case "${1:-}" in
    '') ;;
    --allow-dirty) allow_dirty=true ;;
    -h|--help) echo "Usage: $0 [--allow-dirty]"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
esac
(( $# <= 1 )) || { echo 'Too many arguments' >&2; exit 2; }

mkdir -p ../cache "../dist/releases/$GAME_VERSION"
# The workspace and native build caches are shared across invocations.
exec 9>../cache/build.lock
flock -n 9 || { echo 'Another build is running' >&2; exit 1; }

commit="$(git rev-parse HEAD)"
short_commit="$(git rev-parse --short=12 HEAD)"
branch="$(git rev-parse --abbrev-ref HEAD)"
status="$(git status --porcelain=v1)"
dirty=false
if [[ -n "$status" ]]; then
    dirty=true
    if [[ "$allow_dirty" != true ]]; then
        echo 'Working tree is dirty. Commit changes or use --allow-dirty.' >&2
        exit 1
    fi
fi

for path in "../files/$INPUT_NAME" ../secrets/pgrazor-release.jks ../secrets/signing.env; do
    [[ -r "$path" ]] || { echo "Missing or unreadable: $path" >&2; exit 1; }
done
printf '%s  %s\n' "$INPUT_SHA" "../files/$INPUT_NAME" | sha256sum -c -

compose=(docker compose -f compose.yaml -f compose.signing.yaml)
"${compose[@]}" config --quiet
image_id="$(docker image inspect --format '{{.Id}}' pgrazor-builder:local)"
started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
stamp="$(date -u +%Y%m%d-%H%M%S)"
suffix="$short_commit"
[[ "$dirty" == false ]] || suffix+="-dirty"
parent="../dist/releases/$GAME_VERSION"
stage="$(mktemp -d "$parent/.pending-$stamp-$suffix-XXXXXXXX")"
stage_name="$(basename "$stage")"
final="$parent/${stage_name#.pending-}"
output="/output/releases/$GAME_VERSION/$stage_name"
trap 'rc=$?; if (( rc != 0 )); then printf "Build failed (exit %s). Logs/partial files: %s\n" "$rc" "$stage" >&2; fi' EXIT
printf '%s\n' "$status" > "$stage/git-status.txt"

"${compose[@]}" run --rm --no-deps -T --pull never \
    -e "GITHUB_SHA=$commit" builder bash -s -- \
    "$output" "$INPUT_NAME" "$INPUT_SHA" "$CERT_SHA" \
    "$GAME_VERSION" "$GAME_CODE" "$branch" "$dirty" "$image_id" "$started" \
    <<'CONTAINER' 2>&1 | tee "$stage/build.log"
set -Eeuo pipefail
umask 077
out="$1"; input="/input/$2"; input_sha="$3"; cert_sha="$4"
version="$5"; code="$6"; branch="$7"; dirty="$8"; image_id="$9"; started="${10}"
cd /workspace

for var in PGRAZOR_KEYSTORE PGRAZOR_KEY_ALIAS PGRAZOR_STORE_PASSWORD PGRAZOR_KEY_PASSWORD; do
    [[ -n "${!var:-}" ]] || { echo "Missing signing setting: $var" >&2; exit 1; }
done
[[ -r "$PGRAZOR_KEYSTORE" ]]
printf '%s  %s\n' "$input_sha" "$input" | sha256sum -c -
python -B tools/prepare_native.py
python -B pgrazor/art_build.py --check

python -B pgrazor/nonroot.py --apk "$input" --google-browser-login \
    2>&1 | tee "$out/nonroot.log"

# Resolve the run produced by this invocation, not the latest old build.
mapfile -t runs < <(sed -n 's/^Packaged: //p' "$out/nonroot.log")
[[ ${#runs[@]} -eq 1 ]] || { echo 'Expected exactly one packaging run' >&2; exit 1; }
run="${runs[0]}"
[[ "$run" =~ ^/workspace/pgrazor/build/nonroot/[0-9]{8}-[0-9]{6}-[0-9a-f]{8}$ ]]
[[ -f "$run/report.json" ]]
cp -- "$run/report.json" "$out/report.json"

python -B pgrazor/release_apk.py --run "$run" --output "$out"

apk="$out/PGRazor.apk"
apksigner verify --verbose --print-certs "$apk" | tee "$out/signature.txt"
actual="$(awk '/^Signer #[0-9]+ certificate SHA-256 digest:/ {print tolower($NF)}' "$out/signature.txt")"
[[ "$actual" == "$cert_sha" ]] || { echo 'Unexpected signing certificate' >&2; exit 1; }
zipalign -c -P 16 -v 4 "$apk" > "$out/zipalign.txt"
tail -1 "$out/zipalign.txt"
aapt dump badging "$apk" > "$out/badging.txt"
grep -F "package: name='com.nianticlabs.pokemongo' versionCode='$code' versionName='$version'" "$out/badging.txt"
(cd "$out" && sha256sum -c SHA256SUMS.txt)

python -B - "$out" "$version" "$code" "$input_sha" "$cert_sha" "$branch" "$dirty" "$image_id" "$started" <<'PY'
import hashlib
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

out, version, code, input_sha, cert, branch, dirty, image_id, started = sys.argv[1:]
out = Path(out)
path = out / 'build-info.json'
info = json.loads(path.read_text(encoding='utf-8'))
with (out / 'PGRazor.apk').open('rb') as apk:
    apk_sha = hashlib.file_digest(apk, 'sha256').hexdigest()
expected = {
    'game_version': version,
    'game_version_code': int(code),
    'architecture': 'arm64-v8a',
    'input_sha256': input_sha,
    'apk_sha256': apk_sha,
    'source_commit': os.environ['GITHUB_SHA'],
    'device_tested': False,
}
for key, value in expected.items():
    if info.get(key) != value:
        raise SystemExit(f'Unexpected build metadata: {key}')
info.update(
    source_branch=branch,
    source_dirty=(dirty == 'true'),
    signing_certificate_sha256=cert,
    builder_image_id=image_id,
    build_started_utc=started,
    build_finished_utc=datetime.now(timezone.utc).isoformat(),
)
path.write_text(json.dumps(info, indent=2) + '\n', encoding='utf-8')
PY
CONTAINER

# Catch source changes during a build (not a substitute for an immutable checkout).
[[ "$(git rev-parse HEAD)" == "$commit" && "$(git status --porcelain=v1)" == "$status" ]] || {
    echo 'Git state changed during build; release not promoted' >&2
    exit 1
}
mv -T -- "$stage" "$final"
trap - EXIT
printf '\nBuild verified: %s/PGRazor.apk\n' "$(realpath "$final")"
