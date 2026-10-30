"""导出：单本 TXT / 整库 TXT / 目录级 JSON。"""
from __future__ import annotations

import json
from pathlib import Path

from . import config, store


def book_txt(conn, key: str) -> str:
    b = conn.execute("SELECT * FROM books WHERE key=?", (key,)).fetchone()
    if not b:
        return ""
    lines = [b["title"], f"作者：{b['author']}", f"来源：{b['source']}",
             f"分类：{b['category']}", "", "简介：", b["intro"] or "", "", "=" * 40, ""]
    for ch in conn.execute("SELECT * FROM chapters WHERE key=? ORDER BY idx", (key,)):
        body = conn.execute("SELECT text, note FROM contents WHERE key=? AND idx=? AND ok=1",
                            (key, ch["idx"])).fetchone()
        lines.append(ch["title"])
        if body and body["text"]:
            lines.append(body["text"])
            if body["note"] == "vip_preview":
                lines.append("（本章为站方提供的试读片段）")
        else:
            lines.append("（未抓取）")
        lines.append("")
    return "\n".join(lines)


def export_book(conn, key: str, out_dir: Path | None = None) -> Path:
    out_dir = out_dir or config.EXPORT_DIR
    out_dir.mkdir(parents=True, exist_ok=True)
    b = conn.execute("SELECT title, author FROM books WHERE key=?", (key,)).fetchone()
    safe = "".join(c for c in (b["title"] if b else key) if c not in '\\/:*?"<>|').strip()
    path = out_dir / f"{safe or key}.txt"
    path.write_text(book_txt(conn, key), "utf-8")
    return path


def export_all(source: str | None = None, out_dir: Path | None = None) -> list[Path]:
    out_dir = out_dir or config.EXPORT_DIR
    out: list[Path] = []
    with store.connect() as conn:
        rows = store.books(conn, source)
        for row in rows:
            has = conn.execute("SELECT COUNT(*) c FROM contents WHERE key=? AND ok=1",
                               (row["key"],)).fetchone()["c"]
            if not has:
                continue
            out.append(export_book(conn, row["key"], out_dir))
    return out


def library_json(conn) -> dict:
    """给阅读器/其它端用的整库索引（不含正文）。"""
    books = []
    for b in conn.execute("SELECT * FROM books ORDER BY source, title"):
        ch = conn.execute("SELECT COUNT(*) c FROM chapters WHERE key=?", (b["key"],)).fetchone()["c"]
        got = conn.execute("SELECT COUNT(*) c FROM contents WHERE key=? AND ok=1",
                           (b["key"],)).fetchone()["c"]
        books.append({
            "id": b["key"], "source": b["source"], "title": b["title"],
            "author": b["author"], "cover": b["cover"], "intro": b["intro"],
            "category": b["category"], "status": b["status"], "words": b["words"],
            "chapters": ch, "cached": got,
        })
    return {"count": len(books), "books": books}


def write_library_json(path: Path | None = None) -> Path:
    path = path or (config.EXPORT_DIR / "library.json")
    with store.connect() as conn:
        path.write_text(json.dumps(library_json(conn), ensure_ascii=False, indent=1), "utf-8")
    return path
