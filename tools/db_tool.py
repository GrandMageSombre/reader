"""本地库维护小工具（清正文 / 抽样看正文 / 看一本书的抓取状态）。

    python -m tools.db_tool sample --source fanqie
    python -m tools.db_tool book --source qidian
    python -m tools.db_tool clear --source fanqie      # 只清正文，目录保留
"""
from __future__ import annotations

import argparse

from tools.spider import store


def cmd_sample(a) -> None:
    with store.connect() as conn:
        row = conn.execute(
            "SELECT t.*, b.title bt FROM contents t JOIN books b ON b.key=t.key "
            "WHERE t.key LIKE ? AND t.ok=1 ORDER BY t.ts DESC LIMIT 1",
            (a.source + ":%",)).fetchone()
        if not row:
            print("还没有正文，先跑一次 content")
            return
        print(f"== {a.source} 《{row['bt']}》 #{row['idx']} {row['chars']} 字 "
              f"note={row['note']!r}")
        print(row["text"][:a.chars])


def cmd_book(a) -> None:
    with store.connect() as conn:
        rows = conn.execute(
            "SELECT b.key, b.title, b.chapter_count, "
            "  (SELECT COUNT(*) FROM contents t WHERE t.key=b.key AND t.ok=1) cached, "
            "  (SELECT COUNT(*) FROM contents t WHERE t.key=b.key AND t.note='vip_preview') preview "
            "FROM books b WHERE b.source=? ORDER BY cached DESC, b.chapter_count DESC LIMIT ?",
            (a.source, a.limit)).fetchall()
        for r in rows:
            print(f"{r['title'][:24]:24s} 章 {r['chapter_count']:5d} "
                  f"正文 {r['cached']:4d} 其中试读 {r['preview']:3d}")


def cmd_clear(a) -> None:
    with store.connect() as conn:
        n = conn.execute("DELETE FROM contents WHERE key LIKE ?", (a.source + ":%",)).rowcount
    print(f"清掉 {a.source} 正文 {n} 条（目录保留，重跑 content 会重新抓）")


def build() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="python -m tools.db_tool")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("sample", help="看一段抓到的正文")
    s.add_argument("--source", default="fanqie", choices=["qidian", "fanqie"])
    s.add_argument("--chars", type=int, default=320)
    s.set_defaults(func=cmd_sample)

    b = sub.add_parser("book", help="按书统计抓取进度")
    b.add_argument("--source", default="qidian", choices=["qidian", "fanqie"])
    b.add_argument("--limit", type=int, default=15)
    b.set_defaults(func=cmd_book)

    c = sub.add_parser("clear", help="清正文（目录保留）")
    c.add_argument("--source", default="fanqie", choices=["qidian", "fanqie"])
    c.set_defaults(func=cmd_clear)
    return p


def main() -> None:
    args = build().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
