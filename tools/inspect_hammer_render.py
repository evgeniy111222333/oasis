#!/usr/bin/env python3
"""Проверяет метаданные игрового рендера и собирает контактный лист PNG."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


def default_root() -> Path:
    appdata = Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming"))
    return appdata / ".eclipse-rp" / "eclipse-debug" / "hammer-render"


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session", nargs="?", type=Path)
    parser.add_argument("--root", type=Path, default=default_root())
    parser.add_argument("--columns", type=int, default=3)
    parser.add_argument("--thumb-width", type=int, default=640)
    args = parser.parse_args()

    if args.session is None:
        latest = read_json(args.root / "latest.json")
        session = Path(latest["directory"])
    else:
        session = args.session
    manifest = read_json(session / "session.json")
    images = sorted(session.glob("[0-9][0-9]-*.png"))
    if manifest.get("state") != "complete":
        raise SystemExit(f"Сессия не завершена: {manifest.get('state')}")
    if len(images) != manifest.get("captured"):
        raise SystemExit(f"Число PNG ({len(images)}) не совпадает с manifest ({manifest.get('captured')})")

    reports = [read_json(path.with_suffix(".json")) for path in images]
    collisions = [report["scene"] for report in reports
                  if report.get("headIntersectsPlayerHead") or report.get("headIntersectsPlayerTorso")]
    clamped = [report["scene"] for report in reports
               if report.get("mainClampDistance", 0) > 0.03 or report.get("offhandClampDistance", 0) > 0.03]

    columns = max(1, args.columns)
    label_height = 34
    prepared: list[tuple[Image.Image, str]] = []
    for path, report in zip(images, reports):
        image = Image.open(path).convert("RGB")
        height = round(image.height * args.thumb_width / image.width)
        image.thumbnail((args.thumb_width, height), Image.Resampling.LANCZOS)
        label = (f"{report['scene']}  phase={report['phase']}  "
                 f"head={report.get('headIntersectsPlayerHead', False)}  "
                 f"clamp={report.get('offhandClampDistance', 0):.3f}")
        prepared.append((image.copy(), label))
        image.close()

    rows = (len(prepared) + columns - 1) // columns
    cell_width = args.thumb_width
    cell_height = max(image.height for image, _ in prepared) + label_height
    sheet = Image.new("RGB", (cell_width * columns, cell_height * rows), (18, 20, 22))
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default()
    for index, (image, label) in enumerate(prepared):
        x = (index % columns) * cell_width
        y = (index // columns) * cell_height
        sheet.paste(image, (x, y))
        draw.text((x + 8, y + image.height + 9), label, fill=(232, 235, 238), font=font)
    output = session / "contact-sheet.png"
    sheet.save(output)
    print(json.dumps({
        "session": str(session),
        "state": manifest.get("state"),
        "images": len(images),
        "collisions": collisions,
        "clamped": clamped,
        "contactSheet": str(output),
    }, ensure_ascii=False, indent=2))
    return 1 if collisions or clamped else 0


if __name__ == "__main__":
    raise SystemExit(main())
