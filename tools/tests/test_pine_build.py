"""Unit tests for tools/pine_build.py (V6 Agent 02).  Run: python3 -m unittest discover -s tools/tests -v"""
import hashlib
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
REPO = os.path.dirname(TOOLS)
sys.path.insert(0, TOOLS)

import pine_build  # noqa: E402


def slurp(path, mode="r"):
    with open(path, mode) as fh:
        return fh.read()


def write(d, name, text):
    with open(os.path.join(d, name), "w", encoding="utf-8") as fh:
        fh.write(text)


class AssembleTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.src = self.tmp.name
        write(self.src, "00_header.pine", "//@version=6\nstrategy(\"x\")\n// @@BUILD_ID@@\n")
        write(self.src, "10_inputs.pine", "a = input.int(1, \"a\")\n")
        write(self.src, "80_strategy.pine", "// stub\n")

    def tearDown(self):
        self.tmp.cleanup()

    def test_order_markers_and_version_first(self):
        text, h = pine_build.assemble(self.src, "abc", "unstamped")
        lines = text.split("\n")
        self.assertEqual(lines[0], "//@version=6")
        self.assertTrue(lines[1].startswith("// BUILD: LucidICT_v6 | git abc | built unstamped | src-sha256 " + h))
        i0 = lines.index("// ==== file: 00_header.pine ====")
        i1 = lines.index("// ==== file: 10_inputs.pine ====")
        i8 = lines.index("// ==== file: 80_strategy.pine ====")
        self.assertTrue(i0 < i1 < i8)
        self.assertEqual(text.count("//@version=6"), 1)
        self.assertIn('const string BUILD_ID = "{}"'.format(h), text)
        self.assertNotIn("@@BUILD_ID@@", text)

    def test_numeric_order_not_lexicographic_surprise(self):
        write(self.src, "95_debug.pine", "// dbg\n")
        write(self.src, "20_time.pine", "// t\n")
        names = pine_build.list_sources(self.src)
        self.assertEqual(names, ["00_header.pine", "10_inputs.pine", "20_time.pine", "80_strategy.pine", "95_debug.pine"])

    def test_deterministic_except_time(self):
        a, ha = pine_build.assemble(self.src, "abc", "2026-01-01T00:00:00Z")
        b, hb = pine_build.assemble(self.src, "abc", "2026-11-05T12:34:56Z")
        self.assertEqual(ha, hb)
        self.assertNotEqual(a, b)
        self.assertEqual(pine_build.strip_build_time(a), pine_build.strip_build_time(b))
        c, _ = pine_build.assemble(self.src, "abc", "2026-01-01T00:00:00Z")
        self.assertEqual(a, c)

    def test_src_hash_changes_with_content(self):
        _, h1 = pine_build.assemble(self.src, "abc", "unstamped")
        write(self.src, "10_inputs.pine", "a = input.int(2, \"a\")\n")
        _, h2 = pine_build.assemble(self.src, "abc", "unstamped")
        self.assertNotEqual(h1, h2)

    def test_crlf_and_trailing_space_normalised(self):
        _, h1 = pine_build.assemble(self.src, "abc", "unstamped")
        write(self.src, "10_inputs.pine", "a = input.int(1, \"a\")   \r\n\r\n")
        _, h2 = pine_build.assemble(self.src, "abc", "unstamped")
        self.assertEqual(h1, h2)

    def test_errors(self):
        write(self.src, "10_dup.pine", "x\n")
        with self.assertRaises(pine_build.BuildError):
            pine_build.assemble(self.src, "abc", "unstamped")
        os.remove(os.path.join(self.src, "10_dup.pine"))
        write(self.src, "20_bad.pine", "//@version=5\n")
        with self.assertRaises(pine_build.BuildError):
            pine_build.assemble(self.src, "abc", "unstamped")
        os.remove(os.path.join(self.src, "20_bad.pine"))
        write(self.src, "00_header.pine", "strategy(\"x\")\n")
        with self.assertRaises(pine_build.BuildError):
            pine_build.assemble(self.src, "abc", "unstamped")
        write(self.src, "00_header.pine", "//@version=6\n\tx = 1\n")
        with self.assertRaises(pine_build.BuildError):
            pine_build.assemble(self.src, "abc", "unstamped")

    def test_source_date_epoch(self):
        old = os.environ.get("SOURCE_DATE_EPOCH")
        try:
            os.environ["SOURCE_DATE_EPOCH"] = "0"
            self.assertEqual(pine_build.build_time(False), "1970-01-01T00:00:00Z")
            self.assertEqual(pine_build.build_time(True), "unstamped")
        finally:
            if old is None:
                os.environ.pop("SOURCE_DATE_EPOCH", None)
            else:
                os.environ["SOURCE_DATE_EPOCH"] = old


class RealSourcesTest(unittest.TestCase):
    """The committed pine/src builds, two builds are byte-identical under SOURCE_DATE_EPOCH, and the
    committed dist is up to date (--check)."""

    def run_build(self, out, extra_env=None):
        env = dict(os.environ)
        env.update(extra_env or {})
        return subprocess.run([sys.executable, os.path.join(TOOLS, "pine_build.py"), "--out", out, "--sha", "test"],
                              env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_two_builds_identical(self):
        with tempfile.TemporaryDirectory() as d:
            a, b = os.path.join(d, "a.pine"), os.path.join(d, "b.pine")
            for out in (a, b):
                r = self.run_build(out, {"SOURCE_DATE_EPOCH": "1762300800"})
                self.assertEqual(r.returncode, 0, r.stderr.decode())
            ha = hashlib.sha256(slurp(a, "rb")).hexdigest()
            hb = hashlib.sha256(slurp(b, "rb")).hexdigest()
            self.assertEqual(ha, hb)

    def test_builds_differ_only_in_time_without_epoch(self):
        with tempfile.TemporaryDirectory() as d:
            a, b = os.path.join(d, "a.pine"), os.path.join(d, "b.pine")
            self.assertEqual(self.run_build(a, {"SOURCE_DATE_EPOCH": "1"}).returncode, 0)
            self.assertEqual(self.run_build(b, {"SOURCE_DATE_EPOCH": "2"}).returncode, 0)
            ta, tb = slurp(a), slurp(b)
            self.assertNotEqual(ta, tb)
            self.assertEqual(pine_build.strip_build_time(ta), pine_build.strip_build_time(tb))

    def test_committed_dist_is_current(self):
        dist = os.path.join(REPO, "pine", "dist", "LucidICT_v6.pine")
        r = subprocess.run([sys.executable, os.path.join(TOOLS, "pine_build.py"), "--check", "--out", dist],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(r.returncode, 0, r.stdout.decode() + r.stderr.decode())


if __name__ == "__main__":
    unittest.main()
