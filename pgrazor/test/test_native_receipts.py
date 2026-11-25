import copy
import hashlib
import io
import unittest
import zipfile

from native_receipts import MARKER, SONAME, verify_cores


class NativeReceiptTests(unittest.TestCase):
    def check(self, research):
        abis = ["arm64-v8a", "x86_64"]
        report = {"runtime_abis": abis, "features": {"research_discovery": research},
                  "native_pilot": {"libraries": {}}}
        libraries = report["native_pilot"]["libraries"]
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            for abi in abis:
                base = abi.encode()
                payload = base + (MARKER if research else b"")
                archive.writestr(f"assets/pgrazor/{abi}/{SONAME}", payload)
                libraries[abi] = {"abi": abi, "soname": SONAME, "stripped_sha256": hashlib.sha256(payload).hexdigest(),
                                  "research_discovery": research, "source_fingerprint": "a" * 64}
        with zipfile.ZipFile(buffer) as archive:
            self.assertEqual(verify_cores(archive, report, {"hook_runtime_architectures": abis}), "a" * 64)
            for abi in abis:
                with self.subTest(abi=abi):
                    wrong = copy.deepcopy(report)
                    receipt = wrong["native_pilot"]["libraries"][abi]
                    other = abi.encode() + (b"" if research else MARKER)
                    receipt["stripped_sha256"] = hashlib.sha256(other).hexdigest()
                    with self.assertRaisesRegex(ValueError, f"SHA-256 mismatch for {abi}"):
                        verify_cores(archive, wrong)
                    receipt.update(libraries[abi], research_discovery=not research)
                    with self.assertRaisesRegex(ValueError, f"variant mismatch for {abi}"):
                        verify_cores(archive, wrong)
                    receipt.update(libraries[abi], source_fingerprint="b" * 64)
                    with self.assertRaisesRegex(ValueError, "source fingerprints disagree"):
                        verify_cores(archive, wrong)

    def test_default_receipt_cannot_describe_research_payload(self):
        self.check(False)

    def test_research_receipt_cannot_describe_default_payload(self):
        self.check(True)
