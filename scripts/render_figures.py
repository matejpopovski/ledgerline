#!/usr/bin/env python3
"""Draw the README figures from the result files. Standard library only.

    python3 scripts/render_figures.py

Reads results/mutation-proofs.json and results/lock-strategies*.json, writes
docs/trial-balance.svg, docs/throughput.svg and the Markdown tables in results/.
Numbers in the figures come from those files and nowhere else.
"""
import json
import statistics
from html import escape
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RESULTS = REPO / "results"
DOCS = REPO / "docs"

PAPER = "#f6f8ee"
RULE = "#c9dcc9"
HEAVY_RULE = "#5f8f75"
MARGIN = "#d98c8c"
INK = "#1f2a24"
MUTED = "#55645b"
MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"

STRATEGIES = [
    ("ROW_LOCK", "row lock", "#2a78d6"),
    ("SERIALIZABLE", "serializable + retry", "#eb6834"),
    ("REPEATABLE_READ", "repeatable read + retry", "#1baf7a"),
]
CONTENTION_NAMES = {2: "2 accounts: one hot pair", 32: "32 accounts", 1024: "1024 accounts"}


def text(x, y, content, size=13, fill=INK, anchor="start", weight="normal"):
    return (f'<text x="{x}" y="{y}" font-family="{MONO}" font-size="{size}" fill="{fill}" '
            f'text-anchor="{anchor}" font-weight="{weight}">{escape(str(content))}</text>')


def line(x1, y1, x2, y2, color=RULE, width=1):
    return f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="{width}"/>'


def svg(width, height, body, title):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" '
            f'width="{width}" height="{height}" role="img" aria-label="{escape(title)}">\n'
            f'<title>{escape(title)}</title>\n'
            f'<rect width="{width}" height="{height}" fill="{PAPER}"/>\n'
            + "\n".join(body) + "\n</svg>\n")


def trial_balance():
    """Removed protections on the left, tests that caught the removal on the right."""
    proofs = json.loads((RESULTS / "mutation-proofs.json").read_text())
    order, rows = [], {}
    for mutant in proofs["mutants"]:
        name = mutant["invariant"]
        if name not in rows:
            order.append(name)
            rows[name] = {"removed": 0, "caught": 0, "survived": 0, "failing": set()}
        row = rows[name]
        row["removed"] += 1
        row["caught"] += mutant["outcome"] == "killed"
        row["survived"] += mutant["outcome"] == "survived"
        row["failing"].update(mutant["testsFailed"])

    width, left, row_height = 880, 56, 34
    top = 104
    height = top + row_height * (len(order) + 2) + 58
    columns = {"removed": 560, "caught": 690, "failing": 840}
    body = [line(left - 14, 0, left - 14, height, MARGIN, 1.5)]
    body.append(text(left, 36, "ledgerline: trial balance of protections", 19, weight="bold"))
    body.append(text(left, 60, "Each protection removed from the source, then the full test suite run",
                     13, MUTED))
    header_y = top - 12
    body.append(text(left, header_y, "Invariant", 13, MUTED))
    body.append(text(columns["removed"], header_y, "Removed", 13, MUTED, "end"))
    body.append(text(columns["caught"], header_y, "Caught", 13, MUTED, "end"))
    body.append(text(columns["failing"], header_y, "Tests failed", 13, MUTED, "end"))
    body.append(line(left - 14, top, width, top, HEAVY_RULE, 1.5))

    y = top
    for name in order:
        row = rows[name]
        y += row_height
        body.append(text(left, y - 11, name))
        body.append(text(columns["removed"], y - 11, row["removed"], anchor="end"))
        body.append(text(columns["caught"], y - 11, row["caught"], anchor="end"))
        body.append(text(columns["failing"], y - 11, len(row["failing"]), anchor="end"))
        body.append(line(left - 14, y, width, y))

    y += row_height
    removed = sum(r["removed"] for r in rows.values())
    caught = sum(r["caught"] for r in rows.values())
    survived = sum(r["survived"] for r in rows.values())
    failing = set().union(*(r["failing"] for r in rows.values()))
    body.append(text(left, y - 11, "Total", weight="bold"))
    body.append(text(columns["removed"], y - 11, removed, anchor="end", weight="bold"))
    body.append(text(columns["caught"], y - 11, caught, anchor="end", weight="bold"))
    body.append(text(columns["failing"], y - 11, len(failing), anchor="end", weight="bold"))
    body.append(line(left - 14, y, width, y, HEAVY_RULE, 1.5))
    body.append(line(left - 14, y + 3, width, y + 3, HEAVY_RULE, 1.5))
    survivors = [m["id"] for m in proofs["mutants"] if m["outcome"] == "survived"]
    note = f"Survived, as predicted: {', '.join(survivors)}" if survivors else "No survivors."
    body.append(text(left, y + 28, note, 13, MUTED))
    for x in (columns["removed"] - 92, columns["removed"] + 12, columns["caught"] + 12):
        body.append(line(x, top, x, y, RULE))
    (DOCS / "trial-balance.svg").write_text(
        svg(width, height, body, f"{caught} of {removed} removed protections caught by tests"))


def medians(runs):
    cells = {}
    for run in runs:
        cells.setdefault((run["strategy"], run["accounts"], run["workers"]), []).append(run)
    return cells


def nice_ceiling(value):
    magnitude = 10 ** (len(str(int(value))) - 1)
    for step in (1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10):
        if value <= step * magnitude:
            return int(step * magnitude)
    return int(10 * magnitude)


def throughput():
    data = json.loads((RESULTS / "lock-strategies.json").read_text())
    cells = medians(data["runs"])
    accounts = sorted({key[1] for key in cells})
    workers = sorted({key[2] for key in cells})
    value = {key: statistics.median(r["commitsPerSecond"] for r in rs) for key, rs in cells.items()}
    ceiling = nice_ceiling(max(value.values()))

    panel_w, panel_h, gap, left, top = 250, 250, 34, 96, 118
    width = left + (panel_w + gap) * len(accounts) + 18
    height = top + panel_h + 74
    body = [line(26, 0, 26, height, MARGIN, 1.5)]
    machine = data["machine"]
    body.append(text(44, 36, "Committed entries per second, median of repeats", 19, weight="bold"))
    body.append(text(44, 60, f"{machine['cpu']}, embedded Postgres {data['postgres']['version']}, "
                             f"machine shared with other jobs", 13, MUTED))
    legend_x = 44
    for _, label, color in STRATEGIES:
        body.append(f'<rect x="{legend_x}" y="74" width="12" height="12" rx="2" fill="{color}"/>')
        body.append(text(legend_x + 18, 85, label, 13))
        legend_x += 18 + len(label) * 8 + 30

    for index, count in enumerate(accounts):
        x0 = left + index * (panel_w + gap)
        y_of = lambda v: top + panel_h - panel_h * v / ceiling
        x_of = lambda i: x0 + 14 + (panel_w - 28) * i / (len(workers) - 1)
        for tick in range(0, 5):
            v = ceiling * tick / 4
            body.append(line(x0, y_of(v), x0 + panel_w, y_of(v), HEAVY_RULE if tick == 0 else RULE))
            if index == 0:
                body.append(text(x0 - 8, y_of(v) + 4, f"{int(v):,}", 12, MUTED, "end"))
        for i, w in enumerate(workers):
            body.append(text(x_of(i), top + panel_h + 20, w, 12, MUTED, "middle"))
        body.append(text(x0 + panel_w / 2, top + panel_h + 40, "workers", 12, MUTED, "middle"))
        body.append(text(x0 + panel_w / 2, top - 12, CONTENTION_NAMES.get(count, f"{count} accounts"),
                         14, weight="bold", anchor="middle"))
        for name, _, color in STRATEGIES:
            points = [(x_of(i), y_of(value[(name, count, w)])) for i, w in enumerate(workers)
                      if (name, count, w) in value]
            path = " ".join(f"{x:.1f},{y:.1f}" for x, y in points)
            body.append(f'<polyline points="{path}" fill="none" stroke="{color}" stroke-width="2" '
                        f'stroke-linejoin="round"/>')
            for x, y in points:
                body.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="4" fill="{color}" '
                            f'stroke="{PAPER}" stroke-width="2"/>')
        row_lock = value[("ROW_LOCK", count, workers[-1])]
        body.append(text(x_of(len(workers) - 1), y_of(row_lock) - 12, f"{int(row_lock):,}", 12,
                         anchor="end", weight="bold"))
    (DOCS / "throughput.svg").write_text(
        svg(width, height, body, "Throughput of three lock strategies by contention and workers"))


def spread(values):
    return f"{min(values):,} to {max(values):,}"


def markdown_table(path_in, path_out):
    data = json.loads(path_in.read_text())
    cells = medians(data["runs"])
    lines = ["| Accounts | Workers | Strategy | Commits/s (median) | Range | Retries per commit | "
             "p50 ms | p99 ms | Gave up |",
             "| ---: | ---: | --- | ---: | --- | ---: | ---: | ---: | ---: |"]
    names = {name: label for name, label, _ in STRATEGIES}
    order = {name: i for i, (name, _, _) in enumerate(STRATEGIES)}
    for key in sorted(cells, key=lambda k: (k[1], k[2], order[k[0]])):
        runs = cells[key]
        rate = [r["commitsPerSecond"] for r in runs]
        lines.append(
            f"| {key[1]} | {key[2]} | {names[key[0]]} | {int(statistics.median(rate)):,} | {spread(rate)} | "
            f"{statistics.median(r['retriesPerCommit'] or 0 for r in runs):.3f} | "
            f"{statistics.median(r['p50Millis'] for r in runs):.2f} | "
            f"{statistics.median(r['p99Millis'] for r in runs):.1f} | "
            f"{sum(r['gaveUp'] for r in runs)} |")
    path_out.write_text("\n".join(lines) + "\n")


def proof_table():
    """Every mutant with its outcome, one row each."""
    proofs = json.loads((RESULTS / "mutation-proofs.json").read_text())
    lines = ["| Mutant | Rule | Protection removed | Outcome | Tests failed | Race trials |",
             "| --- | --- | --- | --- | ---: | ---: |"]
    for m in proofs["mutants"]:
        trials = m.get("raceTrials")
        lines.append(f"| `{m['id']}` | {m['invariant']} | {m['protectionRemoved']} | {m['outcome']} | "
                     f"{len(m['testsFailed'])} | "
                     f"{'%d of %d' % (trials['caught'], trials['trials']) if trials else ''} |")
    (RESULTS / "mutation-proofs.md").write_text("\n".join(lines) + "\n")


def main():
    trial_balance()
    proof_table()
    throughput()
    markdown_table(RESULTS / "lock-strategies.json", RESULTS / "lock-strategies.md")
    durable = RESULTS / "lock-strategies-full-flush.json"
    if durable.exists():
        markdown_table(durable, RESULTS / "lock-strategies-full-flush.md")


if __name__ == "__main__":
    main()
