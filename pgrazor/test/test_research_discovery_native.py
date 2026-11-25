"""Hosted metadata fixture for the optional native discovery component."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

class ResearchDiscoveryNative(unittest.TestCase):
    def test_hosted_metadata(self):
        compiler = shutil.which("g++")
        javac = shutil.which("javac")
        if not compiler or not javac:
            self.skipTest("g++ and JDK required")
        include = Path(javac).resolve().parent.parent / "include"
        if not (include / "linux" / "jni_md.h").is_file():
            self.skipTest("Linux JDK headers required")
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "research-discovery"
            subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-rdynamic",
                            "-I", str(include), "-I", str(include / "linux"),
                            "-I", str(ROOT / "native"), str(ROOT / "test/research_discovery_fixture.cpp"),
                            "-ldl", "-o", str(output)], check=True, timeout=30)
            subprocess.run([str(output)], check=True, timeout=10)
