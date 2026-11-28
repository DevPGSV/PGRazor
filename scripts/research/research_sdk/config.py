"""Configuration for authenticated local PGRazor clients."""
import getpass
import os
import re
import stat
import sys


def resolve_config(realm, *, base=None, token=None, token_file=None, interactive=False):
    variables = {"production": "PGRAZOR_HTTP_TOKEN", "research": "PGRAZOR_RESEARCH_TOKEN"}
    variable = variables[realm]
    if base is None:
        base = os.environ.get("PGRAZOR_BASE")
    if not base:
        raise ValueError("Set PGRAZOR_BASE or supply --base")
    if token_file is not None:
        if token is not None:
            raise ValueError("Supply only one explicit credential source")
        try:
            fd = os.open(token_file, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            with os.fdopen(fd, "rb") as stream:
                info = os.fstat(stream.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
                    raise ValueError()
                raw = stream.read(129)
                if len(raw) > 128:
                    raise ValueError()
                token = raw.decode("ascii").strip()
                if not re.fullmatch(r"[A-Za-z0-9_-]{43}", token):
                    raise ValueError()
        except (OSError, ValueError):
            raise ValueError("Invalid protected credential file") from None
    elif token is None:
        token = os.environ.get(variable)
        if token is None and interactive and sys.stdin.isatty():
            token = getpass.getpass(f"{realm.title()} bearer token: ")
    if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", token):
        raise ValueError(f"Missing or invalid {variable} or explicit credential")
    return base, token
