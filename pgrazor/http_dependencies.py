"""Prepare explicitly pinned preview-only HTTP libraries from Maven Central."""
import argparse
import hashlib
import json
from pathlib import Path
from tempfile import NamedTemporaryFile
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parent
VENDOR = ROOT / 'vendor/netty'
CACHE = ROOT.parent / '.artifacts/http-deps'
MAVEN = 'https://repo.maven.apache.org/maven2/io/netty'


def dependencies(download=False):
    lock = json.loads((VENDOR / 'artifacts.json').read_text(encoding='utf-8'))
    version = lock['version']
    paths = []
    for module, digest in lock['sha256'].items():
        filename = f'{module}-{version}.jar'
        path = CACHE / filename
        if not path.is_file() and download:
            CACHE.mkdir(parents=True, exist_ok=True)
            with urlopen(f'{MAVEN}/{module}/{version}/{filename}', timeout=30) as response:
                data = response.read(2 * 1024 * 1024 + 1)
            if hashlib.sha256(data).hexdigest() != digest:
                raise ValueError(f'Unexpected SHA-256 for downloaded {filename}')
            temporary_path = None
            try:
                with NamedTemporaryFile(dir=CACHE, delete=False) as temporary:
                    temporary_path = Path(temporary.name)
                    temporary.write(data)
                temporary_path.replace(path)
            finally:
                if temporary_path is not None:
                    temporary_path.unlink(missing_ok=True)
        if not path.is_file():
            raise FileNotFoundError(f'{filename} missing. Run scripts/prepare.sh or '
                                    'python -B pgrazor/http_dependencies.py --download')
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError(f'Unexpected SHA-256 for cached {filename}. Remove that cache file '
                             'and rerun dependency preparation.')
        paths.append(path)
    return paths


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download', action='store_true', help='Fetch missing pinned artifacts')
    args = parser.parse_args()
    jars = dependencies(download=args.download)
    print(f'Verified {len(jars)} HTTP preview dependencies ({sum(j.stat().st_size for j in jars)} bytes)')
