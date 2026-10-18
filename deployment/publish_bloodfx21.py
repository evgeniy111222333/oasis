#!/usr/bin/env python3
"""Publish the Blood FX 2.1 release through the compatibility publisher path."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


def main() -> None:
    publisher = (
        Path(__file__).resolve().parents[1]
        / "fabric-server"
        / "deployment"
        / "publish_update.py"
    )
    command = [
        sys.executable,
        str(publisher),
        "--id",
        "20260728-bloodfx21",
        "--title",
        "Blood FX 2.1: новая библиотека пятен крови",
        "--summary",
        (
            "Система Blood FX получила 96 новых пиксельных спрайтов: "
            "24 уникальные формы пятен для слабых, средних и сильных попаданий, "
            "каждая — в четырёх стадиях высыхания."
        ),
        "--note",
        (
            "Выбор формы учитывает силу попадания, материал и положение "
            "поверхности, а также индивидуальный рисунок каждой капли."
        ),
        "--note",
        (
            "Добавлены стадии fresh, settled, drying и dry: пятна естественно "
            "темнеют и высыхают со временем."
        ),
        "--note",
        "Повторное попадание обновляет пятно и возвращает ему свежий вид.",
        "--note",
        (
            "Расширена вариативность летящих капель: теперь используются все "
            "пять доступных вариантов."
        ),
        "--note",
        (
            "Новая библиотека полностью подключена к игровому атласу; прежние "
            "восемь пятен исключены из активного набора."
        ),
        "--button-label",
        "ЗАГРУЗИТЬ ОБНОВЛЕНИЕ",
        "--success-message",
        "Обновление Blood FX 2.1 загружено и установлено.",
    ]
    subprocess.run(command, check=True)


if __name__ == "__main__":
    main()
