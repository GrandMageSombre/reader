"""番茄破解器：书池扩到站点愿意给的上限，正文四路取全文。

## 为什么「全部资源」在番茄这边要单独写一层

web 端能给的只有三档榜单（26 本）+ 书籍页 + 目录 + 免费章 SSR。
「按分类列书 / 搜索 / 批量正文 / 付费章」全在 App 接口里，需要签名头（见 `appclient.py`）。
所以扩池走三条路，谁通谁加：

```
榜单 type × dim ─┐
搜索种子 keyword ─┼─► 去重书池 ─► 目录 ─► 正文（web / app / batch / collector）
分类 gender+cat ─┘
```

`dim`、`category_id` 这些参数站点不认时返回空列表——**空不是错误**，跳过继续下一个。

## 正文四路

| 路 | 来源 | 特点 |
|----|------|------|
| `web` | `/reader/{itemId}` SSR | 免费章主路；正文是私有区码位，要靠字形表还原 |
| `app` | `/reading/reader/full` | 客户端接口，明文正文（不解字形），需签名器 |
| `batch` | `/reading/reader/batch_full/v` | 一次多章，风控更松，适合整本补 |
| `collector` | 采集站镜像 | 付费章兜底，按「书名 + 章名 + 预期位置」对齐 |
"""
from __future__ import annotations

import urllib.parse
from typing import Callable, Iterable, Iterator

from .. import config, fontfix, fanqie as base
from . import plan
from .appclient import FanqieApp
from .collectors import CollectorChain

WebJson = Callable[[str], dict]


class FanqieCrack:
    source = "fanqie"

    def __init__(self, web: "base.Fanqie | None" = None, app: FanqieApp | None = None,
                 collector: CollectorChain | None = None, web_json: WebJson | None = None,
                 log=print):
        self.web = web
        self.app = app
        self.collector = collector if collector is not None else CollectorChain(log=log)
        self._web_json = web_json or self._default_web_json()
        self.log = log

    @staticmethod
    def _default_web_json() -> WebJson:
        fq = base.Fanqie()

        def fetch(path: str) -> dict:
            return fq.s.get_json(config.FANQIE_BASE + path)

        return fetch

    # ---------------- 书池 ----------------
    def books(self, dims: Iterable[str] | None = None, seeds: Iterable[str] | None = None,
              max_books: int | None = None) -> Iterator[dict]:
        seen: set[str] = set()
        count = 0

        def emit(raw: dict, how: str):
            nonlocal count
            bid = str(raw.get("bookId") or raw.get("book_id") or "").strip()
            name = str(raw.get("bookName") or raw.get("book_name") or "").strip()
            if not bid or not name or bid in seen:
                return None
            seen.add(bid)
            count += 1
            return {
                "bookId": bid, "bookName": name,
                "author": str(raw.get("author") or raw.get("authorName") or "").strip(),
                "abstract": str(raw.get("abstract") or raw.get("bookAbstract") or "").strip(),
                "thumbUri": str(raw.get("thumbUri") or raw.get("thumbUrl") or "").strip(),
                "category": str(raw.get("category") or "").strip(),
                "words": int(raw.get("wordNumber") or 0),
                "status": {1: "连载", 2: "完结"}.get(raw.get("creationStatus"), ""),
                "how": how,
            }

        # 1) 榜单 × 维度
        for t in plan.FANQIE_RANK_TYPES:
            for dim in (dims if dims is not None else plan.FANQIE_RANK_DIMS):
                params = {"type": t}
                if dim:
                    params["dim"] = dim
                path = "/api/rank/list?" + urllib.parse.urlencode(params)
                try:
                    data = self._web_json(path)
                except Exception as exc:  # noqa: BLE001
                    self.log(f"[fanqie] 榜单 {path} 失败：{exc}")
                    continue
                rows = ((data.get("data") or {}).get("list") or []) if isinstance(data, dict) else []
                for raw in rows:
                    item = emit(raw, f"rank:{t}/{dim or '-'}")
                    if item:
                        yield item
                        if max_books and count >= max_books:
                            return

        # 2) 搜索种子（App 接口；没有签名器时这里就是空的）
        if self.app is not None:
            for key in (seeds if seeds is not None else plan.FANQIE_SEEDS):
                try:
                    rows = self.app.search(key)
                except Exception as exc:  # noqa: BLE001
                    self.log(f"[fanqie] 搜索 {key} 失败：{exc}")
                    continue
                for raw in rows:
                    item = emit(raw, f"search:{key}")
                    if item:
                        yield item
                        if max_books and count >= max_books:
                            return

            # 3) 分类（gender 0=男生 1=女生）
            for gender in (0, 1):
                try:
                    rows = self.app.category(gender=gender)
                except Exception as exc:  # noqa: BLE001
                    self.log(f"[fanqie] 分类 gender={gender} 失败：{exc}")
                    continue
                for raw in rows:
                    item = emit(raw, f"category:{gender}")
                    if item:
                        yield item
                        if max_books and count >= max_books:
                            return

    # ---------------- 目录 ----------------
    def catalog(self, book_id: str) -> list[dict]:
        if self.web is None:
            raise RuntimeError("没有 web 会话，取不到番茄目录")
        return self.web.directory(book_id)

    # ---------------- 正文 ----------------
    def content(self, item_id: str, book_title: str = "",
                chapters: list[dict] | None = None,
                strategies: Iterable[str] | None = None,
                chapter: dict | None = None,
                local_text=None) -> plan.Cracked:
        best = plan.Cracked()
        tried: list[str] = []
        for name in strategies or plan.strategy_names(self.source):
            tried.append(name)
            got = self._route(name, item_id, book_title, chapters, chapter, local_text)
            best = plan.better(best, got) if best.path else got
            if got.usable:
                return got
        best.note = best.note or f"四路都没拿到全文（试过 {','.join(tried)}）"
        return best

    def _route(self, name: str, item_id: str, book_title: str,
               chapters: list[dict] | None, chapter: dict | None,
               local_text=None) -> plan.Cracked:
        try:
            if name == "web":
                return self._web(item_id)
            if name == "app":
                return self._app(item_id)
            if name == "batch":
                return self._batch(item_id)
            if name == "collector":
                return self._collector(item_id, book_title, chapters, chapter, local_text)
        except Exception as exc:  # noqa: BLE001
            # 只有网络/风控类异常参与熔断；代码 bug 判 EMPTY（见 plan.from_exception）
            return plan.from_exception(self.source, name, exc)
        return plan.Cracked(kind=plan.Kind.EMPTY, path=name, note="未知策略")

    def _web(self, item_id: str) -> plan.Cracked:
        if self.web is None:
            return plan.Cracked(kind=plan.Kind.EMPTY, path="web", note="没有 web 会话")
        text, _title, locked, note = self.web.content(item_id)
        kind = plan.classify(text, note or ("chapterLock" if locked else ""))
        return plan.Cracked(text=text, kind=kind, path="web", note=note)

    def _app(self, item_id: str) -> plan.Cracked:
        if self.app is None:
            return plan.Cracked(kind=plan.Kind.EMPTY, path="app", note="没有签名器/App 客户端")
        text, note = self.app.full(item_id)
        return plan.Cracked(text=_plain(text), kind=plan.classify(text, note),
                            path="app", note=(note or "app:") + _pua_note(text))

    def _batch(self, item_id: str) -> plan.Cracked:
        if self.app is None:
            return plan.Cracked(kind=plan.Kind.EMPTY, path="batch", note="没有签名器/App 客户端")
        got = self.app.batch_full([item_id])
        text = got.get(item_id, "")
        if not text:
            return plan.Cracked(kind=plan.Kind.EMPTY, path="batch", note="批量接口没给这一章")
        return plan.Cracked(text=_plain(text), kind=plan.classify(text), path="batch",
                            note="app:batch")

    def _collector(self, item_id: str, book_title: str, chapters: list[dict] | None,
                   chapter: dict | None, local_text=None) -> plan.Cracked:
        if not book_title or not chapters:
            return plan.Cracked(kind=plan.Kind.EMPTY, path="collector",
                                note="缺少书名/目录，无法对齐")
        ch = chapter or {"idx": 0, "title": ""}
        if not ch.get("title"):
            for c in chapters:
                if str(c.get("native_id") or c.get("itemId") or "") == item_id:
                    ch = c
                    break
        text = self.collector.chapter(book_title, chapters, ch, local_text)
        if not text:
            return plan.Cracked(kind=plan.Kind.EMPTY, path="collector",
                                note=f"采集站没有这一章（{self.collector.last_reason or '未收录'}）")
        return plan.Cracked(text=text, kind=plan.classify(text), path="collector",
                            note="collector:mirror")


def _plain(text: str) -> str:
    """App 接口正常给明文。真拿到私有区码位时，没有该页字体文件就无法还原，
    于是原样返回、并在备注里标明——让编排层继续试下一条路，而不是把方块字入库。"""
    return text or ""


def _pua_note(text: str) -> str:
    return "；正文仍是私有区码位" if fontfix.obfuscated_ratio(text) > 0.02 else ""
