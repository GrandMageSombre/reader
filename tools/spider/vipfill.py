"""VIP 章节全文补全：从采集站按「书名 + 章名」补齐付费章正文。

为什么这么做：起点 VIP / 番茄付费章的正文，服务端**不下发**（试读约 130 字），
签名、cookie 都改变不了这一点。同一本书在采集站通常有全本，所以正确做法是
「换源补全」——把采集站的正文对齐到我们的章节号，写进本地库。

    python -m tools.spider.vipfill --book qidian:1035420986 --limit 60
    python -m tools.spider.vipfill --source qidian --books 3 --limit 200
    python -m tools.spider.vipfill --source qidian --only-vip --limit 500

对齐规则见 `align.py`（与 App 侧 `Paywall.findChapter` 同一套）。
补进来的正文 note 记 `collector:<站点>`，接口会原样透出，App 里能标「来自采集站」。
"""
from __future__ import annotations

import argparse
import re
import time
import urllib.parse

from bs4 import BeautifulSoup

from . import align, config, store
from .net import Session

# 候选采集站（按实测可用性排序；望书网最稳）
SITES = [
    ("wangshu", "http://www.wangshuwx.com"),
    ("zwdu", "http://www.zwduxs.com"),
    ("2kxs", "https://www.2kxs.info"),
]

SEARCH = "/modules/article/search.php?searchkey={key}"


def _clean(text: str) -> str:
    lines = [ln.strip() for ln in text.splitlines()]
    out = []
    for ln in lines:
        if not ln:
            continue
        if ln.startswith(("笔趣阁", "本章未完", "请记住本书", "手机版阅读", "www.")):
            continue
        out.append(ln)
    return "\n".join(out)


class CollectorSite:
    def __init__(self, name: str, base: str):
        self.name = name
        self.base = base
        self.s = Session(f"collector_{name}", base)
        self.last_status = 0

    def alive(self) -> bool:
        """站点还活着吗（首页能不能 200）。抓之前先问一句，别在 503 上耗几分钟。"""
        try:
            st, _ = self.s.get(self.base + "/", allow_status=(200, 403, 404, 503),
                               retries=0, timeout=8)
        except Exception:  # noqa: BLE001
            st = -1
        self.last_status = st
        return st in (200, 403, 404)

    def search(self, title: str) -> str | None:
        """按书名搜，返回同名书的详情页地址。"""
        url = self.base + SEARCH.format(key=urllib.parse.quote(title))
        try:
            st, html = self.s.get(url, allow_status=(200, 404, 503), retries=1)
        except Exception:  # noqa: BLE001
            return None
        self.last_status = st
        if st != 200:
            return None
        soup = BeautifulSoup(html, "lxml")
        for row in soup.select("table.grid tr#nr"):
            a = row.select_one("td a")
            if not a:
                continue
            name = a.get_text(strip=True).strip("《》")
            if not align.same_book(name, title):
                continue
            href = a.get("href")
            return href if href.startswith("http") else self.base + href
        return None

    def catalog(self, book_url: str) -> list[tuple[str, str]]:
        """返回 [(章名, 章地址)]，按站点顺序。"""
        try:
            st, html = self.s.get(book_url, allow_status=(200, 404))
        except Exception:  # noqa: BLE001
            return []
        if st != 200:
            return []
        soup = BeautifulSoup(html, "lxml")
        out = []
        for a in soup.select("a[href]"):
            href = a.get("href", "")
            if not re.search(r"/\d+\.html$", href):
                continue
            title = a.get_text(strip=True)
            if not title:
                continue
            url = href if href.startswith("http") else self.base + href
            out.append((title, url))
        return out

    def content(self, chapter_url: str) -> str:
        try:
            st, html = self.s.get(chapter_url, allow_status=(200, 404))
        except Exception:  # noqa: BLE001
            return ""
        if st != 200:
            return ""
        soup = BeautifulSoup(html, "lxml")
        body = soup.select_one("#content") or soup.select_one("div.content")
        if body is None:
            return ""
        for tag in body.select("script, style, a"):
            tag.decompose()
        return _clean(body.get_text("\n", strip=True))


NOTE_CHAPTER = re.compile(r"感言|公告|请假|感谢|上架|单章|新书|说明|求票|推荐|完本")


def is_note_chapter(title: str) -> bool:
    """作者的话/公告这类章节：不参与对齐校验，也不用采集站补（容易张冠李戴）。"""
    return bool(NOTE_CHAPTER.search(title or ""))


def _story_chapters(chapters):
    """挑出「像正常故事章」的章节（有第N章、且不是感言公告）。"""
    out = []
    for c in chapters:
        t = c["title"] or ""
        if is_note_chapter(t):
            continue
        if not re.search(rf"第[{align.NUM}]+[章节回]", t):
            continue
        out.append(c)
    return out


def _shingles(text: str, k: int = 8, window: int = 4000) -> set[str]:
    """取定长子串做「指纹集合」，用来判断两段正文是不是同一本书。

    注意：**不能用等距抽样**——采集站和正站只要差一个标点，抽样点就会整体错位，
    重合度会从 95% 掉到 20%，造成「同名异书」的误判。所以取开头一段连续窗口。
    """
    clean = re.sub(r"\s+", "", text)[:window]
    if len(clean) < k:
        return set()
    return {clean[i:i + k] for i in range(0, len(clean) - k + 1)}


def verify_book(conn, book, site: CollectorSite, toc: list[tuple[str, str]],
                chapters, log=print) -> tuple[bool, str]:
    """确认「采集站这本」和「本地这本」是同一本书。

    同名不代表同书（《最强龙婿》在采集站有 53 章的另一本）。
    两道校验：
    1. 目录对齐率：本地抽 20 章，能在采集站目录里对上的比例 >= 30%；
    2. 正文相似度：找一章本地已有全文的章，与采集站对应章比 8 字指纹重合度 >= 0.35。
    """
    titles = [t for t, _ in toc]
    sample = chapters[:: max(1, len(chapters) // 20)][:20]
    hits = 0
    for c in sample:
        hint = align.expected_index(c["idx"], len(chapters), len(toc))
        if align.find_chapter(titles, c["title"], hint) >= 0:
            hits += 1
    ratio = hits / max(1, len(sample))
    if ratio < 0.3:
        return False, f"章名对不上（{hits}/{len(sample)}，{ratio:.0%}）"

    # 用 3 章「正常故事章」比对正文，取最高重合度——单章可能恰好是番外/感言，误杀
    probes = []
    for c in _story_chapters(chapters):
        row = store.get_content(conn, book["key"], c["idx"])
        if row and (row["text"] or "").strip() and len(row["text"]) >= 500:
            probes.append((c, row["text"]))
        if len(probes) >= 40:
            break
    probes = probes[:: max(1, len(probes) // 3)][:3]
    if not probes:
        return True, f"章名对齐 {ratio:.0%}（本地无正文可比对，先信目录）"

    best = 0.0
    for c, local_text in probes:
        hint = align.expected_index(c["idx"], len(chapters), len(toc))
        pos = align.find_chapter(titles, c["title"], hint)
        if pos < 0:
            continue
        remote_text = site.content(toc[pos][1])
        if len(remote_text) < 200:
            continue
        a, b = _shingles(local_text), _shingles(remote_text)
        best = max(best, len(a & b) / max(1, min(len(a), len(b))))
        if best >= 0.35:
            break
    if best < 0.35:
        return False, f"同名异书：正文重合度仅 {best:.0%}（章名对齐 {ratio:.0%}）"
    return True, f"校验通过：章名对齐 {ratio:.0%}，正文重合 {best:.0%}"


def fill_book(conn, book, sites: list[CollectorSite], limit: int, only_vip: bool,
              log=print) -> dict:
    chapters = store.chapters(conn, book["key"])
    if not chapters:
        return {"book": book["title"], "error": "没有目录，先跑 qidian-chapters"}

    # 需要补的章：VIP（试读/空）或全部
    pending = [c for c in chapters if (c["vip"] or _looks_short(conn, book["key"], c["idx"]))]
    todo = pending[:limit] if limit else pending
    if not todo:
        return {"book": book["title"], "chapters": len(chapters), "pending": 0,
                "filled": 0, "skipped": "没有需要补的章"}

    stats = {"book": book["title"], "chapters": len(chapters), "pending": len(pending),
             "attempted": 0, "filled": 0, "failed": 0, "site": "", "coverage": 0.0}

    live_sites = []
    for site in sites:
        if site.alive():
            live_sites.append(site)
        else:
            log(f"[{site.name}] 不可用（HTTP {site.last_status}），跳过")
    if not live_sites:
        stats["error"] = "采集站全部不可用（多为 503 限流），歇一会儿再跑"
        return stats
    sites = live_sites

    for site in sites:
        detail_url = site.search(book["title"])
        if not detail_url:
            continue
        toc = site.catalog(detail_url)
        if not toc:
            continue
        titles = [t for t, _ in toc]
        ok, why = verify_book(conn, book, site, toc, chapters, log)
        log(f"[{site.name}] 《{book['title']}》采集站目录 {len(toc)} 章 → {why}")
        if not ok:
            stats["rejected"] = stats.get("rejected", 0) + 1
            stats["reject_reason"] = why
            continue
        log(f"[{site.name}] 开始补正文，本地待补 {len(todo)} 章")
        filled = 0
        for c in todo:
            if is_note_chapter(c["title"]):
                stats["skipped_note"] = stats.get("skipped_note", 0) + 1
                continue
            hint = align.expected_index(c["idx"], len(chapters), len(toc))
            pos = align.find_chapter(titles, c["title"], hint)
            if pos < 0:
                stats["failed"] += 1
                continue
            stats["attempted"] += 1
            text = site.content(toc[pos][1])
            if len(text) < 200:
                stats["failed"] += 1
                continue
            store.save_content(conn, book["key"], c["idx"], text,
                               ok=True, note=f"collector:{site.name}")
            filled += 1
            stats["filled"] = filled
            if filled % 20 == 0:
                log(f"[{site.name}] 已补 {filled}/{len(todo)} 章")
        stats["site"] = site.name
        if filled:
            break   # 这个站够用就不必再问下一个

    stats["coverage"] = round(stats["filled"] / max(1, len(todo)), 3)
    if stats["filled"] == 0 and not stats.get("reject_reason"):
        statuses = {s.name: s.last_status for s in sites}
        if any(v == 503 for v in statuses.values()):
            stats["error"] = f"采集站限流（{statuses}），歇一会儿再跑"
        elif not stats.get("attempted"):
            stats["error"] = "采集站里没搜到这本书（未收录）"
    return stats


def _looks_short(conn, key: str, idx: int) -> bool:
    row = store.get_content(conn, key, idx)
    if row is None:
        return True
    if (row["note"] or "").startswith("collector:"):
        return False
    return (row["text"] or "").strip() == "" or len(row["text"]) < 220


def main() -> None:
    ap = argparse.ArgumentParser(prog="python -m tools.spider.vipfill")
    ap.add_argument("--source", default="qidian", choices=["qidian", "fanqie"])
    ap.add_argument("--book", default="", help="只补这一本（key 形如 qidian:1035420986）")
    ap.add_argument("--books", type=int, default=1, help="补几本（按章节多的优先）")
    ap.add_argument("--limit", type=int, default=100, help="每本最多补几章（0=不限）")
    ap.add_argument("--only-vip", action="store_true", help="只补标记为 VIP 的章")
    args = ap.parse_args()

    sites = [CollectorSite(n, b) for n, b in SITES]
    t0 = time.time()
    results = []
    with store.connect() as conn:
        if args.book:
            rows = conn.execute("SELECT * FROM books WHERE key=?", (args.book,)).fetchall()
        else:
            rows = conn.execute(
                "SELECT * FROM books WHERE source=? ORDER BY chapter_count DESC LIMIT ?",
                (args.source, max(1, args.books))).fetchall()
        for book in rows:
            if args.only_vip:
                r = fill_book(conn, book, sites, args.limit, True)
            else:
                r = fill_book(conn, book, sites, args.limit, False)
            print(r)
            results.append(r)

    filled = sum(r.get("filled", 0) for r in results)
    print(f"\n合计补全 {filled} 章，用时 {time.time() - t0:.0f}s")
    print("库内正文统计：")
    with store.connect() as conn:
        for row in conn.execute(
                "SELECT note, COUNT(*) n FROM contents GROUP BY note ORDER BY n DESC LIMIT 8"):
            print(f"  note={row['note']!r:24s} {row['n']} 章")


if __name__ == "__main__":
    main()
