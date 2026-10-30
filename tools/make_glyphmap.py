"""生成手机端番茄字形表（PUA 码位 → 真字）。

番茄把一批常用字换成私有区码位，靠 `@font-face` 的子集字体渲染。实测这份字体
**同一版本全站通用**（一份字体 = 一套替换表），所以手机端只要内置一张表，
不用解析 woff2。

用法：

    python -m tools.make_glyphmap                       # 用 data/cache/fonts 里最新的字体
    python -m tools.make_glyphmap --font path.woff2     # 指定字体
    python -m tools.make_glyphmap --out app/src/main/assets/fanqie_glyphs.json

前置：`python -m tools.spider.cli fonts` 已经建好轮廓哈希表
（`tools/spider/assets/glyph_table.json`），或者让它自动建。
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from fontTools.pens.recordingPen import RecordingPen
from fontTools.ttLib import TTFont

from tools.spider import config, fontfix

DEFAULT_OUT = config.ROOT / "app/src/main/assets/fanqie_glyphs.json"
FONT_DIR = config.CACHE_DIR / "fonts"


def outline_key(glyph_set, name: str) -> str:
    pen = RecordingPen()
    glyph_set[name].draw(pen)
    out = []
    for op, args in pen.value:
        out.append(op)
        for p in args:
            if isinstance(p, tuple) and len(p) == 2:
                out.append(f"{round(p[0])},{round(p[1])}")
    return "|".join(out)


def newest_font() -> Path:
    fonts = sorted(FONT_DIR.glob("*.woff2"), key=lambda p: p.stat().st_mtime, reverse=True)
    if not fonts:
        raise SystemExit(
            f"{FONT_DIR} 里没有字体。先跑一次抓取（会缓存当章的 woff2），"
            "或用 --font 指定一个番茄的混淆字体文件。")
    return fonts[0]


def main() -> None:
    ap = argparse.ArgumentParser(prog="python -m tools.make_glyphmap")
    ap.add_argument("--font", default="", help="番茄混淆字体（woff2），默认取缓存里最新的")
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--table", default=str(config.ASSET_DIR / "glyph_table.json"),
                    help="轮廓哈希表（由 fontfix 生成）")
    args = ap.parse_args()

    font_path = Path(args.font) if args.font else newest_font()
    table_path = Path(args.table)
    if not table_path.exists():
        print("轮廓哈希表不存在，先构建…")
        fontfix.build_table()

    table = json.loads(table_path.read_text("utf-8"))["table"]
    font = TTFont(str(font_path))
    gs = font.getGlyphSet()
    mapping = {}
    for code, name in font.getBestCmap().items():
        real = table.get(hashlib.md5(outline_key(gs, name).encode()).hexdigest())
        if real:
            mapping[f"{code:04x}"] = real
    total = len(font.getBestCmap())
    print(f"字体 {font_path.name}: {font['name'].getDebugName(1)}，"
          f"{len(mapping)}/{total} 个码位已还原")

    payload = {
        "note": "番茄正文私有区码位 → 真字（由 tools/make_glyphmap.py 生成）",
        "version": 1,
        "fonts": {font_path.stem: mapping, "default": mapping},
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), "utf-8")
    print(f"写出 {out}（{out.stat().st_size} 字节）")


if __name__ == "__main__":
    main()
