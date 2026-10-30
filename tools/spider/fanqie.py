"""番茄小说（fanqienovel.com）爬取。

实测（2026-10）：
- 没有「按分类列书」的接口，书池只能从榜单拿：`/api/rank/list?type=0/1/2`。
- 书籍页 `/page/{bookId}` 的 `__INITIAL_STATE__` 里带完整目录
  （`chapterListWithVolume`，含 needPay / isChapterLock），
  另有更干净的接口 `/api/reader/directory/detail?bookId=`。
- 书籍信息：`/api/book/info?bookId=`（官方三级分类、字数、状态）。
- 正文：`/reader/{itemId}` 的 SSR 页面，正文在 `__INITIAL_STATE__.reader.chapterData.content`，
  **每个字都被换成私有区码位**，靠该页 `@font-face` 的子集字体渲染。
  子集字形 = SourceHanSansSC-Normal 2.002R 原样轮廓，由 fontfix 还原真字。
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from bs4 import BeautifulSoup

from . import config, fontfix, store
from .net import Session


class Fanqie:
    source = "fanqie"

    def __init__(self):
        self.s = Session("fanqie", config.FANQIE_BASE)
        self._mobile: Session | None = None
        self._ready = False

    @property
    def mobile(self) -> Session:
        """同一个 itemId，PC UA 有时拿到的是客户端渲染的空壳页（6KB，没有 state），
        换成手机 UA 就是完整 SSR（22KB）。所以留一个兜底会话。"""
        if self._mobile is None:
            self._mobile = Session("fanqie_m", config.FANQIE_BASE, ua=config.UA_MOBILE)
        return self._mobile

    # ---------------- 通行 cookie（验证码中间页） ----------------
    def ensure_access(self, force: bool = False) -> None:
        """番茄连续抓多了会掉进 ByteDance 的「验证码中间页」（约 6KB 空壳 + sec sdk）。
        这里用 headless Edge 跑一次页面拿 cookie，再灌回会话。
        """
        if force:
            self._bootstrap_cookie()
            self._ready = True
            return
        if self._ready:
            return
        status, html = self.s.get(config.FANQIE_BASE + "/", allow_status=(200, 403))
        if _is_challenge(html):
            print("[fanqie] 首页就是验证码中间页，用 headless Edge 过一下…", file=sys.stderr)
            self._bootstrap_cookie()
        self._ready = True

    def _bootstrap_cookie(self) -> None:
        helper = Path(__file__).resolve().parents[1] / "qidian_cookie.mjs"
        out = Path(config.DATA_DIR) / "fanqie_cookies.json"
        if not helper.exists():
            raise RuntimeError(f"缺少 {helper}（headless 过验证码脚本）")
        env = dict(os.environ)
        env.update({
            "PROBE_URL": config.FANQIE_BASE + "/",
            "COOKIE_OUT": str(out),
            "DOMAIN": "fanqienovel.com,byteimg.com,bytetos.com",
            "PROFILE_DIR": str(Path(tempfile.gettempdir()) / "modu-fanqie-edge"),
        })
        try:
            subprocess.run(["node", str(helper)], cwd=str(config.ROOT), env=env,
                           check=True, timeout=90)
        except FileNotFoundError as exc:
            raise RuntimeError("需要 Node.js 来跑 headless Edge 过验证码页") from exc
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            raise RuntimeError(f"过验证码页失败：{exc}") from exc
        data = json.loads(out.read_text("utf-8"))
        self.s.set_cookie_header(data.get("cookieHeader", ""))
        self.s.save()
        print(f"[fanqie] 拿到 {len(data.get('cookies', []))} 个 cookie", file=sys.stderr)

    def browser_html(self, url: str) -> str:
        """被验证码中间页挡住时，用 headless Edge 直接取渲染后的 DOM。"""
        helper = Path(__file__).resolve().parents[1] / "edge_fetch.mjs"
        out = Path(config.CACHE_DIR) / "edge_page.html"
        env = dict(os.environ)
        env.update({"URL": url, "OUT": str(out),
                    "PROFILE_DIR": str(Path(tempfile.gettempdir()) / "modu-fanqie-fetch")})
        subprocess.run(["node", str(helper)], cwd=str(config.ROOT), env=env,
                       timeout=120, check=False,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            return out.read_text("utf-8")
        except FileNotFoundError:
            return ""

    # ---------------- 书池 ----------------
    def rank_books(self) -> list[dict]:
        """三档榜单去重后的书池（番茄官方能拿到的全部书）。"""
        out: dict[str, dict] = {}
        for t in config.FANQIE_RANK_TYPES:
            data = self.s.get_json(f"{config.FANQIE_BASE}/api/rank/list?type={t}")
            lst = (data.get("data") or {}).get("list") or []
            for o in lst:
                bid = str(o.get("bookId", "")).strip()
                name = (o.get("bookName") or "").strip()
                if not bid or not name:
                    continue
                out.setdefault(bid, {
                    "bookId": bid,
                    "bookName": name,
                    "author": (o.get("author") or "").strip(),
                    "abstract": (o.get("abstract") or "").strip(),
                    "thumbUri": (o.get("thumbUri") or "").strip(),
                    "rankType": t,
                })
        return list(out.values())

    def book_info(self, book_id: str) -> dict:
        data = self.s.get_json(f"{config.FANQIE_BASE}/api/book/info?bookId={book_id}")
        return data.get("data") or {}

    # ---------------- 目录 ----------------
    def directory(self, book_id: str) -> list[dict]:
        data = self.s.get_json(
            f"{config.FANQIE_BASE}/api/reader/directory/detail?bookId={book_id}")
        payload = data.get("data") or {}
        chapters: list[dict] = []
        for vol in payload.get("chapterListWithVolume") or []:
            if isinstance(vol, dict):
                vol = [vol]
            if not isinstance(vol, list):
                continue
            for ch in vol:
                if not isinstance(ch, dict):
                    continue
                iid = str(ch.get("itemId", "")).strip()
                if not iid:
                    continue
                chapters.append({
                    "itemId": iid,
                    "title": (ch.get("title") or "").strip(),
                    "volume": (ch.get("volume_name") or "").strip(),
                    "needPay": 1 if ch.get("needPay") else 0,
                    "locked": 1 if ch.get("isChapterLock") else 0,
                })
        if not chapters:
            # 兜底：只用 allItemIds（没有标题）
            for i, iid in enumerate(payload.get("allItemIds") or []):
                chapters.append({"itemId": str(iid), "title": f"第{i + 1}章",
                                 "volume": "", "needPay": 0, "locked": 0})
        return chapters

    # ---------------- 正文 ----------------
    def content(self, item_id: str) -> tuple[str, str, bool, str]:
        """返回 (正文, 标题, 是否加密/锁定, 备注)。"""
        url = f"{config.FANQIE_BASE}/reader/{item_id}"
        status, html = self.s.get(url, referer=config.FANQIE_BASE + "/")
        if _is_challenge(html):
            # 被风控：先过一次验证码页换 cookie，再不行就让浏览器直接把页面渲染出来
            try:
                self.ensure_access(force=True)
            except Exception:  # noqa: BLE001  过不了验证码页不算致命，继续往下试
                pass
            status, html = self.s.get(url, referer=config.FANQIE_BASE + "/")
        if _is_challenge(html):
            try:
                html = self.browser_html(url)
            except Exception:  # noqa: BLE001
                html = ""
            if html and not _is_challenge(html):
                status = 200
        if _is_challenge(html):
            return "", "", False, "被风控（验证码中间页），稍后重试"
        if status != 200:
            return "", "", False, f"HTTP {status}"
        state = _initial_state(html)
        if not state:
            # PC 返回的是空壳页，换手机 UA 再试一次
            status, html = self.mobile.get(url, referer=config.FANQIE_BASE + "/")
            state = _initial_state(html)
        if not state:
            return "", "", False, "页面没有 __INITIAL_STATE__"
        reader = state.get("reader") or {}
        chapter = reader.get("chapterData") or {}
        if not chapter:
            chapter = (state.get("preview") or {}).get("chapterData") or {}
        raw = chapter.get("content") or ""
        title = (chapter.get("title") or "").strip()
        if not raw:
            return "", title, False, "章节没有正文（可能已下架）"

        text = _html_to_text(raw)
        font_url = _font_url(html)
        if fontfix.obfuscated_ratio(text) > 0.02 and font_url:
            try:
                font_bytes = self.s.get_bytes(font_url, cache=config.CACHE_DIR / "fonts" /
                                              (font_url.rsplit("/", 1)[-1]))
                text = fontfix.decode(text, font_bytes, cache_key=font_url)
                note = ""
            except Exception as exc:  # noqa: BLE001
                note = f"字体还原失败：{exc}"
        else:
            note = "" if not fontfix.obfuscated_ratio(text) else "字体缺失，正文仍为私有区码位"

        locked = bool(reader.get("chapterLock")) or "订阅" in html and len(text) < 200
        return text, title, locked, note


# ---------------- 工具 ----------------
def _initial_state(html: str) -> dict:
    i = html.find("window.__INITIAL_STATE__=")
    if i < 0:
        return {}
    j = html.find("{", i)
    depth, k, in_str, esc = 0, j, False, False
    while k < len(html):
        ch = html[k]
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                break
        k += 1
    try:
        return json.loads(html[j:k + 1])
    except json.JSONDecodeError:
        return {}


def _html_to_text(raw: str) -> str:
    soup = BeautifulSoup(raw, "lxml")
    for br in soup.select("br"):
        br.replace_with("\n")
    paras = [p.get_text(" ", strip=True) for p in soup.select("p")]
    if not paras:
        return soup.get_text("\n", strip=True)
    return "\n".join(p for p in paras if p)


def _font_url(html: str) -> str:
    m = re.search(r"@font-face\s*\{[^}]*?url\((https://[^)]+\.woff2)\)", html)
    if m:
        return m.group(1)
    m = re.search(r"(https://[^\"')\s]+\.woff2)", html)
    return m.group(1) if m else ""


def _is_challenge(html: str) -> bool:
    """验证码中间页：约 6KB、无 state、带 sec sdk / captcha。"""
    if not html:
        return False
    if "__INITIAL_STATE__" in html:
        return False
    return ("验证码中间页" in html) or ("captcha" in html and len(html) < 12000)


# ---------------- 入库编排 ----------------
def sync_catalog(log=print) -> dict:
    fq = Fanqie()
    books = fq.rank_books()
    counts = {"books": 0, "new": 0}
    for b in books:
        key = f"fanqie:{b['bookId']}"
        info = {}
        try:
            info = fq.book_info(b["bookId"])
        except Exception:  # noqa: BLE001
            pass
        with store.connect() as conn:
            exists = store.book_exists(conn, key)
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
        counts["books"] += 1
        if not exists:
            counts["new"] += 1
        log(f"[catalog] {b['bookName']} · {info.get('category', '')}")
    return counts


def sync_chapters(limit: int | None = None, log=print) -> dict:
    fq = Fanqie()
    counts = {"books": 0, "chapters": 0}
    with store.connect() as conn:
        todo = store.books(conn, "fanqie", missing_catalog=True, limit=limit)
    for row in todo:
        try:
            chapters = fq.directory(row["native_id"])
        except Exception as exc:  # noqa: BLE001
            log(f"[chapters] {row['title']} 失败：{exc}")
            continue
        with store.connect() as conn:
            store.replace_chapters(conn, row["key"], [
                (i, c["title"], f"{config.FANQIE_BASE}/reader/{c['itemId']}",
                 c["itemId"], max(c["needPay"], c["locked"]))
                for i, c in enumerate(chapters)
            ])
        counts["books"] += 1
        counts["chapters"] += len(chapters)
        log(f"[chapters] {row['title']} → {len(chapters)} 章")
    return counts


def sync_contents(limit: int = 50, include_vip: bool = True, cooldown: int = 120,
                  log=print) -> dict:
    """抓正文。

    番茄的风控是「请求量到阈值 → 掉进验证码中间页」。真遇到时不要硬刷：
    歇 [cooldown] 秒再试同一章；连续 3 次都被挡就收工，下次接着跑（断点续抓）。
    """
    fq = Fanqie()
    counts = {"ok": 0, "fail": 0, "blocked": 0}
    blocked_streak = 0
    with store.connect() as conn:
        todo = store.pending_contents(conn, "fanqie", limit, include_vip=include_vip)
    for ch in todo:
        text, title, locked, note = fq.content(ch["native_id"])
        if note.startswith("被风控"):
            blocked_streak += 1
            counts["blocked"] += 1
            log(f"[content] 被风控，歇 {cooldown}s 再试（连续 {blocked_streak} 次）")
            if blocked_streak >= 3:
                log("[content] 连续被挡，本轮收工，稍后重跑会从没抓的章节继续")
                break
            time.sleep(cooldown)
            text, title, locked, note = fq.content(ch["native_id"])
            if note.startswith("被风控"):
                continue
        blocked_streak = 0
        ok = bool(text)
        with store.connect() as conn:
            store.save_content(conn, ch["key"], ch["idx"], text, ok=ok, note=note)
        counts["ok" if ok else "fail"] += 1
        if (counts["ok"] + counts["fail"]) % 10 == 0:
            log(f"[content] {ch['book_title']} #{ch['idx']} {counts}")
    return counts
