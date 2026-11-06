import json
import tempfile
import unittest
from pathlib import Path

from summarize_audit import summarize


class AuditSummaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        library = {"path": "build.dll", "sha256": "A" * 64}
        resource = {"path": "resource.dll", "sha256": "A" * 64}
        manifest = {"sizes": "32", "rounds": 1, "warmup": 1, "samples": 2, "iterations": 1,
                    "mode": "arithmetic", "entries": ["snapshot", "kernel"],
                    "nativeLibrariesBefore": [library, resource], "nativeLibrariesAfter": [library, resource]}
        (self.root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        for entry in manifest["entries"]:
            impls = (["vanilla", "native", "native-fastpath"] if entry == "kernel" else
                     ["vanilla", "snapshot-old-order", "snapshot-fused", "native-mirror"])
            for length in ("short", "long"):
                for reverse in ("false", "true"):
                    lines = []
                    for step in range(4):
                        variant = "candidate" if step in (1, 2) else "baseline"
                        lines.append(f"PATHFIND_AB mode=arithmetic variant={variant} size=32 round=0 step={step}")
                        lines.append(f"PATHFIND_SCALING entry={entry} length={length} world=both "
                                     f"size=32x32 warmup=1 samples=2 iterations=1 reverse={reverse}")
                        for world in ("serpentine", "village"):
                            prefix = f"world={world} entry={entry} length={length} size=32x32"
                            for impl in impls:
                                for sample, time in enumerate((10, 1000)):
                                    lines.append(f"PATHFIND_SAMPLE {prefix} impl={impl} sample={sample} "
                                                 f"ns-per-search={time} bytes-per-search=104")
                            lines.append(f"PATHFIND_PARITY {prefix} nodes=4")
                            lines.append(f"PATHFIND_FASTPATH {prefix} cardinal-candidates=4 cardinal-hits=3 "
                                         "out-of-region=0 cardinal-hit-rate=75.00% top-misses=none")
                            for impl in impls:
                                rate = 75 if impl == "native-fastpath" else 0
                                lines.append(f"PATHFIND_RESULT {prefix} impl={impl} pathNodes=4 "
                                             f"ns-per-search=505 bytes-per-search=104 fastpath-hit-rate={rate}%")
                    lines.extend(("BUILD SUCCESSFUL", "EXIT=0"))
                    (self.root / f"{entry}-{length}-{reverse}.log").write_text("\n".join(lines), encoding="utf-8")

    def change(self, old, new, count=1):
        log = self.root / "snapshot-short-false.log"
        text = log.read_text(encoding="utf-8")
        self.assertIn(old, text)
        log.write_text(text.replace(old, new, count), encoding="utf-8")

    def test_complete_matrix_retains_slow_samples(self):
        result = summarize(self.root)
        self.assertEqual(64, result["parity_checks"])
        self.assertEqual(56, len(result["comparisons"]))
        self.assertEqual(64, len(result["fastpath_diagnostics"]))
        for row in result["measurements"]:
            self.assertEqual(4, row["samples"])
            self.assertEqual(505, row["p50"])
            self.assertEqual(1000, row["p95"])
            self.assertEqual(104, row["bytes_p50"])

    def test_missing_log(self):
        (self.root / "snapshot-long-true.log").unlink()
        with self.assertRaisesRegex(ValueError, "log matrix"):
            summarize(self.root)

    def test_legacy_single_entry_manifest(self):
        path = self.root / "manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["entries"] = "snapshot"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        for log in self.root.glob("kernel-*.log"):
            log.unlink()
        result = summarize(self.root)
        self.assertEqual(32, result["parity_checks"])
        self.assertEqual(32, len(result["comparisons"]))

    def test_wrong_variant(self):
        self.change("variant=candidate", "variant=baseline")
        with self.assertRaisesRegex(ValueError, "AB selector"):
            summarize(self.root)

    def test_missing_sample(self):
        self.change("PATHFIND_SAMPLE", "REMOVED_SAMPLE")
        with self.assertRaisesRegex(ValueError, "missing samples"):
            summarize(self.root)

    def test_duplicate_sample(self):
        self.change("sample=1", "sample=0")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            summarize(self.root)

    def test_missing_parity(self):
        self.change("PATHFIND_PARITY", "REMOVED_PARITY")
        with self.assertRaisesRegex(ValueError, "AB matrix"):
            summarize(self.root)

    def test_nan_allocation(self):
        self.change("bytes-per-search=104", "bytes-per-search=NaN")
        with self.assertRaisesRegex(ValueError, "invalid bytes"):
            summarize(self.root)

    def test_mismatched_diagnostics(self):
        self.change("cardinal-hits=3", "cardinal-hits=2")
        self.change("cardinal-hit-rate=75.00%", "cardinal-hit-rate=50.00%")
        with self.assertRaisesRegex(ValueError, "diagnostics differ"):
            summarize(self.root)

    def test_scaling_mismatch(self):
        self.change("iterations=1", "iterations=2")
        with self.assertRaisesRegex(ValueError, "scaling differs"):
            summarize(self.root)

    def test_node_mismatch(self):
        self.change("pathNodes=4", "pathNodes=5")
        with self.assertRaisesRegex(ValueError, "path-node mismatch"):
            summarize(self.root)

    def test_native_hash_mismatch(self):
        path = self.root / "manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["nativeLibrariesAfter"][0]["sha256"] = "B" * 64
        path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "native library"):
            summarize(self.root)

    def test_missing_native_hashes(self):
        path = self.root / "manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        for entries in (manifest["nativeLibrariesBefore"], manifest["nativeLibrariesAfter"]):
            for entry in entries:
                del entry["sha256"]
        path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            summarize(self.root)

    def test_empty_and_malformed_native_hashes(self):
        path = self.root / "manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        for sha in ("", "A" * 63, "A" * 65, "G" * 64, None, 123):
            with self.subTest(sha=sha):
                for entries in (manifest["nativeLibrariesBefore"], manifest["nativeLibrariesAfter"]):
                    for entry in entries:
                        entry["sha256"] = sha
                path.write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "SHA-256"):
                    summarize(self.root)

    def test_native_hash_case_insensitive(self):
        path = self.root / "manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["nativeLibrariesAfter"][0]["sha256"] = "a" * 64
        path.write_text(json.dumps(manifest), encoding="utf-8")
        self.assertEqual(56, len(summarize(self.root)["comparisons"]))


if __name__ == "__main__":
    unittest.main()
