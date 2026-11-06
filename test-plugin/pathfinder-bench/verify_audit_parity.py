"""Validate the untimed full-coordinate sidecar's complete matrix."""
import argparse
import json
import re
from itertools import product
from pathlib import Path

from summarize_audit import fields


MODES = ("masks", "arithmetic", "lifecycle")
VARIANTS = ("baseline", "candidate")
SIZES = ("32", "64", "128", "256")
LENGTHS = ("short", "long")
WORLDS = ("serpentine", "village")
REVERSES = ("false", "true")
IMPLS = ("NATIVE", "NATIVE_FASTPATH", "NATIVE_SNAPSHOT_LEGACY",
         "NATIVE_SNAPSHOT_FUSED", "NATIVE_MIRROR")
KEYS = ("mode", "variant", "size", "length", "world", "reverse", "impl")


def verify_text(text):
    expected = set(product(MODES, VARIANTS, SIZES, LENGTHS, WORLDS, REVERSES, IMPLS))
    records = {}
    for line in text.splitlines():
        if not line.startswith("PATHFIND_SIDECAR "):
            continue
        data = fields(line)
        if set(data) != set(KEYS) | {"nodes", "checksum"}:
            raise ValueError(f"invalid sidecar fields: {line}")
        key = tuple(data[name] for name in KEYS)
        if key not in expected or key in records:
            raise ValueError(f"unexpected or duplicate sidecar record: {line}")
        nodes, checksum = int(data["nodes"]), int(data["checksum"])
        if nodes <= 0 or not -(2**63) <= checksum < 2**63:
            raise ValueError(f"invalid path result: {line}")
        records[key] = (nodes, checksum)
    if set(records) != expected:
        raise ValueError(f"incomplete sidecar matrix: {len(records)}/{len(expected)}")
    if re.findall(r"^PATHFIND_SIDECAR_COMPLETE checks=(\d+)$", text, re.MULTILINE) != [str(len(expected))]:
        raise ValueError("missing or duplicate sidecar completion")
    for size, length, world in product(SIZES, LENGTHS, WORLDS):
        results = {value for key, value in records.items() if key[2:5] == (size, length, world)}
        if len(results) != 1:
            raise ValueError(f"coordinate checksum or length mismatch: {size} {length} {world}")
    return len(records)


def verify_directory(root):
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8-sig"))
    if (manifest["exitCode"] != 0 or manifest["before"] != manifest["after"]
            or not re.fullmatch(r"[0-9A-Fa-f]{64}", manifest["before"])):
        raise ValueError("sidecar failed or native library changed")
    text = (root / "parity.log").read_text(encoding="utf-8-sig")
    if "BUILD SUCCESSFUL" not in text:
        raise ValueError("sidecar JVM did not complete")
    return verify_text(text), manifest["before"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    count, sha = verify_directory(args.directory)
    print(f"PASS full-coordinate-checks={count} native-sha256={sha}")


if __name__ == "__main__":
    main()
