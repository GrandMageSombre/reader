"""起点破解器：书目（多入口）→ 目录 → 正文（四路取其一）。

## 四条正文路，为什么需要四条

| 路 | 地址 | 给什么 | 什么时候用 |
|----|------|--------|-----------|
| `web` | `/chapter/{bid}/{cid}/` | 免费章全文；VIP 章 ~130 字试读 | 默认 |
| `mobile` | `m.qidian.com/chapter/{bid}/{cid}/` | 移动页结构不同，风控口径也不同 | web 被判成试读 |
| `ajax` | `ajax/chapter/chapterInfo` | 带 cookie 的 JSON 正文 | 前两条都拿不到 |
| `collector` | 采集站镜像 | 全本（含 VIP 章） | 正站只给试读时的**主要破解路径** |

每一路都返回 [plan.Cracked]（正文 + 成色 + 走了哪条路），
编排层只认 `usable`，所以「哪条路管用」不需要写死在业务里。

判定与 App 侧 `Paywall` 完全对齐：试读标记、220 字阈值、锁定标记。
"""
from __future__ import annotations

import json
import re
from typing import Callable, Iterable, Iterator

from bs4 import BeautifulSoup

from .. import config, qidian as base
from . import plan
from .collectors import CollectorChain

Fetcher = Callable[..., tuple[int, str]]

MOBILE = "https://m.qidian.com"
AJAX = f"{config.QIDIAN_BASE}/ajax/chapter/chapterInfo"

# 移动页正文容器（版本换过几轮，全列上，命中哪个算哪个）
MOBILE_SELECTORS = (
    "div#chapter-content",
    "div.content",
    "div.read-content",
    "div.chapter-content",
    "main",
)


class QidianCrack:
    source = "qidian"

    def __init__(self, fetch: Fetcher | None = None, collector: CollectorChain | None = None,
                 log=print):
        self._fetch = fetch or self._default_fetch()
        self.collector = collector if collector is not None else CollectorChain(log=log)
        self.log = log

    # ---------------- 会话 ----------------
    @staticmethod
    def _default_fetch() -> Fetcher:
        """默认走 `qidian.Qidian` 的会话（自动过 probe.js、cookie 落盘）。"""
        qd = base.Qidian()
        state = {"ready": False}

        def fetch(url: str, referer: str | None = None) -> tuple[int, str]:
            if not state["ready"]:
                qd.ensure_access()
                state["ready"] = True
            return qd.s.get(url, referer=referer, allow_status=(200, 202, 403, 404))

        return fetch

    def _get(self, url: str, referer: str | None = None) -> tuple[int, str]:
        try:
            return self._fetch(url, referer)
        except TypeError:
            return self._fetch(url)          # 简化版 fetch（单测用）
        except Exception as exc:  # noqa: BLE001
            self.log(f"[qidian] {url} 失败：{exc}")
            return 0, ""

    # ---------------- 书目 ----------------
    def books(self, entries: tuple[plan.QidianEntry, ...] | None = None,
              pages: int = 1, max_books: int | None = None) -> Iterator[dict]:
        """遍历入口 × 分页，产出书 dict（按 bid 去重）。"""
        seen: set[str] = set()
        count = 0
        for entry in (entries or plan.QIDIAN_ENTRIES):
            for page in range(1, max(1, pages) + 1):
                url = entry.page_url(page)
                status, html = self._get(url, referer=config.QIDIAN_BASE + "/")
                if status != 200 or not html:
                    break
                books = parse_book_list(html)
                if not books:
                    break
                for b in books:
                    if b["bid"] in seen:
                        continue
                    seen.add(b["bid"])
                    b["entry"] = entry.name
                    yield b
                    count += 1
                    if max_books and count >= max_books:
                        return

    # ---------------- 目录 ----------------
    def catalog(self, bid: str, book_url: str = "") -> tuple[dict, list[dict]]:
        url = book_url or f"{config.QIDIAN_BASE}/book/{bid}/"
        status, html = self._get(url, referer=config.QIDIAN_BASE + "/")
        if status != 200:
            raise RuntimeError(f"书籍页 HTTP {status}: {url}")
        return base.Qidian.parse_catalog(html)

    # ---------------- 正文 ----------------
    def content(self, chapter: dict, book_title: str = "",
                chapters: list[dict] | None = None,
                strategies: Iterable[str] | None = None,
                local_text=None) -> plan.Cracked:
        """按策略顺序取一章正文，拿到全文就停。

        [local_text] 是「本地已有正文」的读取器（`idx -> text`），只用于采集站
        第三道闸（正文指纹校验）；worker 线程里不要传会碰 sqlite 的实现。
        """
        tried: list[str] = []
        best = plan.Cracked()
        for name in strategies or plan.strategy_names(self.source):
            tried.append(name)
            got = self._route(name, chapter, book_title, chapters, local_text)
            best = plan.better(best, got) if best.path else got
            if got.usable:
                return got
        best.note = best.note or f"四路都没拿到全文（试过 {','.join(tried)}）"
        return best

    def _route(self, name: str, chapter: dict, book_title: str,
               chapters: list[dict] | None, local_text=None) -> plan.Cracked:
        try:
            if name == "web":
                return self._web(chapter)
            if name == "mobile":
                return self._mobile(chapter)
            if name == "ajax":
                return self._ajax(chapter)
            if name == "collector":
                return self._collector(chapter, book_title, chapters, local_text)
        except Exception as exc:  # noqa: BLE001
            # 网络/风控类才判 BLOCKED 参与熔断；代码 bug 判 EMPTY，别冒充「被封」
            return plan.from_exception(self.source, name, exc)
        return plan.Cracked(kind=plan.Kind.EMPTY, path=name, note="未知策略")

    def _web(self, chapter: dict) -> plan.Cracked:
        bid = plan.qidian_bid(chapter.get("url", ""))
        status, html = self._get(chapter["url"], referer=f"{config.QIDIAN_BASE}/book/{bid}/")
        if status != 200:
            return plan.Cracked(kind=plan.Kind.BLOCKED, path="web", note=f"HTTP {status}")
        text, preview, note = base.Qidian.parse_chapter(html)
        kind = plan.classify(text, note)
        return plan.Cracked(text=text, kind=kind, path="web", note=note)

    def _mobile(self, chapter: dict) -> plan.Cracked:
        bid = plan.qidian_bid(chapter.get("url", ""))
        cid = plan.qidian_cid(chapter.get("url", ""))
        if not (bid and cid):
            return plan.Cracked(kind=plan.Kind.EMPTY, path="mobile", note="拿不到 bid/cid")
        url = f"{MOBILE}/chapter/{bid}/{cid}/"
        status, html = self._get(url, referer=f"{MOBILE}/book/{bid}/")
        if status != 200:
            return plan.Cracked(kind=plan.Kind.BLOCKED, path="mobile", note=f"HTTP {status}")
        text, note = parse_mobile_chapter(html)
        return plan.Cracked(text=text, kind=plan.classify(text, note), path="mobile", note=note)

    def _ajax(self, chapter: dict) -> plan.Cracked:
        bid = plan.qidian_bid(chapter.get("url", ""))
        cid = plan.qidian_cid(chapter.get("url", ""))
        if not (bid and cid):
            return plan.Cracked(kind=plan.Kind.EMPTY, path="ajax", note="拿不到 bid/cid")
        url = f"{AJAX}?bookId={bid}&chapterId={cid}"
        status, body = self._get(url, referer=chapter.get("url"))
        if status != 200:
            return plan.Cracked(kind=plan.Kind.BLOCKED, path="ajax", note=f"HTTP {status}")
        text, note = parse_ajax_chapter(body)
        return plan.Cracked(text=text, kind=plan.classify(text, note), path="ajax", note=note)

    def _collector(self, chapter: dict, book_title: str,
                   chapters: list[dict] | None, local_text=None) -> plan.Cracked:
        if not book_title or not chapters:
            return plan.Cracked(kind=plan.Kind.EMPTY, path="collector", note="缺少书名/目录，无法对齐")
        text = self.collector.chapter(book_title, chapters, chapter, local_text)
        if not text:
            return plan.Cracked(kind=plan.Kind.EMPTY, path="collector",
                                note=f"采集站没有这一章（{self.collector.last_reason or '未收录'}）")
        return plan.Cracked(text=text, kind=plan.classify(text), path="collector",
                            note="collector:mirror")


# ---------------------------------------------------------------- 纯解析

def parse_book_list(html: str) -> list[dict]:
    """书单页 → 书 dict。书库页（`div.all-book-list`）与排行榜页结构不同，两套都试。"""
    out: list[dict] = []
    if not html:
        return out
    # 1) 书库/分类页：复用 qidian.parse_list（结构最稳）
    try:
        books = base.Qidian.parse_list(html)
    except Exception:  # noqa: BLE001
        books = []
    for b in books:
        out.append({"bid": b.bid, "title": b.title, "author": b.author,
                    "cover": b.cover, "intro": b.intro, "category": b.category,
                    "status": b.status, "words": b.words, "updated": b.updated,
                    "url": b.url})
    if out:
        return out

    # 2) 排行榜页：`ul.rank-list li` / `div.book-mid-info` 混合，统一按「/book/{id}/ 链接」抽
    soup = BeautifulSoup(html, "lxml")
    seen: set[str] = set()
    for a in soup.select("a[href*='/book/']"):
        href = a.get("href", "")
        bid = plan.qidian_bid(href)
        if not bid or bid in seen:
            continue
        title = a.get_text(" ", strip=True)
        if not title or len(title) > 40:
            continue
        seen.add(bid)
        out.append({"bid": bid, "title": title, "author": "", "cover": "",
                    "intro": "", "category": "", "status": "", "words": 0,
                    "updated": "", "url": f"{config.QIDIAN_BASE}/book/{bid}/"})
    return out


def parse_mobile_chapter(html: str) -> tuple[str, str]:
    """移动章节页 → (正文, 备注)。结构换过几轮，按候选容器依次试。"""
    if not html:
        return "", "HTTP 空响应"
    soup = BeautifulSoup(html, "lxml")
    body = None
    for sel in MOBILE_SELECTORS:
        body = soup.select_one(sel)
        if body is not None:
            break
    if body is None:
        return "", "移动页正文容器未命中"
    paras = [p.get_text(" ", strip=True) for p in body.select("p")]
    paras = [p for p in paras if p]
    text = "\n".join(paras) if paras else body.get_text("\n", strip=True)
    note = "vip_preview" if any(m in html for m in plan.PREVIEW_MARKS) else ""
    return text, note


def parse_ajax_chapter(body: str) -> tuple[str, str]:
    """ajax JSON → (正文, 备注)。失败时把业务码写进备注，方便判断是哪一类拒绝。"""
    body = (body or "").strip()
    if not body:
        return "", "空响应"
    try:
        data = json.loads(body)
    except json.JSONDecodeError:
        return "", "返回不是 JSON"
    if not isinstance(data, dict):
        return "", "返回结构异常"
    code = data.get("code")
    if code not in (0, None):
        return "", f"ajax code={code}"
    payload = data.get("data") or {}
    content = payload.get("content") or payload.get("chapterContent") or ""
    if not content:
        return "", "ajax 没有正文"
    soup = BeautifulSoup(content, "lxml")
    for br in soup.select("br"):
        br.replace_with("\n")
    paras = [p.get_text(" ", strip=True) for p in soup.select("p")]
    text = "\n".join(p for p in paras if p) or soup.get_text("\n", strip=True)
    return text, ""
