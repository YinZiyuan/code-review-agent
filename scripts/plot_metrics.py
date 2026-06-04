#!/usr/bin/env python3
"""Generate evaluation metric docs from eval/reports/*.json."""

from __future__ import annotations

import argparse
import html
import json
from pathlib import Path


METRICS = ("recall", "precision", "fp_rate", "severity_accuracy", "avg_latency_ms", "tool_success_rate")
RATE_METRICS = {"recall", "precision", "fp_rate", "severity_accuracy", "tool_success_rate"}


def load_reports(report_dir: Path) -> list[dict]:
    reports = []
    for path in sorted(report_dir.glob("*.json")):
        if path.name.endswith("-historical.json"):
            kind = "historical"
        else:
            kind = "strict" if path.stem in {"v0", "v1", "v2", "v3", "v3.1-tuned"} else "legacy"
        data = json.loads(path.read_text())
        reports.append({"path": path, "kind": kind, "data": data})
    return reports


def sample_count(report: dict) -> int:
    data = report["data"]
    per_sample = data.get("per_sample", data.get("per_sample_results", []))
    runs = int(data.get("config", {}).get("runs_per_sample") or len(data.get("per_run_metrics", [])) or 1)
    return int(len(per_sample) / max(runs, 1)) if per_sample else 0


def runs(report: dict) -> int:
    data = report["data"]
    return int(data.get("config", {}).get("runs_per_sample") or len(data.get("per_run_metrics", [])) or 1)


def pct(value: float | None) -> str:
    if value is None:
        return "-"
    return f"{value * 100:.1f}%"


def ms(value: float | None) -> str:
    if value is None:
        return "-"
    return f"{value / 1000:.2f}s"


def fmt(metric: str, value: float | None) -> str:
    return ms(value) if metric == "avg_latency_ms" else pct(value)


def row(report: dict) -> list[str]:
    data = report["data"]
    metrics = data.get("metrics", {})
    config = data.get("config", {})
    return [
        data.get("version", report["path"].stem),
        report["path"].name,
        str(sample_count(report)),
        str(runs(report)),
        str(config.get("suite", "-")),
        str(config.get("pipeline", "-")),
        pct(metrics.get("recall")),
        pct(metrics.get("precision")),
        pct(metrics.get("fp_rate")),
        pct(metrics.get("severity_accuracy")),
        ms(metrics.get("avg_latency_ms")),
        pct(metrics.get("tool_success_rate")),
    ]


def markdown_table(headers: list[str], rows: list[list[str]]) -> str:
    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join("---" for _ in headers) + " |",
    ]
    lines.extend("| " + " | ".join(r) + " |" for r in rows)
    return "\n".join(lines)


def metric_points(strict: list[dict], metric: str) -> list[tuple[str, float]]:
    out = []
    for report in strict:
        value = report["data"].get("metrics", {}).get(metric)
        if isinstance(value, (int, float)):
            out.append((report["data"].get("version", report["path"].stem), float(value)))
    return out


def render_svg(strict: list[dict], out: Path) -> None:
    width, height = 820, 360
    left, top, plot_w, plot_h = 80, 40, 680, 250
    colors = {
        "recall": "#2563eb",
        "precision": "#16a34a",
        "fp_rate": "#dc2626",
        "severity_accuracy": "#9333ea",
    }
    series = {m: metric_points(strict, m) for m in colors}
    labels = [p[0] for p in next(iter(series.values()), [])]
    n = max(len(labels), 1)

    def x(i: int) -> float:
        return left + (plot_w * i / max(n - 1, 1))

    def y(v: float) -> float:
        return top + plot_h - (plot_h * max(0.0, min(1.0, v)))

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="white"/>',
        '<text x="24" y="24" font-family="Arial" font-size="18" font-weight="700">Release Metrics</text>',
    ]
    for tick in range(0, 6):
        v = tick / 5
        yy = y(v)
        parts.append(f'<line x1="{left}" y1="{yy:.1f}" x2="{left + plot_w}" y2="{yy:.1f}" stroke="#e5e7eb"/>')
        parts.append(f'<text x="{left - 12}" y="{yy + 4:.1f}" text-anchor="end" font-family="Arial" font-size="11" fill="#4b5563">{int(v * 100)}%</text>')
    parts.append(f'<line x1="{left}" y1="{top}" x2="{left}" y2="{top + plot_h}" stroke="#9ca3af"/>')
    parts.append(f'<line x1="{left}" y1="{top + plot_h}" x2="{left + plot_w}" y2="{top + plot_h}" stroke="#9ca3af"/>')

    for metric, points in series.items():
        if not points:
            continue
        coords = " ".join(f"{x(i):.1f},{y(value):.1f}" for i, (_, value) in enumerate(points))
        color = colors[metric]
        parts.append(f'<polyline fill="none" stroke="{color}" stroke-width="3" points="{coords}"/>')
        for i, (_, value) in enumerate(points):
            parts.append(f'<circle cx="{x(i):.1f}" cy="{y(value):.1f}" r="4" fill="{color}"/>')

    for i, label in enumerate(labels):
        parts.append(f'<text x="{x(i):.1f}" y="{top + plot_h + 24}" text-anchor="middle" font-family="Arial" font-size="12" fill="#111827">{html.escape(label)}</text>')

    legend_x = left
    for metric, color in colors.items():
        parts.append(f'<rect x="{legend_x}" y="330" width="12" height="12" fill="{color}"/>')
        parts.append(f'<text x="{legend_x + 18}" y="341" font-family="Arial" font-size="12" fill="#111827">{metric}</text>')
        legend_x += 170
    parts.append("</svg>")
    out.write_text("\n".join(parts))


def render_markdown(reports: list[dict], svg_path: Path, out: Path) -> None:
    strict = [r for r in reports if r["kind"] == "strict" and sample_count(r) >= 40]
    historical = [r for r in reports if r["kind"] == "historical"]
    legacy = [r for r in reports if r["kind"] == "legacy"]

    headers = [
        "Version", "Report", "Samples", "Runs", "Suite", "Pipeline", "Recall", "Precision",
        "FP rate", "Severity acc.", "Latency", "Tool success",
    ]
    lines = [
        "# Evaluation Metrics",
        "",
        "Generated from `eval/reports/*.json` by `scripts/plot_metrics.py`.",
        "",
        "Strict release charts include only reports with at least 40 samples. Historical reports are shown for context only and are not plotted.",
        "",
        "## Strict release reports",
        "",
        markdown_table(headers, [row(r) for r in strict]) if strict else "_No strict release reports found._",
        "",
        f"![Release metrics]({svg_path.name})",
        "",
        "## Historical context",
        "",
        markdown_table(headers, [row(r) for r in historical]) if historical else "_No historical fallback reports found._",
        "",
        "## Legacy dev reports",
        "",
        markdown_table(headers, [row(r) for r in legacy]) if legacy else "_No legacy dev reports found._",
        "",
        "## W4 tuning note",
        "",
        "`v3.1-tuned` was attempted with severity calibration. One variant passed the no-review-error redline and improved severity accuracy, but recall and precision regressed versus `v3`; a looser variant recovered none of that stability and produced a `COMPILER_ERROR` category parse failure. No `v3.1-tuned` report is accepted or plotted.",
        "",
    ]
    out.write_text("\n".join(lines))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reports", type=Path, default=Path("eval/reports"))
    parser.add_argument("--docs", type=Path, default=Path("docs"))
    args = parser.parse_args()

    reports = load_reports(args.reports)
    strict = [r for r in reports if r["kind"] == "strict" and sample_count(r) >= 40]
    args.docs.mkdir(parents=True, exist_ok=True)
    svg = args.docs / "eval-metrics.svg"
    render_svg(strict, svg)
    render_markdown(reports, svg, args.docs / "eval-metrics.md")


if __name__ == "__main__":
    main()
