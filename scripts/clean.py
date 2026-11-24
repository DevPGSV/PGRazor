"""Conservative non-root APK pruning for the Linux Docker workflow.

Only direct generated APK replicas may be removed. Local pins are independent
of the upstream Windows retention file. No ART/native/cache cleanup here.
"""
import argparse
import hashlib
import json
import os
import re
import stat
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

RUN = re.compile(r"[0-9]{8}-[0-9]{6}-[0-9a-f]{8}")
APK = re.compile(r"(?:base(?:-aligned|-unsigned)?|split-[0-9]+(?:-aligned|-unsigned)?|input-[0-9]+)\.apk")
SIGNED = re.compile(r"(?:base|split-[0-9]+)\.apk")
SHA = re.compile(r"[0-9a-fA-F]{64}")


def plain(path, directory=False):
    """Reject symlinks in every component; require regular files/directories."""
    path = Path(path)
    if not path.is_absolute() or '..' in path.parts:
        raise ValueError(f"Unsafe path: {path}")
    for parent in reversed(path.parents):
        if not stat.S_ISDIR(parent.lstat().st_mode):
            raise ValueError(f"Unsafe directory: {parent}")
    st = path.lstat()
    if directory:
        if not stat.S_ISDIR(st.st_mode):
            raise ValueError(f"Not a plain directory: {path}")
    elif not stat.S_ISREG(st.st_mode) or st.st_nlink != 1:
        raise ValueError(f"Not a plain single-link file: {path}")
    return st


def fingerprint(path):
    before = plain(path)
    with path.open('rb') as stream:
        opened = os.fstat(stream.fileno())
        if identity(before) != identity(opened):
            raise ValueError(f"File changed: {path}")
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    after = plain(path)
    if identity(before) != identity(after):
        raise ValueError(f"File changed while hashing: {path}")
    return {'bytes': before.st_size, 'mtime_ns': before.st_mtime_ns,
            'ctime_ns': before.st_ctime_ns, 'dev': before.st_dev,
            'ino': before.st_ino, 'sha256': digest}


def identity(st):
    return st.st_dev, st.st_ino, st.st_size, st.st_mtime_ns, st.st_ctime_ns


def read_json(path):
    before = fingerprint(path)
    data = json.loads(path.read_bytes())
    if fingerprint(path) != before:
        raise ValueError(f"JSON changed: {path}")
    return data, before


def load_pins(path, nonroot):
    data, snapshot = read_json(path)
    if data.get('schema') != 1 or not isinstance(data.get('pinnedRuns'), list):
        raise ValueError('Expected schema 1 and pinnedRuns list')
    pins = {}
    for pin in data['pinnedRuns']:
        name, reason = pin.get('run'), pin.get('reason')
        if (not isinstance(name, str) or not RUN.fullmatch(name) or name in pins
                or not isinstance(reason, str) or not reason.strip()):
            raise ValueError(f'Invalid or duplicate pin: {pin!r}')
        plain(nonroot / name, directory=True)
        pins[name] = reason
    if not pins:
        raise ValueError('At least one explicit local pin is required')
    return pins, snapshot


def inspect_run(path, cutoff):
    row = {'run': path.name, 'path': str(path), 'group': None,
           'completed': False, 'restorable': False, 'retained': True,
           'reason': '', 'report': None, 'files': []}
    try:
        st = plain(path, directory=True)
        if not RUN.fullmatch(path.name):
            raise ValueError('Unrecognized run name')
        report, row['report'] = read_json(path / 'report.json')
        package, abi = report.get('package'), report.get('runtime_abi')
        if not abi:
            runtime = report.get('runtime')
            if isinstance(runtime, dict):
                runtime = [runtime]
            if isinstance(runtime, list) and len(runtime) == 1 and isinstance(runtime[0], dict):
                abi = runtime[0].get('abi')
            elif report.get('mode') == 'panel-only':
                abi = 'panel-only'
        if package not in ('dev.pgrazor.preview', 'com.nianticlabs.pokemongo') or abi not in ('arm64-v8a', 'x86_64', 'auto', 'panel-only'):
            raise ValueError('Unsupported package/ABI')
        if abi == 'auto' and sorted(report.get('runtime_abis', [])) != ['arm64-v8a', 'x86_64']:
            raise ValueError('Incomplete unified runtime receipt')
        outputs = report.get('outputs')
        if not isinstance(outputs, list) or not outputs:
            raise ValueError('Missing outputs')
        seen = set()
        for output in outputs:
            signed = Path(output['file'])
            expected = output['sha256']
            if (signed.parent != path or not SIGNED.fullmatch(signed.name)
                    or signed.name in seen or not isinstance(expected, str) or not SHA.fullmatch(expected)):
                raise ValueError('Unexpected signed output receipt')
            seen.add(signed.name)
            if fingerprint(signed)['sha256'] != expected.lower():
                raise ValueError(f'Signed APK checksum mismatch: {signed.name}')
        if 'base.apk' not in seen:
            raise ValueError('Missing base.apk receipt')
        row.update(completed=True, restorable=True, group=f'{package}/{abi}')
        newest = max(st.st_mtime_ns, row['report']['mtime_ns'])
        for item in sorted(path.iterdir()):
            if APK.fullmatch(item.name):
                meta = fingerprint(item)
                newest = max(newest, meta['mtime_ns'])
                row['files'].append({'path': str(item), **meta})
        if newest > cutoff:
            row['reason'] = 'Recent run'
        else:
            row.update(retained=False, reason='Eligible old run')
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as exc:
        row.update(completed=False, restorable=False, retained=True,
                   reason=f'Protected: {exc}', files=[])
    return row


def save_receipt(path, receipt):
    plain(path.parent, directory=True)
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent, delete=False) as tmp:
        json.dump(receipt, tmp, indent=2)
        tmp.write('\n')
        tmp.flush()
        os.fsync(tmp.fileno())
        temp = Path(tmp.name)
    os.replace(temp, path)


def clean(project, pinfile, apply=False, keep_latest=3, min_age_minutes=30):
    if not 3 <= keep_latest <= 100 or not 30 <= min_age_minutes <= 525600:
        raise ValueError('keep-latest must be 3..100; min-age-minutes 30..525600')
    build = project / 'build'
    nonroot = build / 'nonroot'
    plain(nonroot, directory=True)
    pins, pin_snapshot = load_pins(pinfile, nonroot)
    started = datetime.now(timezone.utc)
    cutoff = time.time_ns() - min_age_minutes * 60 * 10**9
    rows = []
    for path in sorted(nonroot.iterdir()):
        # An unexpected symlink or special file must never become a candidate.
        if stat.S_ISDIR(path.lstat().st_mode) or path.is_symlink():
            rows.append(inspect_run(path, cutoff))
    for group in {r['group'] for r in rows if r['restorable']}:
        recent = sorted((r for r in rows if r['restorable'] and r['group'] == group),
                        key=lambda r: r['run'], reverse=True)[:keep_latest]
        for row in recent:
            row.update(retained=True, reason=f'Latest {keep_latest} verified runs for {group}')
    for row in rows:
        if row['run'] in pins:
            row.update(retained=True, reason=f"Pinned: {pins[row['run']]}; {row['reason']}")
    # Never delete anything when a designated rollback is damaged or incomplete.
    if any(not r['restorable'] for r in rows if r['run'] in pins):
        raise ValueError('A pinned run is not restorable; review pins/reports before cleanup')
    entries = [{**f, 'run': r['run'], 'status': 'planned' if apply else 'dry-run'}
               for r in rows if not r['retained'] for f in r['files']]
    receipt_dir = build / 'cleanup'
    receipt_dir.mkdir(exist_ok=True)
    plain(receipt_dir, directory=True)
    receipt_path = receipt_dir / f"linux-{started.strftime('%Y%m%d-%H%M%S-%f')}-{os.getpid()}.json"
    receipt = {'schema': 1, 'startedUtc': started.isoformat(), 'apply': apply,
               'root': str(nonroot), 'retentionFile': str(pinfile),
               'retentionSha256': pin_snapshot['sha256'], 'keepLatest': keep_latest,
               'minAgeMinutes': min_age_minutes, 'runs': rows, 'files': entries,
               'status': 'applying' if apply else 'dry-run', 'deletedBytes': 0}
    save_receipt(receipt_path, receipt)
    for row in rows:
        print(f"{'KEEP' if row['retained'] else 'PRUNE'} {row['run']}: {row['reason']}")
    if apply:
        try:
            by_run = {r['run']: r for r in rows}
            for entry in entries:
                row = by_run[entry['run']]
                path = Path(entry['path'])
                if fingerprint(pinfile) != pin_snapshot:
                    raise ValueError('Pins changed during cleanup')
                if fingerprint(Path(row['path']) / 'report.json') != row['report']:
                    raise ValueError('Build report changed during cleanup')
                if path.parent != nonroot / entry['run'] or not APK.fullmatch(path.name):
                    raise ValueError(f'Unsafe candidate: {path}')
                original = {k: entry[k] for k in ('bytes', 'mtime_ns', 'ctime_ns', 'dev', 'ino', 'sha256')}
                if fingerprint(path) != original or entry['mtime_ns'] > cutoff:
                    raise ValueError(f'Candidate changed during cleanup: {path}')
                path.unlink()  # File only, never recursive.
                entry['status'] = 'deleted'
                receipt['deletedBytes'] += entry['bytes']
                save_receipt(receipt_path, receipt)
            receipt['status'] = 'complete'
        except BaseException as exc:
            receipt.update(status='stopped-on-error', error=str(exc))
            raise
        finally:
            save_receipt(receipt_path, receipt)
    total = sum(e['bytes'] for e in entries)
    print(f"{'Applied' if apply else 'Dry run'}: {len(entries)} APK files; "
          f"{total / 2**30:.3f} GiB selected; {receipt['deletedBytes'] / 2**30:.3f} GiB deleted.")
    print(f'Receipt: {receipt_path}')
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--keep-latest', type=int, default=3)
    parser.add_argument('--min-age-minutes', type=int, default=30)
    args = parser.parse_args()
    try:
        clean(Path('/workspace/pgrazor'), Path('/cleanup-cache/nonroot-retention.json'),
              args.apply, args.keep_latest, args.min_age_minutes)
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as exc:
        parser.exit(1, f'Cleanup stopped: {exc}\n')
