#!/usr/bin/env python3
"""Export a private Pokémon sample or complete non-atomic collection."""
import argparse
import json
from pathlib import Path

from research_sdk import Client, ResearchError
from research_sdk.pokemon_normalization import normalize_export
from research_sdk.pokemon_export import complete_snapshot, partial_sample, save_export


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base")
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
    parser.add_argument("--normalize", action="store_true", help="add versioned English and Max fields")
    parser.add_argument("--game-version", help="game version required for normalization")
    parser.add_argument("--move-catalog", type=Path, help="directory of pinned offline move JSON files")
    args = parser.parse_args()
    if args.normalize != bool(args.game_version) or args.move_catalog and not args.normalize:
        parser.error("--normalize requires --game-version; --move-catalog is optional with normalization")
    if args.partial and args.selection != "all":
        parser.error("--selection requires --complete")
    try:
        client = Client.configured(base=args.base, token_file=args.token_file, interactive=True)
        result = (complete_snapshot(client, page_size=args.limit, selection=args.selection, iv_percent=args.iv_percent)
                  if args.complete else partial_sample(client, limit=args.limit, iv_percent=args.iv_percent))
        if args.normalize:
            result = normalize_export(result, game_version=args.game_version, move_catalog=args.move_catalog)
        path = save_export(result, args.directory, args.name, args.format)
        summary = {"file": str(path), "coverage": result["coverage"], "rows": result["exported_rows"]}
        if args.normalize:
            summary["max_category_counts"] = result["max_category_counts"]
        print(json.dumps(summary))
        return 0
    except ResearchError as error:
        print(json.dumps({"error": str(error), "http_status": error.status}))
    except (OSError, UnicodeError, ValueError):
        print(json.dumps({"error": "Could not read credential or write export"}))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
