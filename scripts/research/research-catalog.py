#!/usr/bin/env python3
"""Import a private reference catalog and record bounded live structural observations."""
import argparse
import getpass
import json
from pathlib import Path
import sqlite3

from research_sdk import Client, DiscoveryQuery, Job, ResearchError
from research_sdk.catalog import Catalog


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--version", required=True, help="Game version shared by reference and APK")
    actions = parser.add_subparsers(dest="action", required=True)
    load = actions.add_parser("import-static")
    load.add_argument("catalog", type=Path)
    observation = actions.add_parser("import-observation")
    observation.add_argument("document", type=Path, help="JSON with a typed query and completed live job")
    search = actions.add_parser("search")
    search.add_argument("text")
    search.add_argument("--limit", type=int, default=30)
    report = actions.add_parser("summary")
    report.add_argument("--limit", type=int, default=100)
    compare = actions.add_parser("compare")
    compare.add_argument("observation", type=int)
    live = actions.add_parser("observe")
    live.add_argument("--base", required=True)
    live.add_argument("op", choices=("support", "assemblies", "search", "type"))
    for key in ("assembly", "ns", "name", "filter"):
        live.add_argument("--" + key)
    live.add_argument("--kind", choices=("fields", "properties", "methods", "interfaces"))
    live.add_argument("--offset", type=int)
    live.add_argument("--limit", type=int)
    live.add_argument("--pages", type=int, default=5)
    args = parser.parse_args()

    try:
        with Catalog(args.db) as catalog:
            if args.action == "import-static":
                result = {"imported_types": catalog.import_static(args.catalog, version=args.version)}
            elif args.action == "import-observation":
                with args.document.open("rb") as stream:
                    payload = stream.read(16_385)
                if len(payload) > 16_384:
                    raise ResearchError("Observation document exceeds the byte limit")
                record = json.loads(payload)
                result = {"observation_id": catalog.record(DiscoveryQuery(**record["query"]),
                                                             Job.parse(record["job"]), version=args.version)}
            elif args.action == "search":
                result = catalog.search(args.text, version=args.version, limit=args.limit)
            elif args.action == "summary":
                result = catalog.summary(version=args.version, limit=args.limit)
            elif args.action == "compare":
                result = catalog.compare(args.observation)
            else:
                client = Client(args.base, getpass.getpass("Research bearer token: "))
                query = DiscoveryQuery(**{key: getattr(args, key) for key in DiscoveryQuery.__dataclass_fields__})
                result = []
                for sent, job in client.pages(query, max_pages=args.pages):
                    entry = {"state": job.state, "availability": (job.result or {}).get("availability"),
                             "next_offset": (job.result or {}).get("next_offset")}
                    if job.state == "completed" and job.result:
                        entry["observation_id"] = catalog.record(sent, job, version=args.version)
                    result.append(entry)
            print(json.dumps(result))
            return 0
    except ResearchError as error:
        print(json.dumps({"error": str(error), "http_status": error.status}))
    except (OSError, sqlite3.Error, ValueError, KeyError, TypeError):
        print(json.dumps({"error": "Catalog or observation could not be processed"}))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
