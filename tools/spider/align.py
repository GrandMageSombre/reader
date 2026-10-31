"""跨站章节对齐：把起点/番茄的章节，映射到采集站目录里的同一章。

采集站的章名格式和正站不一样（`第1640章 我已非红尘（1+1/2）` vs `我已非红尘`），
而且同名章很多（「感谢」「请假条」），所以对齐要两步：

1. 归一化：去空白、去掉一层层「第N卷/第N章」前缀、去括注、去标点；
2. 定位：先找完全相等的，再找包含关系的；命中多个时取**离预期位置最近**的那个
   （预期位置 = 正站章号 ÷ 正站总章数 × 采集站总章数）。

和 App 侧 `Paywall.findChapter` 保持同一套规则，两边行为必须一致，否则
「手机上自动兜底」和「PC 端批量补全」会对不上。
"""
from __future__ import annotations

import re

NUM = "0-9０-９一二三四五六七八九十百千零两〇廿卅"
PREFIX = re.compile(rf"^(第[{NUM}]+[章节回卷话])+")
PAREN = re.compile(r"[（(][^）)]{0,12}[）)]")
PUNCT = re.compile(r"[，。！？、：；·“”‘’\"'…—\-_.·]")
WS = re.compile(r"[\s\u3000]+")


def norm_title(raw: str) -> str:
    s = WS.sub("", raw or "")
    s = PREFIX.sub("", s)
    if not s.strip():
        s = WS.sub("", raw or "")
    s = PAREN.sub("", s)
    s = PUNCT.sub("", s)
    return s.lower() or (raw or "").strip().lower()


def same_chapter(a: str, b: str) -> bool:
    x, y = norm_title(a), norm_title(b)
    if not x or not y:
        return False
    return x == y or x in y or y in x


def same_book(a: str, b: str) -> bool:
    noise = re.compile(r"[（(][^）)]{0,12}[）)]|全本|完结|校对版|精校版?|无删减|TXT|txt|最新章节")
    clean = lambda s: re.sub(r"[《》\s\u3000·:：]", "", noise.sub("", s or "")).lower()  # noqa: E731
    x, y = clean(a), clean(b)
    return bool(x and y and x == y)


def find_chapter(candidates: list[str], target: str, hint: int = -1) -> int:
    """返回候选目录里最匹配的下标；-1 表示没找到。"""
    if not candidates:
        return -1
    t = norm_title(target)
    if not t:
        return -1

    def nearest(indices: list[int]) -> int:
        if hint < 0:
            return indices[0]
        return min(indices, key=lambda i: abs(i - hint))

    exact = [i for i, c in enumerate(candidates) if norm_title(c) == t]
    if exact:
        return nearest(exact)
    loose = [i for i, c in enumerate(candidates) if same_chapter(c, target)]
    if loose:
        return nearest(loose)
    return -1


def expected_index(local_index: int, local_total: int, remote_total: int) -> int:
    """按比例折算预期位置；总数为 0 时返回 -1（不纠偏）。"""
    if local_total <= 0 or remote_total <= 0:
        return -1
    ratio = (local_index + 0.5) / local_total
    return max(0, min(remote_total - 1, int(ratio * remote_total)))
