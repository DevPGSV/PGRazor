#!/usr/bin/env python3
"""Submit bounded live metadata queries and compare results with an offline catalog."""
import argparse
import getpass
import gzip
import ipaddress
import json
import re
import sys
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

ARGUMENT = re.compile(r"[A-Za-z0-9_.`-]{1,96}", re.ASCII)
PREFIX = "/research/v1/live/"

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None

class Client:
    def __init__(self, base, token):
        parsed = urlsplit(base)
        if (parsed.scheme != "http" or not parsed.hostname or parsed.username is not None
                or parsed.password is not None or parsed.query or parsed.fragment
                or parsed.path not in ("", "/") or not parsed.port):
            raise ValueError("Invalid endpoint")
        ipaddress.IPv4Address(parsed.hostname)
        self.base = f"http://{parsed.hostname}:{parsed.port}"
        self.token = token
        self.opener = build_opener(ProxyHandler({}), NoRedirect())

    def request(self, endpoint, method, **arguments):
        params = [(key, str(value)) for key, value in arguments.items() if value is not None]
        if any(not ARGUMENT.fullmatch(key) or not ARGUMENT.fullmatch(value) for key, value in params):
            raise ValueError("Invalid metadata parameter")
        query = "&".join(f"{key}={value}" for key, value in params)
        path = self.base + PREFIX + endpoint + ("?" + query if query else "")
        with self.opener.open(Request(path, method=method, headers={
            "Authorization": "Bearer " + self.token,
        }), timeout=4) as reply:
            payload = reply.read(9217)
        if len(payload) > 9216:
            raise ValueError("Response limit exceeded")
        return json.loads(payload)

    def submit(self, operation, **arguments):
        return self.request("discover", "POST", op=operation, **arguments)

    def status(self, job_id):
        return self.request("job", "GET", id=job_id)

    def cancel(self, job_id):
        return self.request("cancel", "POST", id=job_id)

    def observe(self, operation, **arguments):
        job = self.submit(operation, **arguments)
        job_id = job["id"]
        deadline = time.monotonic() + 6
        try:
            while job["state"] in ("queued", "running") and time.monotonic() < deadline:
                time.sleep(0.1)
                job = self.status(job_id)
            if job["state"] in ("queued", "running"):
                self.cancel(job_id)
                raise TimeoutError("Job deadline")
            return job
        except KeyboardInterrupt:
            try:
                self.cancel(job_id)
            except (OSError, ValueError):
                pass
            raise

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
    parser.add_argument("operation", choices=("support", "assemblies", "search", "type", "status", "cancel"))
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
        if args.operation in ("status", "cancel"):
            if not args.job:
                raise ValueError("Job required")
            job = getattr(client, args.operation)(args.job)
        else:
            keys = ("assembly", "ns", "name", "kind", "filter", "offset", "limit")
            job = client.observe(args.operation, **{key: getattr(args, key) for key in keys})
        if args.catalog and job.get("result") and job["result"].get("availability") == "observed":
            job["comparison"] = compare(job["result"], args.catalog, args.kind or "methods")
        print(json.dumps(job).replace(token, "[redacted]"))
        return 0 if job.get("state") not in ("failed", "cancelled") else 1
    except HTTPError as error:
        print(json.dumps({"http_status": error.code, "error": "Research request rejected"}))
    except (OSError, ValueError, KeyError, TypeError):
        print(json.dumps({"error": "Invalid input, response, catalog, or unavailable endpoint"}))
    return 1

if __name__ == "__main__":
    sys.exit(main())
