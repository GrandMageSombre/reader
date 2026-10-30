"""起点中文网（www.qidian.com）爬取。

实测（2026-10）：
- PC 站首次访问返回 202 + `probe.js` 指纹脚本，浏览器跑完才下发通行 cookie。
  本模块先用 headless Edge 过这道关（tools/qidian_cookie.mjs），之后全部走纯 HTTP，
  cookie 存 data/cookies_qidian.json，重启不用重过。
- 书库列表 `/all/`、`/all/page{N}/`、`/all/chanId{X}/`、`/all/chanId{X}-subCateId{Y}/`
  都是 SSR，每页 20 本，`data-pagemax` 给出该筛选的页数上限（实测 50 → 1000 本/筛选）。
- 书籍页 `/book/{id}/` 一次给全量目录（`div.catalog-volume`，卷头带 免费/VIP 标记）。
- 章节页 `/chapter/{bid}/{cid}/` 正文在 `main[id^=c-]`；VIP 章只给约 130 字试读，
  页面含「订阅本章」标记。
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

from bs4 import BeautifulSoup

from . import config, store
from .net import Session


@dataclass
class QidianBook:
    bid: str
    title: str
    author: str = ""
    cover: str = ""
    intro: str = ""
    category: str = ""
    status: str = ""
    words: int = 0
    updated: str = ""
    url: str = ""


def _abs_cover(url: str) -> str:
    u = (url or "").strip()
    if u.startswith("//"):
        return "https:" + u
    if u.startswith("/"):
        return config.QIDIAN_BASE + u
    return u


class Qidian:
    source = "qidian"

    def __init__(self):
        self.s = Session("qidian", config.QIDIAN_BASE)
        self._ready = False

    # ---------------- 通行 cookie ----------------
    def ensure_access(self) -> None:
        if self._ready:
            return
        status, body = self.s.get(config.QIDIAN_BASE + "/all/", allow_status=(200, 202, 403))
        if status == 200 and "all-book-list" in body:
            self._ready = True
            self.s.save()
            return

        # 202 + probe.js 是首次访问的指纹页；403 是抓太快被风控——两种都靠浏览器重过一次
        print(f"[qidian] HTTP {status}，用 headless Edge 过一次指纹页…", file=sys.stderr)
        self._bootstrap_cookie()
        status, body = self.s.get(config.QIDIAN_BASE + "/all/", allow_status=(200, 202, 403))
        if status != 200 or "all-book-list" not in body:
            raise RuntimeError(
                f"过了指纹页仍然拿不到书库列表（HTTP {status}）。"
                "起点风控是按 IP 的，歇几分钟再跑；或者调大 --delay。")
        self._ready = True
        self.s.save()

    def _bootstrap_cookie(self) -> None:
        helper = Path(__file__).resolve().parents[1] / "qidian_cookie.mjs"
        out = Path(__file__).resolve().parents[1] / "_probe" / "cookies.json"
        if not helper.exists():
            raise RuntimeError(f"缺少 {helper}（headless 过指纹脚本）")
        try:
            subprocess.run(["node", str(helper)], cwd=str(config.ROOT),
                           check=True, timeout=180)
        except FileNotFoundError as exc:
            raise RuntimeError("需要 Node.js 来跑 headless Edge 过指纹页") from exc
        except subprocess.CalledProcessError as exc:
            raise RuntimeError(f"过指纹页失败：{exc}") from exc
        data = json.loads(out.read_text("utf-8"))
        self.s.set_cookie_header(data.get("cookieHeader", ""))
        print(f"[qidian] 拿到 {len(data.get('cookies', []))} 个 cookie", file=sys.stderr)

    # ---------------- 分类筛选 ----------------
    def filters(self) -> list[dict]:
        """一级分类 + 二级分类（都是 /all/ 的筛选地址）。"""
        self.ensure_access()
        seen: dict[str, dict] = {}
        pages = [f"{config.QIDIAN_BASE}/all/"]
        # 先从总库页抓一遍，再逐个大类首页各抓一遍，凑齐二级分类
        status, html = self.s.get(pages[0])
        self._harvest_filters(html, seen)
        for chan in sorted({f"/all/chanId{cid}/" for cid in
                            re.findall(r"/all/chanId(\d+)/", html)}):
            try:
                st, h = self.s.get(config.QIDIAN_BASE + chan)
            except Exception:  # noqa: BLE001
                continue
            if st == 200:
                self._harvest_filters(h, seen)
        return list(seen.values())

    @staticmethod
    def _harvest_filters(html: str, seen: dict) -> None:
        soup = BeautifulSoup(html, "lxml")
        for a in soup.select("a[data-typeid]"):
            cid = a.get("data-typeid", "").strip()
            sid = a.get("data-subtypeid", "").strip()
            name = a.get_text(strip=True)
            if not cid.isdigit() or not name:
                continue
            if sid and sid.isdigit():
                url = f"/all/chanId{cid}-subCateId{sid}/"
                label = f"{name}"
            else:
                url = f"/all/chanId{cid}/"
                label = f"{name}（全部）"
            seen.setdefault(url, {"name": label, "url": url, "chan": cid, "sub": sid})

    # ---------------- 书库列表 ----------------
    @staticmethod
    def list_url(filter_url: str, page: int) -> str:
        """筛选地址 → 分页地址。

        实测两种写法不一样：
        - 全站库：`/all/` → `/all/page2/`；
        - 分类筛选：`/all/chanId21-subCateId10/` → `/all/chanId21-subCateId10-page2/`
          （写 `/page2/` 会 404，写 `-p2/`、`/2/` 则返回第一页，都是假分页）。
        """
        u = filter_url.rstrip("/")
        if page <= 1:
            return config.QIDIAN_BASE + u + "/"
        if "chanId" in u:
            return f"{config.QIDIAN_BASE}{u}-page{page}/"
        return f"{config.QIDIAN_BASE}{u}/page{page}/"

    def list_page(self, filter_url: str, page: int) -> tuple[list[QidianBook], int]:
        """抓一页书单，返回 (书, 该筛选的总页数)。"""
        self.ensure_access()
        url = self.list_url(filter_url, page)
        status, html = self.s.get(url, referer=config.QIDIAN_BASE + filter_url)
        if status != 200:
            return [], 0
        return self.parse_list(html), self._pagemax(html)

    @staticmethod
    def _pagemax(html: str) -> int:
        m = re.search(r'data-pagemax="(\d+)"', html)
        return int(m.group(1)) if m else 0

    @staticmethod
    def parse_list(html: str) -> list[QidianBook]:
        soup = BeautifulSoup(html, "lxml")
        out: list[QidianBook] = []
        for li in soup.select("div.all-book-list li"):
            info = li.select_one("div.book-mid-info")
            if not info:
                continue
            a = info.select_one("h2 a")
            if not a:
                continue
            href = a.get("href", "")
            bid = a.get("data-bid") or (re.search(r"/book/(\d+)", href).group(1)
                                        if re.search(r"/book/(\d+)", href) else "")
            if not bid:
                continue
            author_el = info.select_one("p.author a.name") or info.select_one("p.author a")
            cats = [x.get_text(strip=True) for x in info.select("p.author a")
                    if x is not author_el and x.get_text(strip=True)]
            status_el = info.select_one("p.author span")
            img = (li.select_one("div.book-img-box img")
                   or li.select_one("div.book-img img")
                   or li.select_one("img"))
            cover = ""
            if img:
                cover = _abs_cover(img.get("src") or img.get("data-src") or "")
            intro_el = info.select_one("p.intro")
            words = 0
            upd = info.select_one("p.update span")
            updated = upd.get_text(" ", strip=True)[:40] if upd else ""
            wm = re.search(r"([\d.]+)\s*万?字", upd.get_text(" ", strip=True)) if upd else None
            if wm:
                try:
                    words = int(float(wm.group(1)) * (10000 if "万" in wm.group(0) else 1))
                except ValueError:
                    words = 0
            out.append(QidianBook(
                bid=bid,
                title=a.get_text(strip=True),
                author=author_el.get_text(strip=True) if author_el else "",
                cover=cover,
                intro=intro_el.get_text(" ", strip=True) if intro_el else "",
                category="/".join(cats),
                status=status_el.get_text(strip=True) if status_el else "",
                words=words,
                updated=updated,
                url=f"{config.QIDIAN_BASE}/book/{bid}/",
            ))
        return out

    # ---------------- 目录 ----------------
    def catalog(self, bid: str) -> tuple[dict, list[dict]]:
        """返回 (书籍信息, 章节列表[{idx,title,url,cid,vip}])。"""
        self.ensure_access()
        url = f"{config.QIDIAN_BASE}/book/{bid}/"
        status, html = self.s.get(url)
        if status != 200:
            raise RuntimeError(f"书籍页 HTTP {status}: {url}")
        soup = BeautifulSoup(html, "lxml")
        info = {
            "title": (soup.select_one("h1") or soup).get_text(strip=True)[:60]
            if soup.select_one("h1") else "",
            "author": "",
            "cover": "",
            "intro": "",
        }
        og_img = soup.select_one("meta[property='og:image']")
        if og_img:
            info["cover"] = _abs_cover(og_img.get("content", ""))
        desc = soup.select_one("meta[property='og:description']") or soup.select_one("meta[name=description]")
        if desc:
            info["intro"] = desc.get("content", "")[:600]
        writer = soup.select_one("a.writer-name") or soup.select_one(".book-info a[href*='/author/']")
        if writer:
            info["author"] = writer.get_text(strip=True)

        chapters: list[dict] = []
        idx = 0
        for vol in soup.select("div.catalog-volume"):
            head = vol.select_one("h3.volume-name")
            vol_name = head.get_text(" ", strip=True) if head else ""
            is_vip = bool(vol.select_one("h3.volume-name .vip")) or "VIP" in vol_name
            for a in vol.select("li.chapter-item a.chapter-name, li.chapter-item a"):
                href = a.get("href", "")
                m = re.search(r"/chapter/(\d+)/(\d+)/?", href)
                if not m:
                    continue
                title = a.get_text(" ", strip=True) or a.get("title", "")
                chapters.append({
                    "idx": idx,
                    "title": title,
                    "url": f"{config.QIDIAN_BASE}/chapter/{m.group(1)}/{m.group(2)}/",
                    "cid": m.group(2),
                    "vip": 1 if is_vip else 0,
                    "volume": vol_name,
                })
                idx += 1
        return info, chapters

    # ---------------- 正文 ----------------
    def content(self, chapter: dict) -> tuple[str, bool, str]:
        """返回 (正文, 是否只有VIP试读, 备注)。"""
        self.ensure_access()
        bid = chapter["url"].split("/chapter/")[1].split("/")[0]
        referer = f"{config.QIDIAN_BASE}/book/{bid}/"
        status, html = self.s.get(chapter["url"], referer=referer)
        if status == 403:
            # cookie 过期或触发风控：重过一次指纹页再来一次
            self._ready = False
            self.ensure_access()
            status, html = self.s.get(chapter["url"], referer=referer)
        if status != 200:
            return "", False, f"HTTP {status}"
        soup = BeautifulSoup(html, "lxml")
        main = soup.select_one("main[id^=c-]")
        if not main:
            return "", False, "正文容器未命中"
        paras = [p.get_text(" ", strip=True) for p in main.select("p")]
        paras = [p for p in paras if p]
        text = "\n".join(paras)
        preview = ("订阅本章" in html) or ("本章为VIP章节" in html)
        note = "vip_preview" if preview else ""
        return text, preview, note


# ---------------- 入库编排 ----------------
def sync_catalog(limit_filters: int | None = None, pages: int | None = None,
                 max_books: int | None = None, log=print) -> dict:
    """枚举书库并入库（可断点续跑：已存在的书直接跳过）。"""
    qd = Qidian()
    qd.ensure_access()
    got_filters = qd.filters()
    counts = {"filters": len(got_filters), "books_new": 0, "books_seen": 0, "pages": 0}

    with store.connect() as conn:
        store.save_filters(conn, "qidian", [(f["name"], f["url"]) for f in got_filters])

    use = got_filters[:limit_filters] if limit_filters else got_filters
    stop = False
    for f in use:
        page = 1
        pagemax = 999
        while page <= min(pagemax, pages or config.QIDIAN_PAGE_MAX):
            books, pm = qd.list_page(f["url"], page)
            counts["pages"] += 1
            if pm:
                pagemax = pm
            if not books:
                break
            with store.connect() as conn:
                for b in books:
                    key = f"qidian:{b.bid}"
                    if store.book_exists(conn, key):
                        counts["books_seen"] += 1
                        continue
                    store.upsert_book(
                        conn, key=key, source="qidian", native_id=b.bid,
                        title=b.title, author=b.author, cover=b.cover, intro=b.intro,
                        category=b.category or f["name"], status=b.status,
                        words=b.words, updated=b.updated, url=b.url)
                    counts["books_new"] += 1
            log(f"[catalog] {f['name']} p{page}/{pagemax} +{len(books)} "
                f"累计新书 {counts['books_new']}")
            if max_books and counts["books_new"] >= max_books:
                stop = True
                break
            page += 1
        if stop:
            break
    return counts


def sync_chapters(limit: int | None = None, log=print) -> dict:
    """给库里还没有目录的书抓目录。"""
    qd = Qidian()
    counts = {"books": 0, "chapters": 0}
    with store.connect() as conn:
        todo = store.books(conn, "qidian", missing_catalog=True, limit=limit)
    for row in todo:
        try:
            info, chapters = qd.catalog(row["native_id"])
        except Exception as exc:  # noqa: BLE001
            log(f"[chapters] {row['title']} 失败：{exc}")
            continue
        with store.connect() as conn:
            store.upsert_book(
                conn, key=row["key"], source="qidian", native_id=row["native_id"],
                title=info["title"] or row["title"], author=info["author"] or row["author"],
                cover=info["cover"] or row["cover"], intro=info["intro"] or row["intro"],
                category=row["category"], status=row["status"], words=row["words"],
                updated=row["updated"], url=row["url"])
            store.replace_chapters(conn, row["key"],
                                   [(c["idx"], c["title"], c["url"], c["cid"], c["vip"])
                                    for c in chapters])
        counts["books"] += 1
        counts["chapters"] += len(chapters)
        log(f"[chapters] {row['title']} → {len(chapters)} 章")
    return counts


def sync_contents(limit: int = 50, include_vip: bool = True, cooldown: int = 120,
                  log=print) -> dict:
    """抓正文：免费章全文；VIP 章只存站点给的试读片段并标记。

    起点按 IP 限速：连续抓几百章后会统一返回 403。这里遇到 403 就歇 [cooldown] 秒，
    连续 3 次被挡就收工，下次重跑会从没抓的章节继续。
    """
    qd = Qidian()
    counts = {"ok": 0, "preview": 0, "fail": 0, "blocked": 0}
    blocked_streak = 0
    with store.connect() as conn:
        todo = store.pending_contents(conn, "qidian", limit, include_vip=include_vip)
    for ch in todo:
        text, preview, note = "", False, ""
        try:
            text, preview, note = qd.content({"url": ch["url"], "idx": ch["idx"]})
        except Exception as exc:  # noqa: BLE001
            note = str(exc)
        if not text and "403" in note:
            blocked_streak += 1
            log(f"[content] 403 风控，歇 {cooldown}s（连续 {blocked_streak} 次）")
            if blocked_streak >= 3:
                log("[content] 连续被挡，本轮收工，稍后重跑会接着抓")
                break
            time.sleep(cooldown)
            qd._ready = False
            try:
                text, preview, note = qd.content({"url": ch["url"], "idx": ch["idx"]})
            except Exception as exc:  # noqa: BLE001
                note = str(exc)
            if not text and "403" in note:
                counts["blocked"] += 1     # 只有「重试后仍被挡」才计一次风控
                continue
        blocked_streak = 0
        # 试读片段不算抓到：ok=0，下次重跑还会再试（note 里留标记给接口展示）
        ok = bool(text) and not preview
        if preview:
            note = "vip_preview"
        with store.connect() as conn:
            store.save_content(conn, ch["key"], ch["idx"], text, ok=ok, note=note)
        if preview:
            counts["preview"] += 1
        elif ok:
            counts["ok"] += 1
        else:
            counts["fail"] += 1
        if (counts["ok"] + counts["preview"] + counts["fail"]) % 20 == 0:
            log(f"[content] 进展 {counts}")
    return counts
