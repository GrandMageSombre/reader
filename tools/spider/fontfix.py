"""字体反混淆（番茄正文）。

番茄把正文字形换成私有区码位（U+E000–U+F8FF），靠 @font-face 下发一份子集字体
渲染。这份子集的字形轮廓与 **SourceHanSansSC-Normal（思源黑体 Normal，2.002R）**
完全一致——所以只要拿同一版字体建一张「轮廓哈希 → 真字」表，就能把私有区码位
换回真字。实测 362/362 字形精确命中。

表只建一次，缓存在 tools/spider/assets/glyph_table.json。
"""
from __future__ import annotations

import gzip  # noqa: F401  (保留：pip 环境下 brotli 缺失时的兜底提示)
import hashlib
import json
import threading
from pathlib import Path

from fontTools.pens.recordingPen import RecordingPen
from fontTools.ttLib import TTFont

from . import config

_lock = threading.Lock()
_table: dict[str, str] | None = None
_font_cache: dict[str, dict[str, str]] = {}

TABLE_PATH = config.ASSET_DIR / "glyph_table.json"
REF_FONT = config.ASSET_DIR / config.REF_FONT_NAME

# 表结构变化时递增，旧缓存自动失效
TABLE_VERSION = 2


def _outline_key(glyph_set, name: str) -> str:
    """字形轮廓签名：指令 + 取整后的坐标，与字体文件无关的确定性字符串。"""
    pen = RecordingPen()
    try:
        glyph_set[name].draw(pen)
    except Exception:  # noqa: BLE001
        return ""
    out: list[str] = []
    for op, args in pen.value:
        out.append(op)
        for p in args:
            if isinstance(p, tuple) and len(p) == 2:
                out.append(f"{round(p[0])},{round(p[1])}")
    return "|".join(out)


def _md5(s: str) -> str:
    return hashlib.md5(s.encode("utf-8")).hexdigest()


def ensure_ref_font() -> Path:
    """下载参考字体（只下一次）。"""
    if REF_FONT.exists() and REF_FONT.stat().st_size > 1_000_000:
        return REF_FONT
    import requests

    last = None
    for url in config.REF_FONT_URLS:
        try:
            r = requests.get(url, timeout=300,
                             headers={"User-Agent": config.UA_PC})
            if r.status_code == 200 and len(r.content) > 1_000_000:
                REF_FONT.write_bytes(r.content)
                return REF_FONT
            last = f"{url} -> HTTP {r.status_code}, {len(r.content)} bytes"
        except Exception as exc:  # noqa: BLE001
            last = f"{url} -> {exc}"
    raise RuntimeError(
        "参考字体下载失败，请手动下载 SourceHanSansSC-Normal.otf(2.002R) 放到 "
        f"{REF_FONT}。最后一次错误：{last}")


def _char_priority(code: int) -> int:
    """同一轮廓可能对应多个码位（康熙部首 U+2F00–U+2FDF 与汉字同形）。
    取真字优先：基本区汉字 > 扩展A > 标点 > 全角 > 部首/兼容区。"""
    if 0x4E00 <= code <= 0x9FFF:
        return 100
    if 0x3400 <= code <= 0x4DBF:
        return 90
    if code < 0x80:
        return 80
    if 0x3000 <= code <= 0x303F or 0xFF00 <= code <= 0xFF65:
        return 70
    if 0x20000 <= code <= 0x3FFFF:
        return 60
    if 0x2E80 <= code <= 0x2FDF:
        return 10
    if 0xF900 <= code <= 0xFAFF:
        return 20
    return 50


def build_table(force: bool = False, verbose: bool = True) -> dict[str, str]:
    """轮廓哈希 → 真字。全字符集（44k+），构建一次后缓存。"""
    global _table
    with _lock:
        if _table is not None and not force:
            return _table
        if TABLE_PATH.exists() and not force:
            raw = json.loads(TABLE_PATH.read_text("utf-8"))
            if raw.get("__version__") == TABLE_VERSION:
                _table = raw["table"]
                if verbose:
                    print(f"[fontfix] 载入字形表 {len(_table)} 条")
                return _table

        font_path = ensure_ref_font()
        if verbose:
            print(f"[fontfix] 构建字形表（{font_path.name}，约 1 分钟）…")
        font = TTFont(str(font_path))
        gs = font.getGlyphSet()
        best: dict[str, tuple[int, str]] = {}
        for code, name in font.getBestCmap().items():
            key = _outline_key(gs, name)
            if not key:
                continue
            h = _md5(key)
            pri = _char_priority(code)
            cur = best.get(h)
            if cur is None or pri > cur[0]:
                best[h] = (pri, chr(code))
        table = {h: ch for h, (_, ch) in best.items()}
        TABLE_PATH.write_text(json.dumps({"__version__": TABLE_VERSION, "table": table},
                                         ensure_ascii=False), "utf-8")
        _table = table
        if verbose:
            print(f"[fontfix] 字形表 {len(table)} 条 → {TABLE_PATH}")
        return table


def font_map(font_bytes: bytes, cache_key: str = "") -> dict[str, str]:
    """混淆字体 → {私有区字符: 真字}。"""
    if cache_key and cache_key in _font_cache:
        return _font_cache[cache_key]
    table = build_table(verbose=False)
    import io

    font = TTFont(io.BytesIO(font_bytes))
    gs = font.getGlyphSet()
    mapping: dict[str, str] = {}
    for code, name in font.getBestCmap().items():
        real = table.get(_md5(_outline_key(gs, name)))
        if real:
            mapping[chr(code)] = real
    if cache_key:
        _font_cache[cache_key] = mapping
    return mapping


def decode(text: str, font_bytes: bytes, cache_key: str = "") -> str:
    mapping = font_map(font_bytes, cache_key)
    return "".join(mapping.get(ch, ch) for ch in text)


def obfuscated_ratio(text: str) -> float:
    pua = sum(1 for ch in text if 0xE000 <= ord(ch) <= 0xF8FF)
    total = sum(1 for ch in text if not ch.isspace())
    return pua / total if total else 0.0
