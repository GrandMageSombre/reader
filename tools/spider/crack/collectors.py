"""采集站补全链：把「起点/番茄的某一章」映射到采集站里的同一章。

这一段是**付费章的主要破解路径**：正站不下发全文，但同一本书在采集站的镜像里是全本。
链条上的三道校验（都在 `vipfill.py` 里落过地，这里复用到「按章取」的场景）：

1. 书名：`align.same_book`（去掉「（校对版）」「全本」这类噪声后**完全相等**，防同人作）；
2. 目录对齐率：抽样 20 章能在采集站目录里对上的比例 ≥ 30%；
3. 定位纠偏：同名章（「感谢」「请假条」）取**离预期位置最近**的那个。

本模块只做「按章取正文」，不碰数据库 —— 入库由 `crack/runner.py` 负责，
所以它能被 fixture 单测。
"""
from __future__ import annotations

import re
import threading
from typing import Callable

from .. import align
from ..vipfill import CollectorSite, SITES, _clean, is_note_chapter

__all__ = ["CollectorChain", "Toc", "align_ratio", "text_overlap"]

Toc = list[tuple[str, str]]  # [(章名, 章地址)]


def align_ratio(toc: Toc, chapters: list[dict], sample: int = 20) -> float:
    """抽样比对「本地章名 vs 采集站章名」能对上的比例。"""
    if not toc or not chapters:
        return 0.0
    titles = [t for t, _ in toc]
    step = max(1, len(chapters) // sample)
    picked = chapters[::step][:sample]
    hits = 0
    for c in picked:
        hint = align.expected_index(c.get("idx", 0), len(chapters), len(toc))
        if align.find_chapter(titles, c.get("title", ""), hint) >= 0:
            hits += 1
    return hits / max(1, len(picked))


def text_overlap(local: str, remote: str, k: int = 8, window: int = 4000) -> float:
    """两段正文的 8 字指纹重合度（与 `vipfill._shingles` 同一套算法，抽出来给两边共用）。

    不能用等距抽样：两个站只要差一个标点，抽样点就整体错位，重合度会从 95% 掉到 20%。
    所以取开头一段连续窗口。
    """
    def shingles(text: str) -> set[str]:
        clean = re.sub(r"\s+", "", text or "")[:window]
        if len(clean) < k:
            return set()
        return {clean[i:i + k] for i in range(0, len(clean) - k + 1)}

    a, b = shingles(local), shingles(remote)
    if not a or not b:
        return 0.0
    return len(a & b) / max(1, min(len(a), len(b)))


class CollectorChain:
    """多镜像按书名找书，一次找书多次取章（目录缓存，避免每章重搜）。"""

    def __init__(self, sites: list[tuple[str, str]] | None = None, log=print,
                 min_align: float = 0.3, min_overlap: float = 0.35):
        self._sites = [CollectorSite(n, b) for n, b in (sites or SITES)]
        self._cache: dict[str, tuple[CollectorSite, Toc] | None] = {}
        self._lock = threading.Lock()
        self._log = log
        self.min_align = min_align
        self.min_overlap = min_overlap
        self.last_reason = ""

    # ---------- 找书 ----------
    def book(self, title: str, chapters: list[dict] | None = None,
             local_text: Callable[[int], str] | None = None
             ) -> tuple[CollectorSite, Toc] | None:
        """按书名在镜像里找同一本书；找到就缓存（含失败的结论，避免反复撞 503）。

        三道闸（缺一道就会把同名异书的正文串进付费章）：

        1. 书名：`CollectorSite.search` 内部用 `align.same_book`（去掉「（校对版）」这类
           噪声后**完全相等**，不是包含）；
        2. 目录对齐率：抽样 20 章章名对得上 ≥ 30%；
        3. 正文指纹：本地已有全文的故事章抽 1–3 章，与采集站同章比 8 字指纹重合度 ≥ 35%。
           第 3 道需要 [local_text]（本地正文读取器）；没传就退化成「先信目录」并记日志。
        """
        key = title.strip()
        with self._lock:
            if key in self._cache:
                return self._cache[key]
        found = None
        self.last_reason = ""
        for site in self._sites:
            try:
                if not site.alive():
                    self.last_reason = f"{site.name} 不可用（HTTP {site.last_status}）"
                    continue
                url = site.search(key)
                if not url:
                    self.last_reason = f"{site.name} 未收录"
                    continue
                toc = site.catalog(url)
                if not toc:
                    self.last_reason = f"{site.name} 目录为空"
                    continue
                if chapters:
                    ratio = align_ratio(toc, chapters)
                    if ratio < self.min_align:
                        self.last_reason = f"{site.name} 目录对齐仅 {ratio:.0%}，判为同名异书"
                        self._log(f"[collector:{site.name}] 《{title}》{self.last_reason[4:]}")
                        continue
                    if local_text and not self._verify_text(site, toc, chapters, local_text):
                        continue
                found = (site, toc)
                break
            except Exception as exc:  # noqa: BLE001
                self.last_reason = f"{site.name} 出错：{exc}"
                self._log(f"[collector:{site.name}] 取《{title}》失败：{exc}")
        with self._lock:
            self._cache[key] = found
        if not found and self.last_reason:
            self._log(f"[collector] 《{title}》未通过校验：{self.last_reason}")
        return found

    def _verify_text(self, site: CollectorSite, toc: Toc, chapters: list[dict],
                     local_text: Callable[[int], str]) -> bool:
        """第三道闸：拿本地已有全文的章，与采集站同章比正文指纹。"""
        probes: list[tuple[dict, str]] = []
        for c in chapters:
            if is_note_chapter(c.get("title", "")):
                continue
            text = (local_text(c.get("idx", 0)) or "").strip()
            if len(text) >= 500:
                probes.append((c, text))
            if len(probes) >= 40:
                break
        probes = probes[:: max(1, len(probes) // 3)][:3]
        if not probes:
            self._log(f"[collector:{site.name}] 本地无全文可比对，先信目录")
            return True
        titles = [t for t, _ in toc]
        best = 0.0
        for c, local in probes:
            hint = align.expected_index(c.get("idx", 0), len(chapters), len(toc))
            pos = align.find_chapter(titles, c.get("title", ""), hint)
            if pos < 0:
                continue
            remote = site.content(toc[pos][1])
            if len(remote) < 200:
                continue
            best = max(best, text_overlap(local, remote))
            if best >= self.min_overlap:
                break
        if best < self.min_overlap:
            self.last_reason = (f"{site.name} 正文重合度仅 {best:.0%}"
                                f"（章名对齐 {align_ratio(toc, chapters):.0%}），判为同名异书")
            self._log(f"[collector:{site.name}] 《{titles[0] if titles else ''}》{self.last_reason}")
            return False
        self._log(f"[collector:{site.name}] 正文校验通过：重合 {best:.0%}")
        return True

    # ---------- 取章 ----------
    def chapter(self, title: str, chapters: list[dict], ch: dict,
                local_text: Callable[[int], str] | None = None) -> str:
        """取这一章的正文；拿不到返回空串（原因见 [last_reason]）。"""
        got = self.book(title, chapters, local_text)
        if not got:
            return ""
        site, toc = got
        titles = [t for t, _ in toc]
        hint = align.expected_index(ch.get("idx", 0), len(chapters), len(toc))
        pos = align.find_chapter(titles, ch.get("title", ""), hint)
        if pos < 0:
            self.last_reason = f"采集站目录里没有「{ch.get('title', '')}」"
            return ""
        text = site.content(toc[pos][1])
        if not text:
            self.last_reason = f"{site.name} 这一章取正文失败"
        return _clean(text) if text else ""

    def reset(self) -> None:
        with self._lock:
            self._cache.clear()
