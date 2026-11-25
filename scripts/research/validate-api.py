#!/usr/bin/env python3
"""Compare authenticated Research API declarations with a private local catalog."""
import argparse
import getpass
import ipaddress
import json
import runpy
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

MAX_BYTES = 8192

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None

def run(base, catalog, token):
    export = runpy.run_path(str(Path(__file__).with_name("export-api-declarations.py")))
    expected = export["declarations"](catalog)
    client = build_opener(ProxyHandler({}), NoRedirect())

    def get(route):
        request = Request(base + "/research/v1/" + route, headers={"Authorization": "Bearer " + token})
        with client.open(request, timeout=5) as response:
            body = response.read(MAX_BYTES + 1)
            if len(body) > MAX_BYTES:
                raise ValueError("Response exceeded the limit")
            return json.loads(body)

    get("status")  # Verify authentication; omit the generation from the report.
    caps = get("capabilities")
    assemblies = get("assemblies?limit=12")
    runtime = get("runtime")
    diagnostics = get("diagnostics")
    contracts = get("contracts")
    listed = []
    offset = 0
    for _ in range(12):
        page = get(f"types?offset={offset}&limit=3")
        listed.extend(item["id"] for item in page["items"])
        next_offset = page["next_offset"]
        if next_offset is None:
            break
        if next_offset <= offset:
            raise ValueError("Invalid pagination")
        offset = next_offset
    expected_ids = [row["id"] for row in expected]
    comparisons = {row["id"]: get("type?id=" + row["id"]) == row for row in expected}
    evidence = get("validate?contract=trainer.stats.v1")
    trainer = next(row for row in expected if row["id"] == "PlayerStatsProto")
    static_fields = {field["name"]: field["type"] for field in trainer["fields"]}
    compatibility = {}
    for name, runtime_type in evidence["checked_fields"].items():
        compatibility[name] = (
            "missing_static_declaration" if name not in static_fields
            else "static_type_unresolved" if static_fields[name] is None
            else "matches" if static_fields[name] == runtime_type
            else "type_mismatch"
        )
    matched = (listed == expected_ids and all(comparisons.values())
               and not any(result in ("missing_static_declaration", "type_mismatch")
                           for result in compatibility.values()))
    return {
        "inventory_matches": listed == expected_ids, "static_matches": comparisons,
        "assemblies": assemblies["items"], "operations": caps["operations"],
        "runtime": runtime, "diagnostics": diagnostics, "registered": contracts["contracts"],
        "trainer_contract": evidence, "trainer_field_conformance": compatibility, "ok": matched,
    }

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, help="http://IPv4:port")
    parser.add_argument("--catalog", required=True, type=Path)
    args = parser.parse_args()
    try:
        base = urlsplit(args.base)
        if (base.scheme != "http" or not base.hostname or base.username is not None
                or base.password is not None or base.query or base.fragment
                or base.path not in ("", "/") or not base.port):
            raise ValueError("Expected http://IPv4:port")
        ipaddress.IPv4Address(base.hostname)
    except ValueError:
        parser.error("--base must be http://IPv4:port")
    token = getpass.getpass("Research bearer token: ")
    if len(token) != 43 or not token.isascii() or any(not (c.isalnum() or c in "-_") for c in token):
        parser.error("Invalid token format")
    try:
        result = run(f"http://{base.hostname}:{base.port}", args.catalog, token)
        print(json.dumps(result).replace(token, "[redacted]"))
        return 0 if result["ok"] else 1
    except HTTPError as failure:
        print(json.dumps({"ok": False, "http_status": failure.code,
                          "error": "Endpoint rejected the request"}))
    except (OSError, URLError, ValueError, TypeError, KeyError):
        print(json.dumps({"ok": False, "error": "Invalid or unavailable API or local catalog"}))
    return 1

if __name__ == "__main__":
    sys.exit(main())
