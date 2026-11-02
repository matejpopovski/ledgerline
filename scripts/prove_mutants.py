#!/usr/bin/env python3
"""Remove each protection from the source and record which tests notice.

Every mutant in mutants/manifest.json is a real patch against this tree: a check deleted,
a constraint dropped, a lock taken away. No flag in the production code enables them.
For each one this script copies the working tree to .mutants/<id>, applies the patch, runs
the whole test suite and reads the JUnit XML. Mutants that depend on a race get extra
trials of the tests expected to catch them, because a single catch could be luck.

    python3 scripts/prove_mutants.py                  full run, writes results/mutation-proofs.json
    python3 scripts/prove_mutants.py --only <id>      one mutant, prints only
    python3 scripts/prove_mutants.py --check-patches  verify every patch still applies, no tests
"""
import argparse
import datetime
import glob
import json
import os
import platform
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MANIFEST = REPO / "mutants" / "manifest.json"
WORK = REPO / ".mutants"
OUT = REPO / "results" / "mutation-proofs.json"
SKIP = {".git", ".gradle", ".mutants", ".kotlin", "build", "results", ".jqwik-database"}
RUN_TIMEOUT_SECONDS = 600


def gradle_env():
    env = dict(os.environ)
    # An inherited JAVA_HOME that points at nothing would stop gradlew before it starts.
    home = env.get("JAVA_HOME")
    if home and not Path(home, "bin", "java").exists():
        del env["JAVA_HOME"]
    return env


def copy_tree(target):
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(REPO, target, ignore=lambda _d, names: [n for n in names if n in SKIP])


def apply_patch(tree, patch):
    return subprocess.run(["patch", "-p1", "-s", "-i", str(patch)], cwd=tree, capture_output=True,
                          text=True)


def run_tests(tree, filters=()):
    command = ["./gradlew", "test", "--continue", "--console=plain"]
    for test_filter in filters:
        command += ["--tests", test_filter]
    results = tree / "build" / "test-results" / "test"
    if results.exists():
        shutil.rmtree(results)
    started = time.time()
    try:
        done = subprocess.run(command, cwd=tree, env=gradle_env(), capture_output=True, text=True,
                              timeout=RUN_TIMEOUT_SECONDS)
        output, timed_out = done.stdout + done.stderr, False
    except subprocess.TimeoutExpired as e:
        output, timed_out = str(e.stdout or "") + str(e.stderr or ""), True
    return read_results(results, output, time.time() - started, timed_out)


def read_results(directory, output, seconds, timed_out):
    total, failed = 0, []
    for path in glob.glob(str(directory / "*.xml")):
        suite = ET.parse(path).getroot()
        for case in suite.findall("testcase"):
            total += 1
            if case.find("failure") is not None or case.find("error") is not None:
                failed.append(f"{case.get('classname').rsplit('.', 1)[-1]}.{case.get('name')}")
    return {"tests": total, "failed": sorted(set(failed)), "seconds": round(seconds, 1),
            "timedOut": timed_out, "compiled": "compileJava FAILED" not in output
            and "compileTestJava FAILED" not in output, "tail": output[-1500:]}


def expected_hit(mutant, failed):
    return [name for name in failed if any(want in name for want in mutant.get("expectedKillers", []))]


def prove(mutant):
    tree = WORK / mutant["id"]
    copy_tree(tree)
    applied = apply_patch(tree, REPO / "mutants" / mutant["patch"])
    if applied.returncode != 0:
        sys.exit(f"{mutant['id']}: patch does not apply\n{applied.stderr}")
    run = run_tests(tree)
    if not run["compiled"]:
        sys.exit(f"{mutant['id']}: mutant does not compile, which proves nothing\n{run['tail']}")

    record = {
        "id": mutant["id"],
        "invariant": mutant["invariant"],
        "protectionRemoved": mutant["protectionRemoved"],
        "patch": "mutants/" + mutant["patch"],
        "expected": mutant.get("expected", "killed"),
        "testsRun": run["tests"],
        "testsFailed": run["failed"],
        "seconds": run["seconds"],
        "timedOut": run["timedOut"],
    }
    if not run["failed"] and not run["timedOut"]:
        record["outcome"] = "survived"
    elif expected_hit(mutant, run["failed"]):
        record["outcome"] = "killed"
    else:
        record["outcome"] = "killed-by-unexpected-test"

    trials = mutant.get("raceTrials", 0)
    if trials and run["failed"]:
        caught = 1
        for _ in range(trials - 1):
            again = run_tests(tree, mutant["raceFilters"])
            caught += 1 if expected_hit(mutant, again["failed"]) else 0
        record["raceTrials"] = {"trials": trials, "caught": caught}
    shutil.rmtree(tree, ignore_errors=True)
    return record


def baseline(runs):
    outcomes = []
    for i in range(runs):
        tree = WORK / "baseline"
        copy_tree(tree)
        run = run_tests(tree)
        print(f"baseline {i + 1}/{runs}: {run['tests']} tests, {len(run['failed'])} failed, "
              f"{run['seconds']}s", flush=True)
        if run["failed"] or run["timedOut"] or run["tests"] == 0:
            sys.exit(f"baseline must be green before mutants mean anything: {run['failed']}\n{run['tail']}")
        outcomes.append({"tests": run["tests"], "failed": 0, "seconds": run["seconds"]})
        shutil.rmtree(tree, ignore_errors=True)
    return outcomes


def check_patches(mutants):
    bad = []
    for mutant in mutants:
        applied = subprocess.run(["git", "apply", "--check", str(REPO / "mutants" / mutant["patch"])],
                                 cwd=REPO, capture_output=True, text=True)
        if applied.returncode != 0:
            bad.append(f"{mutant['id']}: {applied.stderr.strip()}")
    print(f"{len(mutants) - len(bad)} of {len(mutants)} patches apply")
    if bad:
        sys.exit("\n".join(bad))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--only")
    parser.add_argument("--check-patches", action="store_true")
    parser.add_argument("--baseline-runs", type=int, default=3)
    args = parser.parse_args()
    mutants = json.loads(MANIFEST.read_text())["mutants"]

    if args.check_patches:
        check_patches(mutants)
        return
    if args.only:
        record = prove(next(m for m in mutants if m["id"] == args.only))
        print(json.dumps(record, indent=2))
        return

    base = baseline(args.baseline_runs)
    records = []
    for index, mutant in enumerate(mutants, 1):
        record = prove(mutant)
        records.append(record)
        print(f"[{index}/{len(mutants)}] {record['id']}: {record['outcome']} "
              f"({len(record['testsFailed'])} tests failed, {record['seconds']}s) "
              f"{record.get('raceTrials', '')}", flush=True)
    shutil.rmtree(WORK, ignore_errors=True)
    OUT.parent.mkdir(exist_ok=True)
    OUT.write_text(json.dumps({
        "command": "python3 scripts/prove_mutants.py",
        "finishedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "platform": f"{platform.system()} {platform.machine()}",
        "baseline": base,
        "summary": {
            "mutants": len(records),
            "killed": sum(r["outcome"] == "killed" for r in records),
            "survivedAsExpected": sum(r["outcome"] == "survived" and r["expected"] == "survives"
                                      for r in records),
            "unexpected": [r["id"] for r in records
                           if (r["outcome"] != "killed" and r["expected"] == "killed")
                           or (r["outcome"] != "survived" and r["expected"] == "survives")],
        },
        "mutants": records,
    }, indent=2) + "\n")
    print(f"wrote {OUT.relative_to(REPO)}")


if __name__ == "__main__":
    main()
