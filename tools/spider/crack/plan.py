"""破解路线：口径、判定、入口表（**纯函数，全部可单测**）。

这一层不碰网络、不碰数据库，只回答四个问题：

1. 拿到的这段正文算不算「全文」？→ [classify]
2. 两条路都给了正文，用哪条？→ [better]
3. 这条策略失败了，下一条试什么？→ [next_strategy]
4. 起点/番茄各自的「全部资源」入口在哪？→ [QIDIAN_ENTRIES] / [FANQIE_RANK_DIMS]

判定阈值与 App 侧 `Paywall.SUSPICIOUS_CHARS`（220）**必须一致**：
否则 PC 端认为「已拿到全文」、手机端认为「还是试读」，两边会互相覆盖。
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import Enum

from ..align import MIN_FULL_CHARS as _MIN_FULL

# ---------------------------------------------------------------- 判定

# 阈值只有一处定义（tools/spider/align.py），App 侧 Crack.MIN_FULL_CHARS 也对齐它
MIN_FULL_CHARS = _MIN_FULL

PREVIEW_MARKS = (
    "订阅本章", "本章为VIP章节", "本章为 VIP 章节", "试读结束", "购买本章",
    "开通会员", "登录后阅读", "付费后可读", "本章未完，请订阅",
)

# 与 App 侧 Crack.LOCK_MARKS 保持同一份字面表（两边都改了才算改）
LOCK_MARKS = (
    "本章尚未购买", "章节已锁定", "需付费", "need_pay", "needPay",
    "chapterLock", "isChapterLock",
)

# 风控/网络类：判 BLOCKED（熔断计数），其余异常判 EMPTY（代码 bug 不该冒充风控）
BLOCK_PATTERN = re.compile(r"(?<![\d.])403(?![\d.])|429|风控|验证码|captcha|timeout|timed out|"
                           r"connection|reset by peer|ssl|proxy", re.I)

# 被 WAF 拦下时拿到的是脚本壳（实测 m.qidian.com 825 字节）：不是正文，也不能算「空」
SCRIPT_MARKS = ("function(", "document.", "_0x", "captcha", "window.__", "eval(", "var ref")

# 采集站/正站正文里混进来的噪声行（不是正文，但要先剥掉再算长度）
JUNK_LINE_MARKS = (
    "本章未完", "请记住本书", "手机版阅读", "笔趣阁", "www.", "http://", "https://",
    "最新网址", "加入书签", "投推荐票", "求月票", "本章完", "（本章完）",
)


class Kind(str, Enum):
    """一章正文的成色。`OK` 是唯一可以直接入库的状态。"""

    OK = "ok"                 # 全文
    SHORT = "short"           # 有正文但短得反常（公告/感言/被截断）
    PREVIEW = "vip_preview"   # 站方试读片段
    LOCKED = "locked"         # 明确说没买/锁了
    EMPTY = "empty"           # 空正文
    BLOCKED = "blocked"       # 被风控/网络失败（不是内容问题）

    @property
    def usable(self) -> bool:
        return self is Kind.OK

    @property
    def need_more(self) -> bool:
        """还要不要继续换下一条路。"""
        return self is not Kind.OK


_RANK = {
    Kind.OK: 5,
    Kind.SHORT: 3,
    Kind.PREVIEW: 2,
    Kind.LOCKED: 1,
    Kind.EMPTY: 0,
    Kind.BLOCKED: -1,
}


def rank(kind: Kind) -> int:
    return _RANK.get(kind, 0)


def strip_junk(text: str) -> str:
    """剥掉采集站/正站的模板噪声行，只留正文。

    长行（≥120 字）不剥：宁可多留一句噪声，也不要把正文段落按行误删。
    """
    out: list[str] = []
    for line in (text or "").splitlines():
        s = line.strip()
        if not s:
            continue
        if len(s) < 120 and any(mark in s for mark in JUNK_LINE_MARKS):
            continue
        out.append(s)
    return "\n".join(out)


def cjk_ratio(text: str) -> float:
    """汉字占比：脚本壳/英文错误页靠它区分（真正文几乎全是汉字）。"""
    body = [ch for ch in (text or "") if not ch.isspace()]
    if not body:
        return 0.0
    cjk = sum(1 for ch in body if "\u4e00" <= ch <= "\u9fff")
    return cjk / len(body)


def looks_like_script(text: str) -> bool:
    """WAF/JS 壳：有脚本特征且几乎没有汉字。"""
    body = text or ""
    if len(body) < 40:
        return False
    return any(mark in body for mark in SCRIPT_MARKS) and cjk_ratio(body) < 0.3


def classify(text: str, note: str = "") -> Kind:
    """一段正文 + 备注 → 成色。

    判定顺序是有讲究的：**风控在最前**（否则 note 里同时有 `chapterLock` 和「验证码」
    时会判成 LOCKED，而 LOCKED 不参与熔断，爬虫会一直打被风控的站点）；
    锁定次之（正文可能是空串）；然后才是试读与长度。
    """
    body = strip_junk(text or "")
    raw_note = note or ""
    if BLOCK_PATTERN.search(raw_note) or ("风控" in raw_note) or ("验证码" in raw_note):
        return Kind.BLOCKED
    if looks_like_script(text or "") or looks_like_script(body):
        return Kind.BLOCKED          # WAF 脚本壳：既不是正文，也不是「这本书没有」
    low = raw_note.lower()
    if any(mark.lower() in low for mark in LOCK_MARKS):
        return Kind.LOCKED
    if not body.strip():
        return Kind.EMPTY
    # 试读标记可能出现在正文前，也可能跟在正文尾（「……试读结束」），两头都扫
    head, tail = body[:600], body[-200:]
    if any(mark in head for mark in PREVIEW_MARKS) or any(
            mark in tail for mark in PREVIEW_MARKS) or any(
            mark in raw_note for mark in PREVIEW_MARKS):
        return Kind.PREVIEW
    if len(body) < MIN_FULL_CHARS:
        return Kind.SHORT
    return Kind.OK


@dataclass
class Cracked:
    """一次取正文的结果：正文 + 成色 + 走的是哪条路。"""

    text: str = ""
    kind: Kind = Kind.EMPTY
    path: str = ""
    note: str = ""

    @property
    def usable(self) -> bool:
        return self.kind.usable

    @classmethod
    def of(cls, text: str, path: str, note: str = "") -> "Cracked":
        return cls(text=strip_junk(text), kind=classify(text, note), path=path, note=note)


def better(a: Cracked, b: Cracked) -> Cracked:
    """两条路的结果取更好的一条：先比成色，再比长度。"""
    if rank(b.kind) > rank(a.kind):
        return b
    if rank(b.kind) == rank(a.kind) and len(b.text) > len(a.text):
        return b
    return a


def join_unique(*parts: str) -> str:
    """把多段正文按顺序拼起来，重复段只留一份。

    用途：一章被站点拆成上下两半（试读 + 后半），或者分页接口各给一段。
    """
    seen: list[str] = []
    for part in parts:
        body = strip_junk(part)
        if not body:
            continue
        if any(body in s for s in seen):
            continue
        if any(s in body for s in seen):
            seen = [s for s in seen if not s in body]
        seen.append(body)
    return "\n".join(seen)


# ---------------------------------------------------------------- 策略

@dataclass(frozen=True)
class Strategy:
    """一条取正文的路：`name` 进库记在 contents.note 里，方便事后统计哪条路最管用。"""

    name: str
    label: str
    source: str
    note: str = ""


QIDIAN_STRATEGIES: tuple[Strategy, ...] = (
    Strategy("web", "PC 章节页 main[id^=c-]", "qidian", "正站 SSR"),
    Strategy("mobile", "m.qidian.com 移动页", "qidian", "移动端正文结构不同，风控口径也不同"),
    Strategy("ajax", "ajax/chapter 接口", "qidian", "带 cookie 的 JSON 正文"),
    Strategy("collector", "采集站按「书名+章名」对齐", "qidian", "全本镜像，VIP 章主要靠这条"),
)

FANQIE_STRATEGIES: tuple[Strategy, ...] = (
    Strategy("web", "reader SSR + 字形还原", "fanqie", "免费章主路"),
    Strategy("app", "App 签名接口 /reading/reader/full", "fanqie", "需要真机签名器"),
    Strategy("batch", "App 批量接口 batch_full", "fanqie", "一次多章，风控更松"),
    Strategy("collector", "采集站按「书名+章名」对齐", "fanqie", "付费章兜底"),
)

STRATEGIES = {
    "qidian": QIDIAN_STRATEGIES,
    "fanqie": FANQIE_STRATEGIES,
}


def strategy_names(source: str) -> tuple[str, ...]:
    return tuple(s.name for s in STRATEGIES.get(source, ()))


def next_strategy(source: str, tried: set[str] | list[str]) -> Strategy | None:
    """还没试过的下一条路；全试完返回 None。"""
    done = set(tried)
    for s in STRATEGIES.get(source, ()):
        if s.name not in done:
            return s
    return None


def strategy_label(source: str, name: str) -> str:
    for s in STRATEGIES.get(source, ()):
        if s.name == name:
            return s.label
    return name


# ---------------------------------------------------------------- 入口

@dataclass(frozen=True)
class Entry:
    """书目的一个入口。`kind` 用于统计覆盖了哪几类聚合页。"""

    name: str
    url: str
    kind: str = "category"

    def page_url(self, page: int, chan_id: str = "", sub_id: str = "") -> str:
        """入口的第 page 页地址（各入口分页写法不同，统一在这里收口）。"""
        raise NotImplementedError


@dataclass(frozen=True)
class QidianEntry(Entry):
    """起点 PC 站入口。分页写法实测两种：
    全站库 `/all/` → `/all/pageN/`；带 chanId 的 → `/all/chanIdX-pageN/`。
    """

    def page_url(self, page: int, chan_id: str = "", sub_id: str = "") -> str:
        u = self.url.rstrip("/")
        if page <= 1:
            return u + "/"
        if "chanId" in u:
            return f"{u}-page{page}/"
        return f"{u}/page{page}/"


QIDIAN_ENTRIES: tuple[QidianEntry, ...] = (
    QidianEntry("全站书库", "https://www.qidian.com/all/", "all"),
    QidianEntry("限时免费", "https://www.qidian.com/free/", "free"),
    QidianEntry("完本", "https://www.qidian.com/all/action1/", "finish"),
    QidianEntry("月票榜", "https://www.qidian.com/rank/yuepiao/", "rank"),
    QidianEntry("阅读指数榜", "https://www.qidian.com/rank/readindex/", "rank"),
    QidianEntry("收藏榜", "https://www.qidian.com/rank/collect/", "rank"),
)
# 实测（2026-11）：全站书库 20 本/页、限时免费 16 本、完本 20 本、月票榜 20 本、
# 阅读指数 20 本、收藏榜 20 本；`/rank/newbook/` 返回 **404**，已从入口表剔除。

# 番茄榜单的维度参数：type 只有 0/1/2 有数据；`dim` 实测（2026-11-06）**加不加都一样**，
# 所以默认只跑空值，`--dims` 显式指定时才逐个试（站点不认的维度返回空，代码会跳过）。
FANQIE_RANK_TYPES: tuple[int, ...] = (0, 1, 2)
FANQIE_RANK_DIMS: tuple[str, ...] = ("",)
FANQIE_RANK_DIMS_ALL: tuple[str, ...] = ("", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10")

# 番茄扩池用的搜索种子：拿这些关键词去撞搜索接口，命中一本就能顺着「同作者/同类」再扩
FANQIE_SEEDS: tuple[str, ...] = (
    "都市", "玄幻", "仙侠", "历史", "科幻", "悬疑", "言情", "校园", "重生", "系统",
    "赘婿", "战神", "脑洞", "无限流", "直播", "签到", "诸天", "赘婿流", "年代", "Doctor",
)


def qidian_bid(url: str) -> str:
    m = re.search(r"/(?:book|chapter)/(\d+)", url or "")
    return m.group(1) if m else ""


def qidian_cid(url: str) -> str:
    m = re.search(r"/chapter/\d+/(\d+)", url or "")
    return m.group(1) if m else ""


def fanqie_id(url_or_id: str) -> str:
    s = url_or_id or ""
    m = re.search(r"/(?:page|book|reader)/(\d+)", s)
    if m:
        return m.group(1)
    digits = "".join(ch for ch in s if ch.isdigit())
    return digits if len(digits) > 5 else s


# ---------------------------------------------------------------- 目标口径

@dataclass(frozen=True)
class Target:
    """一个站点的「全部资源」口径，给 CLI 的 --help 与文档共用。"""

    source: str
    books: str
    catalog: str
    content: str
    paid: str
    entries: tuple[str, ...] = field(default_factory=tuple)


TARGETS: dict[str, Target] = {
    "qidian": Target(
        source="qidian",
        books="137 个筛选 × 50 页 × 20 本 ≈ 13.7 万本上限，另加限免/完本/四个榜单",
        catalog="书籍页一次给全量目录（分卷 + 免费/VIP 标记）",
        content="免费章：PC 章节页 / 移动页 / ajax 三入口任取其一",
        paid="VIP 章：试读 → 移动端/ ajax → 采集站按「书名+章名+预期位置」对齐补全",
        entries=tuple(f"{e.name} {e.url}" for e in QIDIAN_ENTRIES),
    ),
    "fanqie": Target(
        source="fanqie",
        books="榜单 3 档 × 11 个维度 + 搜索种子 + 同作者扩池（web 端只有 26 本，扩池靠 App 接口）",
        catalog="/api/reader/directory/detail 全量目录（含 needPay/isChapterLock）",
        content="reader SSR + 轮廓哈希还原私有区码位（362/362 命中）",
        paid="App 签名接口（X-Argus/X-Gorgon/X-Khronos）→ 批量接口 → 采集站兜底",
        entries=tuple(f"rank type={t} dim={d or '-'}" for t in FANQIE_RANK_TYPES
                      for d in FANQIE_RANK_DIMS_ALL),
    ),
}


def from_exception(source: str, name: str, exc: Exception) -> "Cracked":
    """把异常翻译成成色：网络/风控类 → BLOCKED（参与熔断），其余 → EMPTY（代码 bug）。

    这条区分很重要：以前所有异常都判 BLOCKED，于是一个 `AttributeError` 连撞三次
    就等于「被风控中断」，用户照提示去调 `--delay` 只会白折腾。
    """
    msg = f"{type(exc).__name__}: {exc}"
    if isinstance(exc, (TimeoutError, ConnectionError, OSError)) or BLOCK_PATTERN.search(msg):
        return Cracked(kind=Kind.BLOCKED, path=name, note=msg)
    return Cracked(kind=Kind.EMPTY, path=name, note=f"内部错误 {msg}")


def summary() -> dict:
    """给 `stats`/文档用的口径摘要（也是 CLI --help 的内容来源）。"""
    return {
        src: {
            "books": t.books, "catalog": t.catalog,
            "content": t.content, "paid": t.paid,
            "strategies": [s.name for s in STRATEGIES[src]],
        }
        for src, t in TARGETS.items()
    }
