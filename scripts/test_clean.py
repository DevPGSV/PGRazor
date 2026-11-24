"""Synthetic retention tests; does not touch the real workspace."""
import contextlib
import hashlib
import io
import json
import os
import tempfile
import time
import unittest
from pathlib import Path
from clean import clean


class CleanupTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.project = self.root / 'pgrazor'
        self.nonroot = self.project / 'build' / 'nonroot'
        self.nonroot.mkdir(parents=True)
        self.pinfile = self.root / 'pins.json'
        self.runs = [self.make_run(i) for i in range(1, 6)]
        self.pin(self.runs[0])

    def make_run(self, i, abi='arm64-v8a', recent=False):
        run = self.nonroot / f'20260101-0000{i:02d}-12345678'
        run.mkdir()
        outputs = []
        for name in ('base.apk', 'split-1.apk', 'base-unsigned.apk', 'input-0.apk', 'base.apk.idsig', 'build.log'):
            file = run / name
            file.write_bytes(name.encode())
            if name in ('base.apk', 'split-1.apk'):
                outputs.append({'file': str(file), 'sha256': hashlib.sha256(file.read_bytes()).hexdigest()})
        (run / 'report.json').write_text(json.dumps({'package': 'com.nianticlabs.pokemongo', 'runtime_abi': abi, 'outputs': outputs}))
        (run / 'nested').mkdir()
        (run / 'nested' / 'base.apk').write_text('keep')
        if not recent:
            old = time.time() - 7200
            for p in (*run.iterdir(), run):
                os.utime(p, (old, old))
        return run

    def pin(self, *runs):
        self.pinfile.write_text(json.dumps({'schema': 1, 'pinnedRuns': [{'run': r.name, 'reason': 'test'} for r in runs]}))

    def execute(self, **kwargs):
        with contextlib.redirect_stdout(io.StringIO()):
            return clean(self.project, self.pinfile, **kwargs)

    def test_dry_run_and_apply(self):
        planned = self.execute()
        self.assertEqual({e['run'] for e in planned['files']}, {self.runs[1].name})
        self.assertEqual(len(planned['files']), 4)
        self.assertTrue((self.runs[1] / 'base.apk').exists())
        applied = self.execute(apply=True)
        self.assertEqual(applied['status'], 'complete')
        self.assertFalse((self.runs[1] / 'base.apk').exists())
        for name in ('base.apk.idsig', 'build.log', 'report.json', 'nested/base.apk'):
            self.assertTrue((self.runs[1] / name).exists())
        for run in (self.runs[0], *self.runs[2:]):
            self.assertTrue((run / 'base.apk').exists())
        self.assertEqual(self.execute()['files'], [])

    def test_missing_pins_refuses(self):
        self.pin()
        with self.assertRaises(ValueError):
            self.execute(apply=True)
        self.assertTrue((self.runs[1] / 'base.apk').exists())

    def test_corrupt_pinned_run_refuses(self):
        (self.runs[0] / 'base.apk').write_text('bad')
        with self.assertRaises(ValueError):
            self.execute(apply=True)
        self.assertTrue((self.runs[1] / 'base.apk').exists())

    def test_recent_or_incomplete_protected(self):
        (self.runs[1] / 'input-0.apk').touch()
        (self.runs[2] / 'report.json').unlink()
        self.assertEqual(self.execute(apply=True)['files'], [])

    def test_separate_abi_group(self):
        self.make_run(6, abi='x86_64')
        self.assertEqual({e['run'] for e in self.execute()['files']}, {self.runs[1].name})

    def test_symlink_protected(self):
        outside = self.root / 'outside.apk'
        outside.write_text('keep')
        path = self.runs[1] / 'base-unsigned.apk'
        path.unlink()
        path.symlink_to(outside)
        self.execute(apply=True)
        self.assertEqual(outside.read_text(), 'keep')
        self.assertTrue(path.is_symlink())

    def test_minimum_limits(self):
        with self.assertRaises(ValueError):
            self.execute(keep_latest=2, apply=True)
        with self.assertRaises(ValueError):
            self.execute(min_age_minutes=1, apply=True)


if __name__ == '__main__':
    unittest.main()
