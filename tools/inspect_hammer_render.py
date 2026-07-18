#!/usr/bin/env python3
"""Проверяет метаданные игрового рендера и собирает контактный лист PNG."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageStat


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
    collision_fields = (
        "headIntersectsPlayerHead", "headIntersectsPlayerTorso",
        "handleIntersectsPlayerHead", "handleIntersectsPlayerTorso",
        "mainGripIntersectsPlayer", "offhandGripIntersectsPlayer",
    )
    collisions = [report["scene"] for report in reports
                  if any(report.get(field, False) for field in collision_fields)]
    clamped = [report["scene"] for report in reports
               if ((report.get("poseWeight", 1) > 0.98
                    and report.get("mainClampDistance", 0) > 0.03)
                   or (report.get("offhandWeight", 1) > 0.98
                       and report.get("offhandClampDistance", 0) > 0.03))]
    ground_penetrations = [report["scene"] for report in reports
                           if report.get("headGroundClearance", 0) < -0.15]
    missed_contacts = [report["scene"] for report in reports
                       if report.get("phase") == "impact"
                       and abs(report.get("headGroundClearance", 999)) > 0.25]
    unsafe_elbows = [report["scene"] for report in reports
                     if min(report.get("rightElbowAngle", 1), report.get("leftElbowAngle", 1)) < 0.4
                     or max(report.get("rightElbowAngle", 1), report.get("leftElbowAngle", 1)) > 2.50]
    kinematic_spikes = [report["scene"] for report in reports
                        if report.get("headSpeed", 0) > 10.0
                        or report.get("headAcceleration", 0) > 10.0
                        or report.get("headAngularSpeed", 0) > 0.60]
    grip_spikes = [report["scene"] for report in reports
                   if report.get("gripSlideRate", 0) > 1.35
                   or (report.get("elapsedTicks", -1) >= 0
                       and report.get("gripDistance", 99) < 4.40)]
    weak_idle_support = [report["scene"] for report in reports
                         if report.get("phase") == "idle"
                         and (("unsupportedLever" in report
                              and report["unsupportedLever"] > 7.60)
                              or ("leftWristTwist" in report
                                  and report["leftWristTwist"] > -0.45))]
    crossed_stances = []
    gait_mismatches = []
    for report in reports:
        separation = report.get("renderedFootCenterSeparation")
        if separation is None:
            crossed_stances.append(report["scene"])
            continue
        phase = report.get("phase")
        if phase == "idle" and not 5.3 <= separation <= 6.3:
            crossed_stances.append(report["scene"])
        elif phase in ("walk", "run") and not 4.0 <= separation <= 5.2:
            crossed_stances.append(report["scene"])
        elif phase == "draw" and not 4.0 <= separation <= 6.4:
            crossed_stances.append(report["scene"])
        elif phase not in ("idle", "walk", "run", "draw") and separation < 5.2:
            crossed_stances.append(report["scene"])
        if (phase in ("walk", "run")
                and abs(separation - report.get("gaitFootCenterSeparation", separation)) > 0.08):
            gait_mismatches.append(report["scene"])
    diagonal_idle_shafts = [report["scene"] for report in reports
                            if report.get("phase") == "idle"
                            and report.get("shaftTopAngleDegrees", 0) > 3.0]
    missing_rendered_rig = [report["scene"] for report in reports
                            if "renderedMainGripError" not in report]
    stale_rendered_rig = [report["scene"] for report in reports
                          if report.get("renderedRigAgeMs", 0) > 500
                          or ("renderedRigProgress" in report
                              and abs(report.get("renderedRigProgress", 0)
                                      - report.get("progress", 0)) > 0.01)]
    rendered_grip_failures = [report["scene"] for report in reports
                              if ((report.get("poseWeight", 1) > 0.98
                                   and (report.get("renderedMainGripError", 0) > 0.03
                                        or report.get("renderedRightShoulderRootError", 0) > 0.01))
                                  or (report.get("poseWeight", 1) > 0.98
                                      and report.get("offhandWeight", 1) > 0.98
                                      and (report.get("renderedOffhandGripError", 0) > 0.03
                                           or report.get("renderedLeftShoulderRootError", 0) > 0.01)))]
    rendered_hip_failures = [report["scene"] for report in reports
                             if report.get("renderedRightHipRootError", 0) > 0.01
                             or report.get("renderedLeftHipRootError", 0) > 0.01]
    raised_idle = [report["scene"] for report in reports
                   if report.get("phase") == "idle"
                   and (not 8.8 <= report.get("mainGrip", {}).get("y", -99) <= 10.0
                        or not 9.5 <= report.get("offhandGrip", {}).get("y", -99) <= 11.0
                        or report.get("headCenter", {}).get("y", -99) < 10.5
                        or not 4.0 <= report.get("shaftDropAngleDegrees", -99) <= 8.0
                        or not 2.0 <= report.get("visibleHandleTail", -99) <= 2.6
                        or report.get("headGroundClearance", -99) < 4.0
                        or abs(report.get("torsoPitch", 99)) > 0.05
                        or abs(report.get("torsoYaw", 99)) > 0.06
                        or abs(report.get("torsoRoll", 99)) > 0.04)]

    columns = max(1, args.columns)
    label_height = 34
    prepared: list[tuple[Image.Image, str]] = []
    blank_images: list[str] = []
    for path, report in zip(images, reports):
        image = Image.open(path).convert("RGB")
        if max(ImageStat.Stat(image).stddev) < 2.0:
            blank_images.append(report["scene"])
        height = round(image.height * args.thumb_width / image.width)
        image.thumbnail((args.thumb_width, height), Image.Resampling.LANCZOS)
        label = (f"{report['scene']}  phase={report['phase']}  "
                 f"collision={any(report.get(field, False) for field in collision_fields)}  "
                 f"clamp={report.get('offhandClampDistance', 0):.3f}  "
                 f"ground={report.get('headGroundClearance', 0):.2f}  "
                 f"speed={report.get('headSpeed', 0):.2f}  grip={report.get('gripDistance', 0):.2f}  "
                 f"feet={report.get('renderedFootCenterSeparation', 0):.2f}")
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
        "groundPenetrations": ground_penetrations,
        "missedContacts": missed_contacts,
        "unsafeElbows": unsafe_elbows,
        "kinematicSpikes": kinematic_spikes,
        "gripSpikes": grip_spikes,
        "weakIdleSupport": weak_idle_support,
        "crossedStances": crossed_stances,
        "gaitMismatches": gait_mismatches,
        "diagonalIdleShafts": diagonal_idle_shafts,
        "missingRenderedRig": missing_rendered_rig,
        "staleRenderedRig": stale_rendered_rig,
        "renderedGripFailures": rendered_grip_failures,
        "renderedHipFailures": rendered_hip_failures,
        "raisedIdle": raised_idle,
        "blankImages": blank_images,
        "contactSheet": str(output),
    }, ensure_ascii=False, indent=2))
    failed = (collisions or clamped or ground_penetrations or missed_contacts or unsafe_elbows
              or kinematic_spikes or grip_spikes or weak_idle_support
              or crossed_stances or gait_mismatches or diagonal_idle_shafts or missing_rendered_rig
              or stale_rendered_rig or rendered_grip_failures or rendered_hip_failures
              or raised_idle or blank_images)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
