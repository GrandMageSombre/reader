"""命令行入口。

    python -m tools.spider.cli qidian-catalog --pages 2
    python -m tools.spider.cli qidian-chapters --limit 3
    python -m tools.spider.cli qidian-content --limit 40
    python -m tools.spider.cli fanqie-all
    python -m tools.spider.cli stats
    python -m tools.spider.cli export --source qidian
    python -m tools.spider.cli serve --port 8765
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
import time

from . import config, export, fanqie, fontfix, qidian, serve, store


def _fmt(counts: dict) -> str:
    return "，".join(f"{k}={v}" for k, v in counts.items())


def cmd_qidian_catalog(a) -> None:
    t0 = time.time()
    counts = qidian.sync_catalog(limit_filters=a.filters, pages=a.pages,
                                 max_books=a.max_books)
    print(f"完成：{_fmt(counts)}，用时 {time.time() - t0:.1f}s")


def cmd_qidian_chapters(a) -> None:
    print(_fmt(qidian.sync_chapters(limit=a.limit)))


def cmd_qidian_content(a) -> None:
    print(_fmt(qidian.sync_contents(limit=a.limit, include_vip=not a.skip_vip,
                                    cooldown=a.cooldown)))


def cmd_fanqie_catalog(a) -> None:
    print(_fmt(fanqie.sync_catalog()))


def cmd_fanqie_chapters(a) -> None:
    print(_fmt(fanqie.sync_chapters(limit=a.limit)))


def cmd_fanqie_content(a) -> None:
    print(_fmt(fanqie.sync_contents(limit=a.limit, include_vip=not a.skip_vip,
                                    cooldown=a.cooldown)))


def cmd_fanqie_all(a) -> None:
    print("书目:", _fmt(fanqie.sync_catalog()))
    print("目录:", _fmt(fanqie.sync_chapters()))
    print("正文:", _fmt(fanqie.sync_contents(limit=a.limit)))


def cmd_stats(a) -> None:
    with store.connect() as conn:
        rows = store.stats(conn)
        print(f"数据库：{config.DB_PATH}")
        for r in rows:
            print(f"  {r['source']}: 书 {r['books']}，章 {r['chapters'] or 0}，正文 {r['contents']}")
        total = conn.execute("SELECT COUNT(*) c FROM contents WHERE ok=1").fetchone()["c"]
        print(f"  正文合计 {total} 章")
        du = conn.execute("SELECT SUM(chars) c FROM contents").fetchone()["c"] or 0
        print(f"  正文字数 {du:,}")


def cmd_export(a) -> None:
    paths = export.export_all(a.source, config.EXPORT_DIR)
    idx = export.write_library_json()
    print(f"导出 {len(paths)} 本 → {config.EXPORT_DIR}")
    print(f"索引 → {idx}")


def cmd_fonts(a) -> None:
    table = fontfix.build_table(force=a.force)
    print(f"字形表 {len(table)} 条 → {fontfix.TABLE_PATH}")


def cmd_serve(a) -> None:
    serve.serve(port=a.port, host=a.host)


def cmd_demo(a) -> None:
    """端到端小样：一本起点书 + 一本番茄书，验证三层都通。"""
    print("== 起点：目录 + 正文")
    with store.connect() as conn:
        rows = store.books(conn, "qidian", limit=1)
    if not rows:
        qidian.sync_catalog(limit_filters=1, pages=1, max_books=1)
        with store.connect() as conn:
            rows = store.books(conn, "qidian", limit=1)
    if rows:
        book = rows[0]
        info, chapters = qidian.Qidian().catalog(book["native_id"])
        print(f"  {info['title'] or book['title']}：{len(chapters)} 章")
        qd = qidian.Qidian()
        for ch in chapters[:2]:
            text, preview, note = qd.content(ch)
            print(f"   {ch['title'][:20]} → {len(text)} 字 {'(试读)' if preview else ''}")

    print("== 番茄：目录 + 正文（字体反混淆）")
    fq = fanqie.Fanqie()
    books = fq.rank_books()
    if books:
        b = books[0]
        chs = fq.directory(b["bookId"])
        print(f"  {b['bookName']}：{len(chs)} 章")
        text, title, locked, note = fq.content(chs[0]["itemId"])
        print(f"   {title}: {len(text)} 字 | '{text[:40]}' | {note}")


def cmd_cookie(a) -> None:
    """把你浏览器里过完验证码的 cookie 导进爬虫（风控期最省事的办法）。"""
    from .net import Session

    base = config.QIDIAN_BASE if a.source == "qidian" else config.FANQIE_BASE
    sess = Session(a.source, base)
    text = Path(a.file).read_text("utf-8") if a.file else a.header
    if not text:
        raise SystemExit("要么 --header 'k=v; k=v'，要么 --file cookies.json")
    n = sess.import_cookies(text)
    print(f"已导入 {n} 个 cookie → {sess.jar_path}")
    status, body = sess.get(base + ("/all/" if a.source == "qidian" else "/"),
                            allow_status=(200, 202, 403))
    print(f"验证：HTTP {status}，{len(body)} 字节")


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="python -m tools.spider.cli", description="墨读全站爬虫")
    p.add_argument("--delay", type=float, default=None,
                   help="请求间隔（秒），默认 0.35；番茄连续抓建议 1.0 以上")
    sub = p.add_subparsers(dest="cmd", required=True)

    qc = sub.add_parser("qidian-catalog", help="枚举起点书库（分类 × 分页）")
    qc.add_argument("--pages", type=int, default=None, help="每个筛选抓几页（默认到站点上限 50）")
    qc.add_argument("--filters", type=int, default=None, help="只抓前 N 个筛选")
    qc.add_argument("--max-books", type=int, default=None)
    qc.set_defaults(func=cmd_qidian_catalog)

    qch = sub.add_parser("qidian-chapters", help="抓起点目录")
    qch.add_argument("--limit", type=int, default=None)
    qch.set_defaults(func=cmd_qidian_chapters)

    qct = sub.add_parser("qidian-content", help="抓起点正文")
    qct.add_argument("--limit", type=int, default=50)
    qct.add_argument("--skip-vip", action="store_true", help="跳过 VIP 章（只存免费章）")
    qct.add_argument("--cooldown", type=int, default=120, help="被 403 风控时歇多久（秒）")
    qct.set_defaults(func=cmd_qidian_content)

    fc = sub.add_parser("fanqie-catalog", help="抓番茄榜单书池")
    fc.set_defaults(func=cmd_fanqie_catalog)

    fch = sub.add_parser("fanqie-chapters", help="抓番茄目录")
    fch.add_argument("--limit", type=int, default=None)
    fch.set_defaults(func=cmd_fanqie_chapters)

    fct = sub.add_parser("fanqie-content", help="抓番茄正文（自动还原字体混淆）")
    fct.add_argument("--limit", type=int, default=50)
    fct.add_argument("--skip-vip", action="store_true")
    fct.add_argument("--cooldown", type=int, default=120, help="被风控时歇多久（秒）")
    fct.set_defaults(func=cmd_fanqie_content)

    fa = sub.add_parser("fanqie-all", help="番茄一条龙")
    fa.add_argument("--limit", type=int, default=50)
    fa.set_defaults(func=cmd_fanqie_all)

    st = sub.add_parser("stats", help="看库里有什么")
    st.set_defaults(func=cmd_stats)

    ex = sub.add_parser("export", help="导出 TXT + 索引 JSON")
    ex.add_argument("--source", default=None, choices=["qidian", "fanqie"])
    ex.set_defaults(func=cmd_export)

    fo = sub.add_parser("fonts", help="构建字体反混淆表")
    fo.add_argument("--force", action="store_true")
    fo.set_defaults(func=cmd_fonts)

    sv = sub.add_parser("serve", help="启动本地接口给阅读器用")
    sv.add_argument("--port", type=int, default=8765)
    sv.add_argument("--host", default="0.0.0.0")
    sv.set_defaults(func=cmd_serve)

    dm = sub.add_parser("demo", help="端到端小样验证")
    dm.set_defaults(func=cmd_demo)

    ck = sub.add_parser("cookie", help="导入浏览器 cookie（风控期用）")
    ck.add_argument("--source", default="fanqie", choices=["qidian", "fanqie"])
    ck.add_argument("--header", default="", help="直接贴 Cookie 头：'k1=v1; k2=v2'")
    ck.add_argument("--file", default="", help="从文件读（cookie 头或浏览器导出的 JSON）")
    ck.set_defaults(func=cmd_cookie)
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if getattr(args, "delay", None):
        config.DELAY = float(args.delay)
    args.func(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
