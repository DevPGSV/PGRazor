#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"
# Give the image builder only its Dockerfile. Input and secrets are absent from preparation.
docker build --platform linux/amd64 --tag pgrazor-research:cpp2il21-jadx157 - < docker/research.Dockerfile
docker run --rm --network none --read-only --tmpfs /tmp:rw,size=64m --cap-drop ALL \
    --security-opt no-new-privileges --user "$(id -u):$(id -g)" \
    pgrazor-research:cpp2il21-jadx157 /opt/research/jadx/bin/jadx --version
