#!/usr/bin/env python3
"""Compare authenticated Research API declarations with a private local catalog."""
import argparse
import json
import runpy
import sys
from pathlib import Path

from research_sdk import Client, ResearchError
from research_sdk.config import resolve_config


def run(base, catalog, token):
    export = runpy.run_path(str(Path(__file__).with_name("export-api-declarations.py")))
    expected = export["declarations"](catalog)
    client = Client(base, token)

    client.status()  # Verify authentication; omit the generation from the report.
    caps = client.capabilities()
    assemblies = client.static_assemblies()
    runtime = client.runtime()
    diagnostics = client.diagnostics()
    contracts = client.contracts()
    listed = []
    offset = 0
    for _ in range(12):
        page = client.offline_types(offset=offset, limit=3)
        listed.extend(item["id"] for item in page["items"])
        next_offset = page["next_offset"]
        if next_offset is None:
            break
        if next_offset <= offset:
            raise ValueError("Invalid pagination")
        offset = next_offset
    expected_ids = [row["id"] for row in expected]
    comparisons = {row["id"]: client.offline_type(row["id"]) == row for row in expected}
    evidence = client.validate_contract("trainer.stats.v1")
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
    parser.add_argument("--base", help="http://IPv4:port")
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--token-file", type=Path)
    args = parser.parse_args()
    try:
        base, token = resolve_config("research", base=args.base, token_file=args.token_file)
        result = run(base, args.catalog, token)
        print(json.dumps(result).replace(token, "[redacted]"))
        return 0 if result["ok"] else 1
    except ResearchError as failure:
        print(json.dumps({"ok": False, "http_status": failure.status, "error": str(failure)}))
    except (OSError, ValueError, TypeError, KeyError):
        print(json.dumps({"ok": False, "error": "Invalid or unavailable API or local catalog"}))
    return 1

if __name__ == "__main__":
    sys.exit(main())
