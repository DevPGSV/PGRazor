#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
CALLER="$PWD"
source "$ROOT/scripts/build-config.env"

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
for file in PGRazor.apk SHA256SUMS.txt build-info.json report.json; do
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
        PYTHONPATH=/workspace/pgrazor python -B - "$1" <<'\''PY'\''
import hashlib
import json
import re
import zipfile
import subprocess
from native_receipts import verify_cores
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
features = info.get("features")
report_path = Path("report.json")
report = json.loads(report_path.read_text(encoding="utf-8"))
if "report_sha256" in info and hashlib.sha256(report_path.read_bytes()).hexdigest() != info["report_sha256"]:
    raise SystemExit("Package report SHA-256 does not match build-info")
with zipfile.ZipFile("PGRazor.apk") as archive:
    try:
        fingerprint = verify_cores(archive, report, info)
    except ValueError as error:
        raise SystemExit(str(error)) from None
if "native_source_fingerprint" in info and info["native_source_fingerprint"] != fingerprint:
    raise SystemExit("Native source fingerprint does not match build-info")
if fingerprint:
    print("Verified native core hashes and source fingerprint:", fingerprint)
if features:
    if report.get("features") != features:
        raise SystemExit("APK feature receipts disagree")
    if features.get("production_api") is not True or features.get("websocket") is not True:
        raise SystemExit("Missing production API feature")
    with zipfile.ZipFile("PGRazor.apk") as archive:
        dexes = [archive.read(name) for name in archive.namelist() if re.fullmatch(r"classes[0-9]*\.dex", name)]
    descriptors = ("ResearchApi", "ResearchApiSession", "ResearchApiSettings")
    if "research_discovery" in features:
        descriptors += ("ResearchTokenStore", "ResearchMetadata", "ResearchDeclarations",
                        "ResearchDiscovery", "ResearchDiscoveryNative")
    if not isinstance(features.get("research_api"), bool) or any(
        any(("Ldev/pgrazor/" + name + ";").encode() in dex for dex in dexes) != features["research_api"]
        for name in descriptors
    ):
        raise SystemExit("APK Research API classes disagree with feature receipt")
    print("Features:", json.dumps(features, sort_keys=True))
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
