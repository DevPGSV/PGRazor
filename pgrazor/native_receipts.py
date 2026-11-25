"""Check native core receipts against ZIP assets without accessing the build cache."""
import hashlib
import re

SONAME = "libpgrazor-core.so"
ASSET = re.compile(r"assets/pgrazor/([^/]+)/libpgrazor-core\.so")
MARKER = b"dev/pgrazor/ResearchDiscoveryNative"


def verify_cores(archive, report, build_info=None):
    assets = [entry for entry in archive.namelist() if entry.endswith("/" + SONAME) or entry == SONAME]
    pilot = report.get("native_pilot")
    if pilot is None and not assets and not report.get("features", {}).get("research_discovery"):
        return
    if not isinstance(pilot, dict) or not isinstance(pilot.get("libraries"), dict):
        raise ValueError("Native core receipt is missing")
    libraries = pilot["libraries"]
    abis = report.get("runtime_abis")
    if (not isinstance(abis, list) or not abis or any(abi not in ("arm64-v8a", "x86_64") for abi in abis)
            or len(abis) != len(libraries) or set(abis) != set(libraries)):
        raise ValueError("Native core receipt ABIs disagree with the package report")
    if build_info is not None and abis != build_info.get("hook_runtime_architectures"):
        raise ValueError("Native core receipt ABIs disagree with build-info")
    expected = {f"assets/pgrazor/{abi}/{SONAME}" for abi in abis}
    if len(assets) != len(expected) or set(assets) != expected:
        raise ValueError("Native core assets disagree with receipt ABI set")

    discovery = report.get("features", {}).get("research_discovery")
    fingerprints = set()
    legacy = build_info is not None and "report_sha256" not in build_info
    for asset in sorted(expected):
        abi = ASSET.fullmatch(asset)[1]
        receipt = libraries[abi]
        if not isinstance(receipt, dict) or receipt.get("abi") != abi or receipt.get("soname") != SONAME:
            raise ValueError(f"Native core receipt identity mismatch for {abi}")
        payload = archive.read(asset)
        actual = hashlib.sha256(payload).hexdigest()
        if actual != receipt.get("stripped_sha256"):
            raise ValueError(f"Native core SHA-256 mismatch for {abi}: receipt {receipt.get('stripped_sha256')}, APK {actual}")
        receipt_variant = receipt.get("research_discovery", discovery if legacy else None)
        if discovery is not None and (not isinstance(discovery, bool)
                or receipt_variant is not discovery or (MARKER in payload) != discovery):
            raise ValueError(f"Native core variant mismatch for {abi}")
        fingerprint = receipt.get("source_fingerprint")
        if not isinstance(fingerprint, str) or not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
            raise ValueError(f"Native core source fingerprint invalid for {abi}")
        fingerprints.add(fingerprint)
    if len(fingerprints) != 1:
        raise ValueError("Native core source fingerprints disagree across ABIs")
    return next(iter(fingerprints))
