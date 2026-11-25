#!/usr/bin/env python3
"""Submit bounded live metadata queries and compare results with an offline catalog."""
import argparse
from dataclasses import asdict
import getpass
import gzip
import json
import re
import sys
from pathlib import Path

from research_sdk import Client, DiscoveryQuery, ResearchError


def identity(row):
    return row.get("assembly", row.get("image")), row.get("namespace"), row.get("name")

def static_name(ref):
    if ref and ref.get("name"):
        return ".".join(filter(None, (ref.get("namespace"), ref["name"])))
    return None

def compare(result, catalog, kind):
    """Compare a bounded live page against a streaming pass of the full static catalog."""
    declaration = result.get("declaration")
    live_rows = [declaration] if declaration else [
        row for row in result.get("items", []) if isinstance(row, dict)
    ]
    targets = {identity(row) for row in live_rows}
    found = {}
    with gzip.open(catalog, "rt", encoding="utf-8") as data:
        for line in data:
            row = json.loads(line)
            key = identity(row)
            if key in targets:
                found.setdefault(key, []).append(row)
    report = {"types_confirmed": [], "not_in_static_catalog": [],
              "ambiguous_static_type": [], "resolved_runtime_types": [], "confirmed_members": [],
              "different_runtime_types": []}
    for row in live_rows:
        key = identity(row)
        candidates = found.get(key, [])
        display = ".".join(filter(None, key[1:]))
        if not candidates:
            report["not_in_static_catalog"].append(display)
            continue
        if len(candidates) != 1:
            report["ambiguous_static_type"].append(display)
            continue
        report["types_confirmed"].append(display)
        if not declaration or kind not in ("fields", "properties", "methods"):
            continue
        for member in result.get("items", []):
            matches = [item for item in candidates[0][kind] if item["name"] == member["name"]]
            if kind == "methods":
                matches = [item for item in matches if len(item["parameters"]) == len(member["parameters"])]
            # Do not correlate ambiguous overloads by position.
            if len(matches) != 1:
                continue
            original = matches[0]
            value = member.get("type", member.get("returns"))
            static = static_name(original.get("type", original.get("return_type")))
            pairs = [("return" if kind == "methods" else "type", static, value)]
            if kind == "methods":
                pairs += [(f"parameter_{i}", static_name(p.get("type")), runtime)
                          for i, (p, runtime) in enumerate(zip(original["parameters"], member["parameters"]))]
            for position, expected, observed in pairs:
                if observed is None:
                    continue
                entry = {"member": member["name"], "position": position, "runtime_type": observed}
                if expected is None:
                    report["resolved_runtime_types"].append(entry)
                elif expected == observed:
                    report["confirmed_members"].append(entry)
                else:
                    entry["static_type"] = expected
                    report["different_runtime_types"].append(entry)
    return report

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, help="http://IPv4:port")
    parser.add_argument("--catalog", type=Path, help="Full offline types.jsonl.gz")
    parser.add_argument("operation", choices=("support", "assemblies", "search", "type", "status", "cancel", "watch"))
    parser.add_argument("--assembly")
    parser.add_argument("--ns")
    parser.add_argument("--name")
    parser.add_argument("--kind", choices=("fields", "properties", "methods", "interfaces"))
    parser.add_argument("--filter")
    parser.add_argument("--offset", type=int)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--job")
    args = parser.parse_args()
    token = getpass.getpass("Research bearer token: ")
    try:
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", token):
            raise ValueError("Invalid bearer")
        client = Client(args.base, token)
        if args.operation == "watch":
            if not args.job:
                raise ResearchError("Job required")
            for update in client.job_updates([args.job]):
                print(json.dumps(asdict(update)).replace(token, "[redacted]"), flush=True)
            return 0
        if args.operation in ("status", "cancel"):
            if not args.job:
                raise ValueError("Job required")
            job = asdict(getattr(client, "job" if args.operation == "status" else "cancel")(args.job))
        else:
            keys = ("assembly", "ns", "name", "kind", "filter", "offset", "limit")
            job = asdict(client.observe(DiscoveryQuery(args.operation, **{key: getattr(args, key) for key in keys})))
        if args.catalog and job.get("result") and job["result"].get("availability") == "observed":
            job["comparison"] = compare(job["result"], args.catalog, args.kind or "methods")
        print(json.dumps(job).replace(token, "[redacted]"))
        return 0 if job.get("state") not in ("failed", "cancelled") else 1
    except ResearchError as error:
        print(json.dumps({"http_status": error.status, "error": str(error)}))
    except (OSError, ValueError, KeyError, TypeError):
        print(json.dumps({"error": "Invalid input, response, catalog, or unavailable endpoint"}))
    return 1

if __name__ == "__main__":
    sys.exit(main())
