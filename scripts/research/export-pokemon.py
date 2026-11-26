#!/usr/bin/env python3
"""Export a private Pokémon sample or complete non-atomic collection."""
import argparse
import getpass
import json
import os
from pathlib import Path
import stat

from research_sdk import Client, ResearchError
from research_sdk.pokemon_export import complete_snapshot, partial_sample, save_export


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("--token-file", type=Path)
    scope = parser.add_mutually_exclusive_group(required=True)
    scope.add_argument("--partial", action="store_true", help="export a bounded sample")
    scope.add_argument("--complete", action="store_true", help="request an entire non-atomic snapshot")
    parser.add_argument("--limit", type=int, default=10, help="partial sample size or complete snapshot page size")
    parser.add_argument("--selection", choices=("all", "pokemon", "eggs"), default="all")
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--format", choices=("json", "csv"), required=True)
    parser.add_argument("--iv-percent", action="store_true")
    args = parser.parse_args()
    if args.partial and args.selection != "all":
        parser.error("--selection requires --complete")
    try:
        if args.token_file:
            with args.token_file.open("rb") as stream:
                info = os.fstat(stream.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
                    raise ResearchError("Credential file must be owner-only")
                token = stream.read(128).decode("ascii").strip()
        else:
            token = getpass.getpass("Research bearer token: ")
        client = Client(args.base, token)
        result = (complete_snapshot(client, page_size=args.limit, selection=args.selection, iv_percent=args.iv_percent)
                  if args.complete else partial_sample(client, limit=args.limit, iv_percent=args.iv_percent))
        path = save_export(result, args.directory, args.name, args.format)
        print(json.dumps({"file": str(path), "coverage": result["coverage"], "rows": result["exported_rows"]}))
        return 0
    except ResearchError as error:
        print(json.dumps({"error": str(error), "http_status": error.status}))
    except (OSError, UnicodeError, ValueError):
        print(json.dumps({"error": "Could not read credential or write export"}))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
