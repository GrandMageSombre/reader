"""编排层：把「书目 → 目录 → 正文（含破解）」串成可断点续跑、可并发、可统计的流水线。

三条硬性纪律（都是踩过坑才写进来的）：

1. **只写主线程的库连接**：并发抓取只负责网络，结果回主线程统一入库，
   避免 SQLite `database is locked`；
2. **试读/空正文不进库**：`ok=0` 且 note 标成 `vip_preview`，下次重跑还会再试；
   只有真正拿到全文（`plan.Kind.OK`）才 `ok=1`；
3. **风控连续命中就收工**：起点 403、番茄验证码中间页都是按 IP 限速的，
   硬刷只会让封得更久；停下来让用户改 `--delay` 或换时间。

并发只在「取正文」这一层开：目录与书目本身就是接口串行的，开了也没收益。
注意 `net._throttle()` 是**全局**限速，所以 `--workers` 提高不了吞吐，
它的价值是让采集站多镜像探测、以及慢请求不互相堵死。
"""
from __future__ import annotations

import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field, asdict

from .. import config, store
from . import plan
from .collectors import CollectorChain

# 连续被风控这么多次就收工（App 侧 Crack.MAX_BLOCKED_STREAK 同一个数）
MAX_BLOCKED_STREAK = 3


@dataclass
class Report:
    source: str = ""
    stage: str = ""
    books_new: int = 0
    books_in_db: int = 0
    catalogs: int = 0
    catalog_fail: int = 0
    chapters: int = 0
    need_content: int = 0
    content_ok: int = 0
    content_fail: int = 0
    blocked: int = 0
    by_path: dict = field(default_factory=dict)
    seconds: float = 0.0
    note: str = ""

    def bump(self, path: str) -> None:
        self.by_path[path] = self.by_path.get(path, 0) + 1

    def as_dict(self) -> dict:
        d = asdict(self)
        d["by_path"] = dict(sorted(self.by_path.items(), key=lambda kv: -kv[1]))
        return d


def _crack_for(source: str, collector: CollectorChain, log):
    """按站点造一个破解器。**番茄必须注入 web 会话**，否则目录与免费章都拿不到。"""
    if source == "qidian":
        from .qidian import QidianCrack

        return QidianCrack(collector=collector, log=log)
    if source == "fanqie":
        from ..fanqie import Fanqie
        from .appclient import AppClient, FanqieApp
        from .fanqie import FanqieCrack

        client = AppClient(log=log)
        return FanqieCrack(web=Fanqie(), app=FanqieApp(client, log=log),
                           collector=collector, log=log)
    raise ValueError(f"未知 source：{source}")


# ---------------------------------------------------------------- 书目

def harvest_books(source: str, limit_books: int | None = None, pages: int = 1,
                  dims=None, seeds=None, log=print) -> Report:
    """枚举书池并入库（已存在的 key 直接跳过 → 天然断点续跑）。"""
    rep = Report(source=source, stage="书目")
    t0 = time.time()
    collector = CollectorChain(log=log)

    if source == "qidian":
        from .qidian import QidianCrack

        crack = QidianCrack(log=log)
        for b in crack.books(pages=pages, max_books=limit_books):
            key = f"qidian:{b['bid']}"
            with store.connect() as conn:
                if store.book_exists(conn, key):
                    continue
                store.upsert_book(
                    conn, key=key, source="qidian", native_id=b["bid"],
                    title=b["title"], author=b["author"], cover=b["cover"],
                    intro=b["intro"], category=b["category"], status=b["status"],
                    words=b["words"], updated=b["updated"], url=b["url"])
                rep.books_new += 1
        rep.note = f"书目（入口 {len(plan.QIDIAN_ENTRIES)} 个）"
    elif source == "fanqie":
        crack = _crack_for(source, collector, log)
        for b in crack.books(dims=dims, seeds=seeds, max_books=limit_books):
            key = f"fanqie:{b['bookId']}"
            with store.connect() as conn:
                if store.book_exists(conn, key):
                    continue
                store.upsert_book(
                    conn, key=key, source="fanqie", native_id=b["bookId"],
                    title=b["bookName"], author=b["author"], cover=b["thumbUri"],
                    intro=b["abstract"], category=b["category"], status=b["status"],
                    words=b["words"], url=f"{config.FANQIE_BASE}/page/{b['bookId']}")
                rep.books_new += 1
                rep.bump(b.get("how", "rank"))
        signer = getattr(getattr(crack, "app", None), "client", None)
        signed = signer is not None and signer.signer.__class__.__name__ != "StubSigner"
        rep.note = f"书目（榜单 + 搜索种子；App 签名{'已配置' if signed else '未配置'}"
        rep.note += "）"
    else:
        raise ValueError(f"未知 source：{source}")

    with store.connect() as conn:
        rep.books_in_db = conn.execute(
            "SELECT COUNT(*) c FROM books WHERE source=?", (source,)).fetchone()["c"]
    rep.seconds = round(time.time() - t0, 1)
    log(f"[{source}] 书目：新增 {rep.books_new}，库里 {rep.books_in_db}，{rep.seconds}s")
    return rep


# ---------------------------------------------------------------- 目录

def harvest_catalog(source: str, limit_books: int | None = None, log=print) -> Report:
    """给还没有目录的书抓目录（`cataloged_at=0`）。

    **空目录不算抓成功**：以前 `replace_chapters([])` 会把书标成「目录已抓 0 章」，
    之后 `missing_catalog` 永远不再选中它 —— 一次签名失败就等于整本丢书。
    """
    rep = Report(source=source, stage="目录")
    t0 = time.time()
    crack = _crack_for(source, CollectorChain(log=log), log)

    with store.connect() as conn:
        todo = store.books(conn, source, missing_catalog=True, limit=limit_books)
    for row in todo:
        chapters: list[dict] = []
        info: dict = {}
        try:
            if source == "qidian":
                info, chapters = crack.catalog(row["native_id"])
            else:
                chapters = crack.catalog(row["native_id"])
        except Exception as exc:  # noqa: BLE001
            rep.catalog_fail += 1
            reason = getattr(crack, "last_error", "") or f"{type(exc).__name__}: {exc}"
            log(f"[{source}] 目录失败《{row['title']}》：{reason}")
            continue
        if not chapters:
            rep.catalog_fail += 1
            log(f"[{source}] 目录为空《{row['title']}》（签名/风控/下架），本轮不标记已抓")
            continue
        title = info.get("title") or row["title"]
        with store.connect() as conn:
            if source == "qidian":
                store.upsert_book(
                    conn, key=row["key"], source="qidian", native_id=row["native_id"],
                    title=title, author=info.get("author") or row["author"],
                    cover=info.get("cover") or row["cover"],
                    intro=info.get("intro") or row["intro"],
                    category=row["category"], status=row["status"], words=row["words"],
                    updated=row["updated"], url=row["url"])
            rows = []
            for i, c in enumerate(chapters):
                if source == "qidian":
                    url = c.get("url") or ""
                else:
                    iid = c.get("itemId", "")
                    url = c.get("url") or f"{config.FANQIE_BASE}/reader/{iid}"
                rows.append((i, c.get("title", ""), url,
                             c.get("cid") or c.get("itemId", ""),
                             int(c.get("vip") or c.get("needPay") or c.get("locked") or 0)))
            store.replace_chapters(conn, row["key"], rows)
        rep.catalogs += 1
        rep.chapters += len(chapters)
        log(f"[{source}] 目录《{title}》→ {len(chapters)} 章")
    rep.seconds = round(time.time() - t0, 1)
    log(f"[{source}] 目录：{rep.catalogs} 本 / {rep.chapters} 章，失败 {rep.catalog_fail}，{rep.seconds}s")
    return rep


# ---------------------------------------------------------------- 正文

def harvest_content(source: str, limit_chapters: int | None = 200, workers: int = 2,
                    include_vip: bool = True, log=print) -> Report:
    """抓正文：免费章直取，付费章按策略链破解。"""
    rep = Report(source=source, stage="正文")
    t0 = time.time()

    with store.connect() as conn:
        todo = store.pending_contents(conn, source, limit_chapters or 10 ** 9,
                                      include_vip=include_vip)
    rep.need_content = len(todo)
    if not todo:
        rep.seconds = round(time.time() - t0, 1)
        log(f"[{source}] 没有待抓的章")
        return rep

    # 按书分组：采集站对齐要「书名 + 整本目录 + 本地已有正文（做指纹校验）」
    by_book: dict[str, list] = {}
    for ch in todo:
        by_book.setdefault(ch["key"], []).append(ch)

    crack = _crack_for(source, CollectorChain(log=log), log)
    # 写库只用一个连接：store.connect() 每次都 executescript(SCHEMA)，
    # 万级章节时那点开销比抓取本身还贵（实测每章 2–4ms）。
    conn = store.connect()
    db = conn.__enter__()
    blocked_streak = 0
    done = 0
    cancelled = False
    try:
        for key, rows in by_book.items():
            if cancelled:
                break
            book = db.execute("SELECT * FROM books WHERE key=?", (key,)).fetchone()
            chapters = [dict(c) for c in store.chapters(db, key)]
            if book is None or not chapters:
                continue
            title = book["title"]
            # 本地已有正文先读进内存：worker 线程不能碰 sqlite 连接（check_same_thread），
            # 而采集站第三道闸（正文指纹）只需要几章样本。
            local_map = {
                r["idx"]: r["text"] for r in db.execute(
                    "SELECT idx, text FROM contents WHERE key=? AND ok=1 AND chars>=500",
                    (key,))
            }

            def work(ch, _title=title, _chapters=chapters, _local=local_map):
                """一个 worker：只做网络与解析，**不碰数据库**（写库留在主线程）。"""
                try:
                    if source == "qidian":
                        got = crack.content({"url": ch["url"], "idx": ch["idx"],
                                             "title": ch["title"], "cid": ch["native_id"]},
                                            book_title=_title, chapters=_chapters,
                                            local_text=lambda i: _local.get(i, ""))
                    else:
                        item = str(ch["native_id"] or plan.fanqie_id(ch["url"]))
                        got = crack.content(item, book_title=_title, chapters=_chapters,
                                            chapter={"idx": ch["idx"], "title": ch["title"],
                                                     "native_id": item},
                                            local_text=lambda i: _local.get(i, ""))
                except Exception as exc:  # noqa: BLE001  单章炸掉不该带走整批
                    got = plan.from_exception(source, "worker", exc)
                return ch, got

            pool = ThreadPoolExecutor(max_workers=max(1, workers))
            try:
                futures = [pool.submit(work, ch) for ch in rows]
                for fut in as_completed(futures):
                    ch, got = fut.result()
                    done += 1
                    if got.kind is plan.Kind.BLOCKED:
                        rep.blocked += 1
                        blocked_streak += 1
                        # 被挡的章也要落一条 ok=0 记录，否则它 ts 一直是 0，
                        # 下次还排在最前面，重复撞同一堵墙。
                        store.save_content(db, ch["key"], ch["idx"], got.text,
                                           ok=False, note=f"blocked:{got.note}"[:180])
                        db.commit()
                        if blocked_streak >= MAX_BLOCKED_STREAK:
                            for f in futures:
                                f.cancel()
                            log(f"[{source}] 连续 {blocked_streak} 次被风控，本轮收工"
                                "（调大 --delay，或换时间再跑）")
                            rep.note = f"风控中断（已处理 {done}/{len(rows)} 章）"
                            cancelled = True
                            break
                        continue
                    blocked_streak = 0
                    ok = got.kind.usable
                    note = got.note or ("" if ok else got.kind.value)
                    if ok:
                        rep.content_ok += 1
                        rep.bump(got.path or "web")
                    else:
                        rep.content_fail += 1
                    store.save_content(db, ch["key"], ch["idx"], got.text, ok=ok, note=note)
                    db.commit()
                    if done % 20 == 0:
                        log(f"[{source}] 正文 进度 {done}/{rep.need_content} · "
                            f"成功 {rep.content_ok} · 失败 {rep.content_fail} · 路径 {rep.by_path}")
            finally:
                pool.shutdown(wait=not cancelled, cancel_futures=cancelled)
    finally:
        conn.__exit__(None, None, None)

    rep.seconds = round(time.time() - t0, 1)
    log(f"[{source}] 正文：成功 {rep.content_ok}（路径 {rep.by_path}），失败 {rep.content_fail}，"
        f"风控 {rep.blocked}，{rep.seconds}s")
    return rep
