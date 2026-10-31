"""全量书目枚举（起点 / 番茄）。

「全部书籍信息」在站点侧有两种口径：

- **起点**：`/all/` 的筛选项是有限的（实测 137 个：15 个一级 + 122 个二级），
  每个筛选最多 50 页 × 20 本 = 1000 本。所以「全量」= 137 × 1000 = 13.7 万本上限，
  去重后就是这个站点愿意在一次列表里给出的全部书。
- **番茄**：web 端没有「按分类列书」也没有搜索接口，只有三档榜单（26 本）；
  要更多只能走 App 接口，而那是签名接口（见 `tools/spider/sign/`）。

用法：

    python -m tools.spider.catalog_full --source qidian --pages 3      # 小样
    python -m tools.spider.catalog_full --source qidian                # 全量（会跑很久）
    python -m tools.spider.catalog_full --source fanqie                # 榜单 26 本

断点续跑：每抓完一页就把进度写进 `state` 表；重跑会跳过已完成页。
"""
from __future__ import annotations

import argparse
import json
import time

from . import config, store
from .fanqie import Fanqie
from .qidian import Qidian


def _state_key(source: str, name: str) -> str:
    return f"catalog:{source}:{name}"


def collect_qidian(pages: int | None, max_books: int | None, only_filters: int | None,
                   log=print) -> dict:
    qd = Qidian()
    qd.ensure_access()
    filters = qd.filters()
    use = filters[:only_filters] if only_filters else filters
    log(f"[catalog] 起点筛选项 {len(filters)} 个，本次处理 {len(use)} 个")

    stats = {"filters": len(filters), "processed": 0, "pages": 0,
             "books_seen": 0, "books_new": 0}
    stop = False
    for i, f in enumerate(use, 1):
        with store.connect() as conn:
            done_page = int(store.get_state(conn, _state_key("qidian", f["url"]), "0") or 0)
        page = done_page + 1
        pagemax = 50
        while page <= min(pagemax, pages or config.QIDIAN_PAGE_MAX):
            books, pm = qd.list_page(f["url"], page)
            if pm:
                pagemax = pm
            if not books:
                break
            with store.connect() as conn:
                for b in books:
                    key = f"qidian:{b.bid}"
                    stats["books_seen"] += 1
                    if store.book_exists(conn, key):
                        continue
                    store.upsert_book(
                        conn, key=key, source="qidian", native_id=b.bid,
                        title=b.title, author=b.author, cover=b.cover, intro=b.intro,
                        category=b.category or f["name"], status=b.status,
                        words=b.words, updated=b.updated, url=b.url)
                    stats["books_new"] += 1
                store.set_state(conn, _state_key("qidian", f["url"]), str(page))
            stats["pages"] += 1
            if page % 5 == 0 or page == 1:
                log(f"[catalog] {i}/{len(use)} {f['name']} 第 {page}/{pagemax} 页 "
                    f"→ 新书 {stats['books_new']}")
            if max_books and stats["books_new"] >= max_books:
                stop = True
                break
            page += 1
        stats["processed"] += 1
        if stop:
            break
    with store.connect() as conn:
        total = conn.execute("SELECT COUNT(*) c FROM books WHERE source='qidian'").fetchone()["c"]
    stats["books_in_db"] = total
    return stats


def collect_fanqie(log=print) -> dict:
    fq = Fanqie()
    books = fq.rank_books()
    new = 0
    for b in books:
        key = f"fanqie:{b['bookId']}"
        info = {}
        try:
            info = fq.book_info(b["bookId"])
        except Exception:  # noqa: BLE001
            pass
        with store.connect() as conn:
            existed = store.book_exists(conn, key)
            store.upsert_book(
                conn, key=key, source="fanqie", native_id=b["bookId"],
                title=info.get("bookName") or b["bookName"],
                author=info.get("authorName") or b["author"],
                cover=info.get("thumbUrl") or b["thumbUri"],
                intro=info.get("abstract") or b["abstract"],
                category=info.get("category", ""),
                status={1: "连载", 2: "完结"}.get(info.get("creationStatus"), ""),
                words=int(info.get("wordNumber") or 0),
                url=f"{config.FANQIE_BASE}/page/{b['bookId']}")
        if not existed:
            new += 1
    with store.connect() as conn:
        total = conn.execute("SELECT COUNT(*) c FROM books WHERE source='fanqie'").fetchone()["c"]
    log(f"[catalog] 番茄榜单 {len(books)} 本（新 {new}），库里共 {total} 本")
    return {"rank_books": len(books), "new": new, "books_in_db": total}


def main() -> None:
    ap = argparse.ArgumentParser(prog="python -m tools.spider.catalog_full")
    ap.add_argument("--source", default="qidian", choices=["qidian", "fanqie"])
    ap.add_argument("--pages", type=int, default=None, help="每个筛选抓几页")
    ap.add_argument("--filters", type=int, default=None, help="只处理前 N 个筛选")
    ap.add_argument("--max-books", type=int, default=None, help="抓够 N 本新书就停")
    ap.add_argument("--summary", default="", help="把统计写到这个 json 文件")
    args = ap.parse_args()

    t0 = time.time()
    stats = (collect_qidian(args.pages, args.max_books, args.filters)
             if args.source == "qidian" else collect_fanqie())
    stats["seconds"] = round(time.time() - t0, 1)
    print(json.dumps(stats, ensure_ascii=False, indent=1))
    if args.summary:
        with open(args.summary, "w", encoding="utf-8") as fh:
            json.dump(stats, fh, ensure_ascii=False, indent=1)
        print("统计已写入", args.summary)


if __name__ == "__main__":
    main()
