"""Summarize ABBA logs without discarding slow samples."""
import argparse
import csv
import json
import math
import re
import statistics
from collections import defaultdict
from itertools import product
from pathlib import Path


def fields(line):
    return dict(re.findall(r"([\w-]+)=([^\s]+)", line))


def number(value, label):
    result = float(value)
    if not math.isfinite(result) or result < 0:
        raise ValueError(f"invalid {label}: {value}")
    return result


def summarize(root):
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8-sig"))
    sizes = [int(value) for value in manifest["sizes"].split(",")]
    rounds, sample_count = int(manifest["rounds"]), int(manifest["samples"])
    if not sizes or len(set(sizes)) != len(sizes) or min(sizes) <= 0 or rounds <= 0 or sample_count <= 0:
        raise ValueError("invalid matrix dimensions")
    # Earlier masks manifests describe the snapshot-only runner.
    entries = manifest.get("entries", ["snapshot"])
    if isinstance(entries, str):
        entries = [entries]
    worlds = manifest.get("worlds", ["serpentine", "village"])
    lengths = manifest.get("lengths", ["short", "long"])
    reverses = manifest.get("reverse", ["false", "true"])
    if (not entries or len(set(entries)) != len(entries) or not set(entries) <= {"snapshot", "kernel"}
            or worlds != ["serpentine", "village"] or lengths != ["short", "long"]
            or reverses != ["false", "true"]):
        raise ValueError("unsupported or duplicate matrix dimensions")
    before, after = manifest["nativeLibrariesBefore"], manifest["nativeLibrariesAfter"]
    if len(before) != 2 or len(after) != 2:
        raise ValueError("native library fingerprint matrix is incomplete")
    hashes = [item.get("sha256") for item in before + after]
    if any(not isinstance(sha, str) or re.fullmatch(r"[0-9A-Fa-f]{64}", sha) is None for sha in hashes):
        raise ValueError("native library SHA-256 is missing or malformed")
    if len({sha.upper() for sha in hashes}) != 1:
        raise ValueError("native library changed or differs between build and resource")
    if [item["path"] for item in before] != [item["path"] for item in after]:
        raise ValueError("native library paths changed")
    expected_logs = {f"{entry}-{length}-{reverse}.log" for entry, length, reverse in
                     product(entries, lengths, reverses)}
    logs = {log.name: log for log in root.glob("*.log")}
    if set(logs) != expected_logs:
        raise ValueError(f"incomplete log matrix: expected={sorted(expected_logs)} actual={sorted(logs)}")
    implementations = {"snapshot": {"vanilla", "snapshot-old-order", "snapshot-fused", "native-mirror"},
                       "kernel": {"vanilla", "native", "native-fastpath"}}
    groups = defaultdict(lambda: {"samples": [], "bytes": [], "runs": {}, "nodes": set()})
    parity_count = 0
    diagnostics = []
    for name, log in sorted(logs.items()):
        text = log.read_text(encoding="utf-8-sig")
        if "BUILD SUCCESSFUL" not in text or re.findall(r"^EXIT=(.*)$", text, re.MULTILINE) != ["0"]:
            raise ValueError(f"incomplete benchmark: {log}")
        entry, length, reverse = log.stem.split("-")
        variant = None
        run = None
        blocks, scaling, samples, results, parity, fastpath = [], set(), {}, {}, {}, {}
        for line in text.splitlines():
            if line.startswith("PATHFIND_AB "):
                data = fields(line)
                variant = data["variant"]
                size, round_id, step = int(data["size"]), int(data["round"]), int(data["step"])
                if (data["mode"] != manifest["mode"] or variant != ("candidate" if step in (1, 2) else "baseline")
                        or size not in sizes or not 0 <= round_id < rounds or step not in range(4)):
                    raise ValueError(f"invalid AB selector: {line}")
                run = (size, round_id, step)
                blocks.append(run)
            elif line.startswith("PATHFIND_SCALING "):
                if run is None or run in scaling:
                    raise ValueError(f"missing selector or duplicate scaling: {line}")
                data = fields(line)
                expected = {"entry": entry, "length": length, "world": "both", "reverse": reverse,
                            "size": f"{run[0]}x{run[0]}", **{key: str(manifest[key]) for key in
                                                          ("warmup", "samples", "iterations")}}
                if any(data.get(key) != value for key, value in expected.items()):
                    raise ValueError(f"scaling differs from manifest: {line}")
                scaling.add(run)
            elif line.startswith(("PATHFIND_SAMPLE ", "PATHFIND_RESULT ", "PATHFIND_PARITY ", "PATHFIND_FASTPATH ")):
                if run not in scaling:
                    raise ValueError(f"record before AB scaling: {log}")
                data = fields(line)
                world = data.get("world", "serpentine")
                if (data["entry"] != entry or data["length"] != length or world not in worlds
                        or data["size"] != f"{run[0]}x{run[0]}"):
                    raise ValueError(f"record differs from selector: {line}")
                scene = (run, world)
                if line.startswith("PATHFIND_PARITY "):
                    if scene in parity or int(data["nodes"]) <= 0:
                        raise ValueError(f"duplicate or invalid parity: {line}")
                    parity[scene] = int(data["nodes"])
                    parity_count += 1
                    continue
                if line.startswith("PATHFIND_FASTPATH "):
                    if scene in fastpath:
                        raise ValueError(f"duplicate fastpath: {line}")
                    candidates = int(data["cardinal-candidates"])
                    hits, outside = int(data["cardinal-hits"]), int(data["out-of-region"])
                    rate = number(data["cardinal-hit-rate"].removesuffix("%"), "hit rate")
                    actual_rate = 100 * hits / candidates if candidates else 0
                    if min(candidates, hits, outside) < 0 or hits > candidates or abs(rate - actual_rate) > 0.006:
                        raise ValueError(f"invalid fastpath counters: {line}")
                    fastpath[scene] = (candidates, hits, outside, data["top-misses"])
                    diagnostics.append({"entry": entry, "length": length, "reverse": reverse, "world": world,
                                        "size": data["size"], "variant": variant, "round": run[1], "step": run[2],
                                        "cardinal_candidates": candidates, "cardinal_hits": hits,
                                        "out_of_region": outside, "hit_rate_percent": actual_rate,
                                        "top_misses": data["top-misses"]})
                    continue
                impl = data["impl"]
                if impl not in implementations[entry]:
                    raise ValueError(f"unexpected implementation: {line}")
                record = (run, world, impl)
                if line.startswith("PATHFIND_SAMPLE "):
                    index = int(data["sample"])
                    if index not in range(sample_count) or index in samples.setdefault(record, {}):
                        raise ValueError(f"duplicate or out-of-range sample: {line}")
                    samples[record][index] = (number(data["ns-per-search"], "time"),
                                             number(data["bytes-per-search"], "bytes"))
                else:
                    if record in results:
                        raise ValueError(f"duplicate result: {line}")
                    number(data["ns-per-search"], "result time")
                    number(data["bytes-per-search"], "result bytes")
                    results[record] = (int(data["pathNodes"]),
                                       number(data["fastpath-hit-rate"].removesuffix("%"), "result hit rate"))
        expected_blocks = list(product(sizes, range(rounds), range(4)))
        scenes = set(product(expected_blocks, worlds))
        records = {(block, world, impl) for block, world, impl in
                   product(expected_blocks, worlds, implementations[entry])}
        if (blocks != expected_blocks or scaling != set(expected_blocks) or set(parity) != scenes
                or set(fastpath) != scenes or set(samples) != records or set(results) != records):
            raise ValueError(f"incomplete or duplicate AB matrix: {name}")
        for record, values in samples.items():
            block, world, impl = record
            if set(values) != set(range(sample_count)) or results[record][0] != parity[(block, world)]:
                raise ValueError(f"missing samples or path-node mismatch: {record}")
            candidates, hits, _, _ = fastpath[(block, world)]
            if impl == "native-fastpath":
                rate = 100 * hits / candidates if candidates else 0
                if abs(rate - results[record][1]) > 0.006:
                    raise ValueError(f"result fastpath hit-rate mismatch: {record}")
            elif results[record][1] != 0:
                raise ValueError(f"unexpected placeholder fastpath rate: {record}")
            variant = "candidate" if block[2] in (1, 2) else "baseline"
            key = (entry, length, reverse, world, f"{block[0]}x{block[0]}", impl, variant)
            group = groups[key]
            ordered = [values[index] for index in range(sample_count)]
            group["samples"].extend(value[0] for value in ordered)
            group["bytes"].extend(value[1] for value in ordered)
            group["runs"][block] = [value[0] for value in ordered]
            group["nodes"].add(results[record][0])
        for size, round_id, world in product(sizes, range(rounds), worlds):
            counters = [fastpath[((size, round_id, step), world)] for step in range(4)]
            if len(set(counters)) != 1:
                raise ValueError(f"AB fastpath diagnostics differ: {name} {size} {round_id} {world}")
    rows = []
    for key, group in sorted(groups.items()):
        if len(group["nodes"]) != 1 or not group["samples"]:
            raise ValueError(f"missing samples or inconsistent nodes: {key}")
        samples = sorted(group["samples"])
        row = dict(zip(("entry", "length", "reverse", "world", "size", "impl", "variant"), key))
        nodes = next(iter(group["nodes"]))
        row.update(samples=len(samples), nodes=nodes, p50=statistics.median(samples),
                   p95=samples[math.ceil(0.95 * len(samples)) - 1],
                   bytes_p50=statistics.median(group["bytes"]), bytes_max=max(group["bytes"]),
                   run_p50=[statistics.median(v) for _, v in sorted(group["runs"].items())])
        row["ns_per_node"] = row["p50"] / max(1, nodes)
        rows.append(row)
    paired = []
    variants = defaultdict(dict)
    for row in rows:
        key = tuple(row[k] for k in ("entry", "length", "reverse", "world", "size", "impl"))
        variants[key][row["variant"]] = row
    for key, pair in sorted(variants.items()):
        if set(pair) != {"baseline", "candidate"}:
            raise ValueError(f"missing AB variant: {key}")
        baseline, candidate = pair["baseline"], pair["candidate"]
        if baseline["nodes"] != candidate["nodes"]:
            raise ValueError(f"AB path-node mismatch: {key}")
        paired.append({
            **dict(zip(("entry", "length", "reverse", "world", "size", "impl"), key)),
            "baseline_p50": baseline["p50"], "candidate_p50": candidate["p50"],
            "improvement_percent": 100 * (1 - candidate["p50"] / baseline["p50"]),
            "baseline_p95": baseline["p95"], "candidate_p95": candidate["p95"],
            "baseline_run_p50": baseline["run_p50"], "candidate_run_p50": candidate["run_p50"],
            "baseline_bytes_max": baseline["bytes_max"], "candidate_bytes_max": candidate["bytes_max"],
            "baseline_bytes_p50": baseline["bytes_p50"], "candidate_bytes_p50": candidate["bytes_p50"],
            "nodes": baseline["nodes"],
        })
    if not paired or parity_count == 0:
        raise ValueError("no complete AB data")
    return {"manifest": manifest, "parity_checks": parity_count, "measurements": rows, "comparisons": paired,
            "fastpath_diagnostics": diagnostics,
            "metric_notes": {
                "ns_per_node": "Total search time divided by returned pathNodes, not visited/expanded nodes.",
                "fastpath": "Independent kernel diagnostic pass; not timed mirror hit-rate. Other RESULT rates are placeholders.",
                "bytes": "Java ThreadMXBean allocation per search, not native allocator instrumentation.",
                "samples": "All samples retained, including slow samples; ABBA run medians reported separately."}}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    result = summarize(args.directory)
    output = args.directory / "summary.json"
    with output.open("x", encoding="utf-8") as file:
        json.dump(result, file, indent=2)
        file.write("\n")
    csv_path = args.directory / "summary.csv"
    with csv_path.open("x", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=result["comparisons"][0].keys())
        writer.writeheader()
        writer.writerows(result["comparisons"])
    print(f"PASS comparisons={len(result['comparisons'])} parity={result['parity_checks']}")


if __name__ == "__main__":
    main()
