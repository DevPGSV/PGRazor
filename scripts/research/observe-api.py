#!/usr/bin/env python3
"""Run a registered observation without storing its private result."""
from dataclasses import asdict
import argparse
import json
from pathlib import Path

from research_sdk import Client, ResearchError
from research_sdk.inventory import normalize_inventory_observation


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base")
    parser.add_argument("--token-file", type=Path)
    parser.add_argument("--contract")
    parser.add_argument("--fields", nargs="*", default=[])
    parser.add_argument("--limit", type=int, default=1)
    parser.add_argument("--offset", type=int, default=0)
    parser.add_argument("--normalize-inventory", metavar="GAME_VERSION",
                        help="add verified storage fields to inventory.bag.v1")
    args = parser.parse_args()
    if args.normalize_inventory and args.contract != "inventory.bag.v1":
        parser.error("--normalize-inventory requires --contract inventory.bag.v1")
    try:
        client = Client.configured(base=args.base, token_file=args.token_file, interactive=True)
    except (OSError, ValueError) as error:
        print(json.dumps({"error": str(error)}))
        return 1
    except ResearchError as error:
        print(json.dumps({"error": str(error), "http_status": error.status}))
        return 1
    try:
        if args.contract:
            result = asdict(client.wait(client.submit_observation(args.contract, fields=tuple(args.fields),
                                                                   limit=args.limit, offset=args.offset)))
            if args.normalize_inventory and result["state"] == "completed" and result["result"] is not None:
                result["result"] = normalize_inventory_observation(result["result"], game_version=args.normalize_inventory)
        else:
            result = client.observation_contracts()
        print(json.dumps(result))
        return 0
    except ResearchError as error:
        print(json.dumps({"error": str(error), "http_status": error.status}))
    except (OSError, UnicodeError, ValueError):
        print(json.dumps({"error": "Invalid observation or normalization options"}))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
