# Linux build environment (Docker)

The Linux workflow builds the non-root APK and the standalone preview using
Docker. The host does not need Java, Python, Node.js or Android SDK/NDK.
The tested host is Arch Linux x86_64; the builder is Ubuntu 24.04 amd64.

## Prerequisites and local layout

Install Git, Docker with Compose v2-compatible CLI, and standard Linux tools
(Bash, coreutils and util-linux/flock). ADB is only needed for device tests.

Keep the original APKM, signing credentials, caches and output outside the
Git checkout, using the following layout (directory names are examples):

```text
PGRazor/
├── PGRazor-DevPGSV/       # this Git checkout
├── files/                 # original APKM
├── secrets/               # mode 0700, never commit
│   ├── pgrazor-release.jks
│   └── signing.env
├── cache/                 # shared dependencies and build lock
└── dist/                  # APKs and logs
```

Create the sibling directories with `mkdir -p ../files ../secrets ../cache ../dist`
from the repository root, and set `chmod 700 ../secrets`.
The original APKM filename, required SHA-256, game version and expected signing
certificate fingerprint are defined in `scripts/build-config.env`. The APKM is
not downloaded by the scripts. Obtain it lawfully and verify its hash.

The signing key must be a persistent key that you control. For a different
key, update the public certificate fingerprint in `scripts/build-config.env`.
Keep the key and its password securely backed up; changing the signing identity
can prevent Android from accepting updates to an existing installation.

The external `../secrets/signing.env` must contain these four settings (replace
the placeholder passwords, and do not add shell quoting around values):

```dotenv
PGRAZOR_KEYSTORE=/secrets/pgrazor-release.jks
PGRAZOR_KEY_ALIAS=pgrazor
PGRAZOR_STORE_PASSWORD=YOUR_STORE_PASSWORD
PGRAZOR_KEY_PASSWORD=YOUR_KEY_PASSWORD
```

Restrict access with `chmod 600 ../secrets/pgrazor-release.jks ../secrets/signing.env`.
The signing Compose override mounts the JKS read-only, uses raw environment-file
format, and disables networking. Do not print resolved signing Compose config or
share container inspection output: environment variables can contain passwords.
Only run trusted source code and dependencies in the signing container.

## Commands

Run from the checkout, or invoke each script by its path from elsewhere.

```bash
./scripts/prepare.sh
./scripts/check.sh
./scripts/build.sh
./scripts/verify.sh
```

- `prepare.sh`: builds the image, prepares native and Java dependencies, checks
  prerequisites and creates `.env` with host UID/GID if missing. Needs network;
  does not mount the signing key. Netty JARs for the preview and compiled non-root build are verified against
  pinned SHA-256 values and cached at `../cache/artifacts/http-deps/`.
- `check.sh`: builds the standalone preview and runs upstream checks offline,
  without the release key. The preview build runs a JVM loopback HTTP transport test.
  Two Windows-only tests are expected to be skipped
  for the currently tested source version.
- `build.sh`: requires a clean Git working tree unless `--allow-dirty` is
  specified. Verifies input checksum, builds offline with the signing key,
  verifies the APK and records provenance. Does not run the full test suite.
- `verify.sh`: verifies the latest completed automated release by directory name,
  or accepts a specific release directory or its `PGRazor.apk` path. Does not
  require the signing key or a network connection.

For iterative development, `./scripts/build.sh --allow-dirty` permits uncommitted
changes. The recorded commit then identifies only the base revision, not the
exact modified source. Avoid editing the checkout during any build. The Git-state
check is not equivalent to an immutable snapshot of all source files.

## Artifacts

Preparation and check logs are saved in `../dist/logs/`. Automated releases
are saved in `../dist/releases/<game-version>/<UTC-timestamp>-<commit>-<suffix>/`.
Each contains `PGRazor.apk`, checksum, build metadata, certificate verification,
alignment report, package metadata and build logs. A `.pending-*` directory
indicates an incomplete or failed run and is not selected as a completed release.
The scripts do not automatically install or publish the resulting APK.

To independently verify the first manual release, if present:

```bash
./scripts/verify.sh ../dist/release/
```

## Manual intermediate APK cleanup (Linux)

`./scripts/clean.sh` previews the pruning plan; `./scripts/clean.sh --apply`
actually deletes eligible direct APK replicas in `pgrazor/build/nonroot/`.
It uses the shared build lock and a container without network or signing secrets.
Do not run manual upstream build commands concurrently with cleanup.

Create `../cache/nonroot-retention.json` with schema 1 and a nonempty
`pinnedRuns` array. Each entry has a `run` (the exact existing non-root run
name, not a path) and a nonempty `reason`. Keep important local builds pinned
until replacement builds pass device tests. These pins are local to the Linux
workflow and do not replace the upstream Windows `build-retention.json`.

The default policy keeps the latest three checksum-verified complete runs per
package/runtime ABI, all pinned runs, runs modified within 30 minutes, and
unrecognized, incomplete or invalid runs. Missing or damaged pins stop cleanup.
The optional flags `--keep-latest N` (3–100) and `--min-age-minutes N`
(30–525600) increase the retention limits. Review the dry-run output before
applying. JSON receipts are written to `pgrazor/build/cleanup/` even in dry-run.

Reports, logs, nested directories, ART outputs, native caches, original APKM,
keys and final releases are not deleted. Report paths must refer to the current
`/workspace/pgrazor/build/nonroot/` container layout. The script rejects unsafe
paths and checks file fingerprints again before deletion; the shared lock only
coordinates the supplied wrappers, not unrelated external processes.

Synthetic tests (no actual build files are touched):

```bash
docker compose run --rm --no-deps -T --pull never builder python -B scripts/test_clean.py -v
```

## Updating upstream

Review upstream changes to the supported game version, SDK/NDK requirements and
pinned dependencies before updating the local build config or Dockerfile.
Run preparation and all checks again, then build and verify a new release.
Do not replace the permanent key merely because the game version changes.

## Known limitations

- The Linux build and artifact checks have passed, but Android device execution
  and upgrade compatibility have not yet been validated.
- Windows compatibility of the Linux portability changes remains untested.
- The upstream non-root cleanup invokes PowerShell; its absence produces a
  non-fatal warning. Use the manual Linux cleanup for old non-root APK replicas;
  ART and other intermediate outputs can still accumulate in `pgrazor/build/`.
- The Docker image is not fully pinned by digest, and builds are not yet proven
  bit-for-bit reproducible. Record the image ID and source revision for diagnosis.
- The release certificate establishes the signing identity, not the safety of
  the app or its compatibility with the online service.
