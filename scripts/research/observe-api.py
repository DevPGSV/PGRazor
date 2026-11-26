#!/usr/bin/env python3
"""Run a registered observation without storing its private result."""
from dataclasses import asdict
import argparse
import getpass
import json
import os
from pathlib import Path
import stat

from research_sdk import Client, ResearchError


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("--token-file", type=Path)
    parser.add_argument("--contract")
    parser.add_argument("--fields", nargs="*", default=[])
    parser.add_argument("--limit", type=int, default=1)
    parser.add_argument("--offset", type=int, default=0)
    args = parser.parse_args()
    try:
        if args.token_file:
            with args.token_file.open("rb") as stream:
                if stat.S_IMODE(os.fstat(stream.fileno()).st_mode) & 0o077:
                    raise ResearchError("Credential file must be owner-only")
                token = stream.read(128).decode("ascii").strip()
        else:
            token = getpass.getpass("Research bearer token: ")
        client = Client(args.base, token)
        if args.contract:
            result = asdict(client.wait(client.submit_observation(args.contract, fields=tuple(args.fields),
                                                                   limit=args.limit, offset=args.offset)))
        else:
            result = client.observation_contracts()
        print(json.dumps(result))
        return 0
    except ResearchError as error:
        print(json.dumps({"error": str(error), "http_status": error.status}))
    except (OSError, UnicodeError):
        print(json.dumps({"error": "Could not read credential file"}))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
