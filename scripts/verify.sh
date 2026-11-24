#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
CALLER="$PWD"
CERT_SHA=21d8ca27b314ccf2de4cdfab5f6365e75c2ef740ae445cbf9a26b28575414946

if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
    printf 'Usage: %s [release-directory | PGRazor.apk]\nWithout an argument, verify the newest completed automated release by directory name.\n' "$0"
    exit 0
fi
(( $# <= 1 )) || { echo 'Too many arguments' >&2; exit 2; }
dist="$(realpath -e "$ROOT/../dist")"
if (( $# == 1 )); then
    target="$1"
    [[ "$target" == /* ]] || target="$CALLER/$target"
    if [[ -f "$target" && "$(basename "$target")" == PGRazor.apk ]]; then
        target="$(dirname "$target")"
    fi
    release="$(realpath -e "$target")"
else
    export LC_ALL=C
    shopt -s nullglob
    releases=("$dist"/releases/*/[0-9]*/PGRazor.apk)
    (( ${#releases[@]} > 0 )) || { echo 'No completed automated release found' >&2; exit 1; }
    # Sort by run directory (UTC timestamp), rather than game-version directory.
    latest=""
    for apk in "${releases[@]}"; do
        candidate="$(dirname "$apk")"
        if [[ -z "$latest" || "$(basename "$candidate")" > "$(basename "$latest")" ]]; then
            latest="$candidate"
        fi
    done
    release="$(realpath -e "$latest")"
fi
[[ "$release" == "$dist/"* && -d "$release" ]] || { echo 'Release must be inside dist/' >&2; exit 1; }
[[ "$(basename "$release")" != .pending-* ]] || { echo 'Release is pending' >&2; exit 1; }
for file in PGRazor.apk SHA256SUMS.txt build-info.json; do
    [[ -f "$release/$file" && ! -L "$release/$file" ]] || { echo "Missing or unsafe file: $file" >&2; exit 1; }
done

cd "$ROOT"
# Read-only release mount; no signing configuration or network.
docker compose -f compose.yaml -f - run --rm --no-deps -T --pull never \
    -v "$release:/verify-release:ro" builder bash -Eeuo pipefail -c '
        cd /verify-release
        signature="$(apksigner verify --verbose --print-certs PGRazor.apk)"
        printf "%s\n" "$signature"
        actual="$(printf "%s\n" "$signature" | awk '\''/^Signer #[0-9]+ certificate SHA-256 digest:/ {print tolower($NF)}'\'')"
        [[ "$actual" == "$1" ]] || { echo "Unexpected signing certificate" >&2; exit 1; }
        zipalign -c -P 16 4 PGRazor.apk
        python -B - "$1" <<'\''PY'\''
import hashlib
import json
import subprocess
import sys
from pathlib import Path

info = json.loads(Path("build-info.json").read_text(encoding="utf-8"))
with Path("PGRazor.apk").open("rb") as apk:
    digest = hashlib.file_digest(apk, "sha256").hexdigest()
if Path("SHA256SUMS.txt").read_text(encoding="utf-8").strip() != f"{digest}  PGRazor.apk":
    raise SystemExit("SHA256SUMS.txt mismatch")
if info.get("apk_sha256") != digest:
    raise SystemExit("APK hash does not match build-info.json")
if "signing_certificate_sha256" in info and info["signing_certificate_sha256"] != sys.argv[1]:
    raise SystemExit("Certificate does not match build-info.json")
version, code = info["game_version"], info["game_version_code"]
badging = subprocess.check_output(["aapt", "dump", "badging", "PGRazor.apk"], text=True)
expected = f"package: name=\x27com.nianticlabs.pokemongo\x27 versionCode=\x27{code}\x27 versionName=\x27{version}\x27"
if not any(line.startswith(expected + " ") or line == expected for line in badging.splitlines()):
    raise SystemExit("APK package or version mismatch")
print(expected)
print(f"SHA-256: {digest}")
print("Source commit:", info.get("source_commit"))
print("Device tested:", info.get("device_tested"))
PY
    ' verify "$CERT_SHA" <<'COMPOSE'
services:
  builder:
    network_mode: none
    volumes:
      - ../dist:/output:ro
COMPOSE
printf '\nAPK verified: %s/PGRazor.apk\n' "$release"
