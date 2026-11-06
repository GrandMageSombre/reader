"""破解层自测：判定、入口、解析、策略链、签名头、番茄扩池。

    python -m tools.spider.crack.selftest

全部用**离线 fixture**：不联网、不写库，所以可以放心在 CI/无网环境跑。
网络相关的部分（起点 cookie、番茄验证码页、真机签名器）另有 `selftest_live` 思路，
但那必须人工盯——这里只保证「我们自己的逻辑」是对的。
"""
from __future__ import annotations

import sys

from . import (appclient, collectors, fanqie as fcrack, plan, qidian as qcrack)
from .. import align, fanqie as base

CHECKS = {"pass": 0, "fail": 0}


def check(name: str, cond: bool, detail: str = "") -> bool:
    CHECKS["pass" if cond else "fail"] += 1
    print(f"{'PASS' if cond else 'FAIL'}  {name}" + (f"  ({detail})" if detail else ""))
    return cond


# ---------------------------------------------------------------- fixtures

BOOK_LIST_HTML = """
<div class="all-book-list"><ul>
 <li>
  <div class="book-img-box"><img src="//img.qidian.com/1.jpg"></div>
  <div class="book-mid-info">
   <h2><a href="//www.qidian.com/book/1035420986/" data-bid="1035420986">诡秘之主</a></h2>
   <p class="author"><a class="name" href="/author/1">爱潜水的乌贼</a>
      <a href="/all/chanId21/">玄幻</a><span>连载</span></p>
   <p class="intro">蒸汽与机械的浪潮中，谁能踏上神座。</p>
   <p class="update"><span>450万字</span></p>
  </div>
 </li>
</ul></div>
"""

CATALOG_HTML = """
<h1>诡秘之主</h1>
<meta property="og:image" content="//img.qidian.com/c.jpg">
<meta property="og:description" content="蒸汽与机械的浪潮中，谁能踏上神座。">
<a class="writer-name" href="/author/1">爱潜水的乌贼</a>
<div class="catalog-volume">
  <h3 class="volume-name">第一卷 小丑</h3>
  <ul>
    <li class="chapter-item"><a class="chapter-name" href="/chapter/1035420986/1001/">第一章 绯红</a></li>
    <li class="chapter-item"><a class="chapter-name" href="/chapter/1035420986/1002/">第二章 情况</a></li>
  </ul>
</div>
<div class="catalog-volume">
  <h3 class="volume-name">第二卷 逆位 <span class="vip">VIP</span></h3>
  <ul>
    <li class="chapter-item"><a class="chapter-name" href="/chapter/1035420986/1003/">第三章 两个世界</a></li>
  </ul>
</div>
"""

FREE_CHAPTER_HTML = """
<html><body><main id="c-1001">
<p>绯红，是一片纯粹的红。那红来自天上，也来自他自己的眼睛。他站在窗前，看着雾蒙蒙的街道，</p>
<p>他做了一个很长的梦，梦里有人在叫他的名字，声音很近，又很远。醒来时煤油灯还亮着，</p>
<p>火苗在玻璃罩里轻轻摇晃。桌上是昨晚剩下的半块面包，和一杯凉透了的红茶。他伸手去摸，</p>
<p>指尖碰到杯壁的时候，忽然听见楼下有人敲门，三长两短，是约定的暗号。他知道，事情开始了。</p>
<p>于是他披上外套，把怀表塞进口袋，推开门，走进了那条终年不见日光的小巷。</p>
<p>巷子尽头是一间挂着铜牌的旧宅，门楣上刻着一行早已被雨水磨平的字，只有最末一个字还认得出。</p>
<p>他抬手敲了三下，门里没有回应，只有风从窗缝里钻出来，带着一股旧纸与铁锈混在一起的味道。</p>
</main></body></html>
"""

PREVIEW_CHAPTER_HTML = """
<html><body><main id="c-1003">
<p>两个世界交错的那一刻，他听见了蒸汽的轰鸣，也听见了远处教堂的钟声。</p>
</main><div>订阅本章</div></body></html>
"""

MOBILE_CHAPTER_HTML = """
<html><body><div id="chapter-content">
<p>绯红，是一片纯粹的红。那红来自天上，也来自他自己的眼睛。他站在窗前，看着雾蒙蒙的街道，</p>
<p>他做了一个很长的梦。梦里有人在叫他的名字，声音很近，又很远。醒来的时候，桌上的煤油灯还亮着，</p>
<p>火苗在玻璃罩里轻轻摇晃。他伸手去摸那只杯子，指尖碰到杯壁的瞬间，忽然听见楼下有人敲门，</p>
<p>三长两短，是约定的暗号。他披上外套，把怀表塞进口袋，推开门，走进了那条终年不见日光的小巷。</p>
<p>巷子尽头是一间挂着铜牌的旧宅，门楣上刻着一行早已被雨水磨平的字，只剩最末一个字还认得出。</p>
<p>他抬手敲了三下，门里没有回应，只有风从窗缝里钻出来，带着旧纸与铁锈混在一起的味道。</p>
</div></body></html>
"""

FULL_TEXT = ("绯红，是一片纯粹的红。那红来自天上，也来自他自己的眼睛。\n"
             "他做了一个很长的梦。梦里有人在叫他的名字，声音很近，又很远。\n"
             "醒来的时候，桌上的煤油灯还亮着，火苗在玻璃罩里轻轻摇晃。" * 3)

FANQIE_DIRECTORY = {"chapterListWithVolume": [
    [{"itemId": "7001", "title": "第一章 直播", "volume_name": "第一卷",
      "needPay": 0, "isChapterLock": False},
     {"itemId": "7002", "title": "第二章 弹幕", "volume_name": "第一卷",
      "needPay": 1, "isChapterLock": False}],
]}


def qidian_fetch(url: str, referer=None):
    """假起点：web 章节页只给试读，移动页给全文，ajax 404。"""
    if "/chapter/" in url and "m.qidian.com" in url:
        return 200, MOBILE_CHAPTER_HTML
    if url.endswith("/book/1035420986/"):
        return 200, CATALOG_HTML
    if "/ajax/" in url:
        return 404, ""
    if "/chapter/" in url:
        cid = plan.qidian_cid(url)
        return 200, (FREE_CHAPTER_HTML if cid == "1001" else PREVIEW_CHAPTER_HTML)
    if "/all/" in url:
        return 200, BOOK_LIST_HTML
    return 404, ""


# ---------------------------------------------------------------- 1. 判定

def test_classify() -> bool:
    ok = True
    ok &= check("长正文 = 全文", plan.classify(FULL_TEXT) is plan.Kind.OK)
    ok &= check("空正文 = 空", plan.classify("") is plan.Kind.EMPTY)
    ok &= check("试读只有一句话 = SHORT",
                plan.classify("两个世界交错的那一刻。") is plan.Kind.SHORT)
    ok &= check("带「订阅本章」= 试读",
                plan.classify(PREVIEW_CHAPTER_HTML) is plan.Kind.PREVIEW)
    ok &= check("note 里写锁定 = LOCKED",
                plan.classify("", "chapterLock") is plan.Kind.LOCKED)
    ok &= check("风控 = BLOCKED", plan.classify("", "被风控（验证码中间页）") is plan.Kind.BLOCKED)
    ok &= check("403 = BLOCKED", plan.classify("", "HTTP 403") is plan.Kind.BLOCKED)

    # 判定顺序：风控必须压过锁定，否则被风控时熔断永远不触发
    ok &= check("风控压过锁定",
                plan.classify("", "chapterLock + 验证码中间页") is plan.Kind.BLOCKED)
    # 「14030 字」里的 403 不是状态码
    ok &= check("14030 字不误判风控", plan.classify(FULL_TEXT, "共 14030 字") is plan.Kind.OK)

    # WAF 脚本壳：不是正文，也不是「这本书没有」
    shell = ('<html><script>!function(){try{var m=document.cookie.match(/x-waf-captcha/);'
             'if(m){window.location.reload()}}catch(e){}}</script></html>')
    ok &= check("WAF 脚本壳判 BLOCKED", plan.classify(shell) is plan.Kind.BLOCKED,
                plan.classify(shell).value)

    # 异常翻译：网络类才算风控，代码 bug 不能冒充
    ok &= check("超时算 BLOCKED",
                plan.from_exception("qidian", "web", TimeoutError("x")).kind is plan.Kind.BLOCKED)
    ok &= check("代码 bug 不算风控",
                plan.from_exception("qidian", "web", ValueError("x")).kind is plan.Kind.EMPTY)

    # 噪声行要被剥掉，但不许把正文也剥了
    noisy = "本章未完，请点击下一页继续阅读\n真正的正文在这里。\n请记住本书首发域名"
    ok &= check("剥掉采集站噪声行", plan.strip_junk(noisy) == "真正的正文在这里。",
                repr(plan.strip_junk(noisy)))

    # 两条路取更好
    a = plan.Cracked.of("短", "web")
    b = plan.Cracked.of(FULL_TEXT, "collector")
    ok &= check("全文胜过短片段", plan.better(a, b).path == "collector")
    c = plan.Cracked.of("", "ajax")
    ok &= check("空的不如短的", plan.better(a, c).path == "web")

    # 多段拼接去重
    joined = plan.join_unique("上半段正文。", "上半段正文。", "下半段正文。")
    ok &= check("重复段只留一份", joined.count("上半段正文。") == 1, joined.replace("\n", "|"))
    return ok


def test_strategies() -> bool:
    ok = True
    ok &= check("起点四条路", plan.strategy_names("qidian") == ("web", "mobile", "ajax", "collector"))
    ok &= check("番茄四条路",
                plan.strategy_names("fanqie") == ("web", "app", "batch", "collector"))
    ok &= check("第一条是 web", plan.next_strategy("qidian", []).name == "web")
    ok &= check("跳过已试", plan.next_strategy("qidian", ["web", "mobile"]).name == "ajax")
    ok &= check("全试完返回 None",
                plan.next_strategy("qidian", ["web", "mobile", "ajax", "collector"]) is None)
    ok &= check("策略名可读", plan.strategy_label("fanqie", "app") == "App 签名接口 /reading/reader/full")
    return ok


def test_ids() -> bool:
    ok = True
    ok &= check("起点 bid", plan.qidian_bid("https://www.qidian.com/chapter/1035420986/1001/")
                == "1035420986")
    ok &= check("起点 cid", plan.qidian_cid("https://www.qidian.com/chapter/1035420986/1001/")
                == "1001")
    ok &= check("番茄 id（页面）", plan.fanqie_id("https://fanqienovel.com/page/7143038691944959011")
                == "7143038691944959011")
    ok &= check("番茄 id（纯数字）", plan.fanqie_id("7143038691944959011") == "7143038691944959011")
    return ok


# ---------------------------------------------------------------- 2. 起点解析与链路

def test_qidian_parse() -> bool:
    ok = True
    books = qcrack.parse_book_list(BOOK_LIST_HTML)
    ok &= check("书单页解析出 1 本", len(books) == 1, str(len(books)))
    ok &= check("书名/作者/分类都对",
                books and books[0]["title"] == "诡秘之主" and books[0]["author"] == "爱潜水的乌贼"
                and "玄幻" in books[0]["category"], str(books[:1]))
    ok &= check("封面补全协议头", books and books[0]["cover"].startswith("https://"))

    from ..qidian import Qidian

    info, chapters = Qidian.parse_catalog(CATALOG_HTML)
    ok &= check("目录解析出 3 章", len(chapters) == 3, str(len(chapters)))
    ok &= check("第一卷是免费章", chapters[0]["vip"] == 0)
    ok &= check("第二卷标了 VIP", chapters[2]["vip"] == 1)
    ok &= check("章节地址带上 bid/cid", chapters[0]["url"].endswith("/chapter/1035420986/1001/"))
    ok &= check("书籍信息取到书名/作者", info["title"] == "诡秘之主" and info["author"] == "爱潜水的乌贼")

    text, note = qcrack.parse_mobile_chapter(MOBILE_CHAPTER_HTML)
    ok &= check("移动页取到多段", text.count("\n") >= 3 and "绯红" in text, repr(text[:20]))

    t2, note2 = qcrack.parse_ajax_chapter('{"code":0,"data":{"content":"<p>一段正文</p>"}}')
    ok &= check("ajax JSON 取到正文", t2 == "一段正文", repr(t2))
    _, n3 = qcrack.parse_ajax_chapter('{"code":-1,"message":"no"}')
    ok &= check("ajax 业务码失败写进备注", "code=-1" in n3, n3)
    _, n4 = qcrack.parse_ajax_chapter("<html>not json</html>")
    ok &= check("ajax 非 JSON 明确报出来", "JSON" in n4, n4)
    return ok


def test_qidian_chain() -> bool:
    ok = True

    class NullCollector:
        """离线替身：采集站这路返回空（selftest 不联网）。"""

        last_reason = "采集站未收录"

        def chapter(self, title: str, chapters: list, ch: dict,
                    local_text=None) -> str:
            return ""

    crack = qcrack.QidianCrack(fetch=qidian_fetch, collector=NullCollector(),
                               log=lambda *_: None)
    routes = ("web", "mobile", "ajax")      # 只测站内三路，collector 单独看

    got_books = list(crack.books(pages=1))
    ok &= check("入口遍历拿到书", len(got_books) >= 1 and got_books[0]["bid"] == "1035420986",
                str(len(got_books)))

    info, chapters = crack.catalog("1035420986")
    ok &= check("目录 3 章", len(chapters) == 3)

    free = crack.content(chapters[0], book_title="诡秘之主", chapters=chapters, strategies=routes)
    ok &= check("免费章走 web 就拿到全文", free.usable and free.path == "web", free.path)

    vip = crack.content(chapters[2], book_title="诡秘之主", chapters=chapters, strategies=routes)
    ok &= check("VIP 章 web 只给试读 → 自动换路", vip.path == "mobile", vip.path)
    ok &= check("换路后拿到全文", vip.usable, vip.kind.value)

    # 全部路都失败时，返回「最好的一条」并说明试过哪些路
    dead = qcrack.QidianCrack(fetch=lambda url, referer=None: (403, ""),
                              collector=NullCollector(), log=lambda *_: None)
    none = dead.content(chapters[2], book_title="诡秘之主", chapters=chapters, strategies=routes)
    ok &= check("全失败时不冒充成功", not none.usable)
    ok &= check("全失败时留下原因", "HTTP 403" in none.note or "试过" in none.note, none.note)

    # collector 这路：替身返回空 → 判 EMPTY，不会把空串当全文
    col = crack.content(chapters[2], book_title="诡秘之主", chapters=chapters,
                        strategies=("collector",))
    ok &= check("采集站没收录时不写成功", not col.usable and col.path == "collector", col.note)
    return ok


# ---------------------------------------------------------------- 3. 番茄

def test_fanqie_parse() -> bool:
    ok = True
    chapters = base.Fanqie.parse_directory(FANQIE_DIRECTORY)
    ok &= check("番茄目录解析出 2 章", len(chapters) == 2, str(len(chapters)))
    ok &= check("付费标记读出来", chapters[1]["needPay"] == 1)

    # 对齐率：本地 3 章、采集站 3 章，章名一致
    toc = [("第一章 绯红", "http://x/1.html"), ("第二章 情况", "http://x/2.html"),
           ("第三章 两个世界", "http://x/3.html")]
    local = [{"idx": 0, "title": "第一章 绯红"}, {"idx": 1, "title": "第二章 情况"},
             {"idx": 2, "title": "第三章 两个世界"}]
    ok &= check("目录对齐率 100%", abs(collectors.align_ratio(toc, local) - 1.0) < 1e-6)
    local2 = [{"idx": 0, "title": "第一章 绯红"}, {"idx": 1, "title": "完本感言"},
              {"idx": 2, "title": "第九百章 无关"}]
    ok &= check("对不上的目录对齐率会掉下去", collectors.align_ratio(toc, local2) < 0.8,
                f"{collectors.align_ratio(toc, local2):.2f}")
    ok &= check("空目录对齐率 0", collectors.align_ratio([], local) == 0.0)

    # 第三道闸：正文指纹（同名异书的正文对不上）
    same = ("绯红，是一片纯粹的红。那红来自天上，也来自他自己的眼睛。"
            "他站在窗前，看着雾蒙蒙的街道，听见楼下有人敲门。") * 10
    shifted = "，".join([same[:20], same[20:]])
    other = ("鼎盛大厦。方平径直进了公司，此刻的远方总算是有了前台。" * 20)
    ok &= check("同一段正文指纹重合度高", collectors.text_overlap(same, shifted) > 0.9,
                f"{collectors.text_overlap(same, shifted):.0%}")
    ok &= check("不同正文指纹重合度低", collectors.text_overlap(same, other) < 0.1,
                f"{collectors.text_overlap(same, other):.0%}")
    ok &= check("空文本不炸", collectors.text_overlap("", same) == 0.0)
    return ok


def test_fanqie_books() -> bool:
    """扩池：三个维度都返回两本重叠的书 → 去重后两本。"""
    def web_json(path: str) -> dict:
        if "dim=3" in path:
            return {"data": {"list": []}}          # 站点不认的维度：空
        return {"data": {"list": [
            {"bookId": "1", "bookName": "都市至尊战神", "author": "甲", "abstract": "简介",
             "thumbUri": "http://img/1", "category": "男生/都市"},
            {"bookId": "2", "bookName": "人生三十", "author": "乙", "abstract": "简介",
             "thumbUri": "http://img/2", "category": "男生/都市"},
        ]}}

    crack = fcrack.FanqieCrack(web_json=web_json, log=lambda *_: None)
    books = list(crack.books(dims=["", "3"], seeds=None))
    ok = check("榜单 × 维度去重后 2 本", len(books) == 2, str(len(books)))
    ok &= check("跳过空维度不报错", all(b["bookId"] in ("1", "2") for b in books))
    ok &= check("记下从哪来的", books and books[0]["how"].startswith("rank:"), books[0]["how"])
    return ok


def test_fanqie_chain() -> bool:
    ok = True
    calls = {"n": 0}

    def fetch(url: str, headers: dict):
        calls["n"] += 1
        if "/reading/reader/full" in url:
            return 200, '{"code":0,"data":{"chapterData":{"content":"%s"}}}' % FULL_TEXT.replace("\n", "\\n")
        return 200, '{"code":-1,"message":"no"}'

    client = appclient.AppClient(fetch=fetch, log=lambda *_: None)
    app = appclient.FanqieApp(client, log=lambda *_: None)
    headers = client.headers("/reading/reader/full", {"item_id": "7002"})
    ok &= check("签名头齐全",
                {"X-Argus", "X-Gorgon", "X-Khronos"} <= set(headers), str(sorted(headers)))
    ok &= check("Khronos 是秒级时间戳", headers["X-Khronos"].isdigit() and
                len(headers["X-Khronos"]) in (10, 11), headers["X-Khronos"])

    crack = fcrack.FanqieCrack(app=app, log=lambda *_: None)
    got = crack.content("7002", book_title="人生三十", chapters=[], strategies=["app"])
    ok &= check("App 接口拿到全文", got.usable and got.path == "app", got.kind.value)

    # 业务码非 0 不能当成功
    bad = appclient.parse_api(200, '{"code":-1,"message":"bad sign"}')
    ok &= check("业务码 -1 判失败", not bad.ok and bad.message == "bad sign", bad.message)
    empty = appclient.parse_api(200, "")
    ok &= check("空 body 判失败并说明签名", not empty.ok and "签名" in empty.message, empty.message)
    good = appclient.parse_api(200, '{"code":0,"data":{"bookName":"x"}}')
    ok &= check("code=0 且有 data 判成功", good.ok and good.data["bookName"] == "x")
    return ok


def test_runner_pipeline() -> bool:
    """编排层回归：`ok` 语义、by_path 统计、风控熔断、空目录不标已抓。

    这一段以前没有任何覆盖 —— 于是「番茄没注入 web 会话」「空目录把书标成已抓」
    两个生产级 bug 能在 63 条全绿的情况下活下来。
    """
    import tempfile
    from pathlib import Path as _Path

    from .. import config, store
    from . import runner

    ok = True
    old_db = config.DB_PATH
    config.DB_PATH = _Path(tempfile.mkdtemp()) / "selftest.db"
    old_crack = runner._crack_for
    try:
        with store.connect() as conn:
            store.upsert_book(conn, key="qidian:1", source="qidian", native_id="1",
                              title="测试书", url="https://www.qidian.com/book/1/")
            store.replace_chapters(conn, "qidian:1", [
                (0, "第一章 免费", "https://www.qidian.com/chapter/1/10/", "10", 0),
                (1, "第二章 付费", "https://www.qidian.com/chapter/1/11/", "11", 1),
                (2, "第三章 风控", "https://www.qidian.com/chapter/1/12/", "12", 1),
            ])

        class FakeCrack:
            def content(self, chapter, **kw):
                idx = chapter["idx"]
                if idx == 0:
                    return plan.Cracked(text=FULL_TEXT, kind=plan.Kind.OK, path="web")
                if idx == 1:
                    return plan.Cracked(kind=plan.Kind.EMPTY, path="collector",
                                        note="采集站未收录")
                return plan.Cracked(kind=plan.Kind.BLOCKED, path="web", note="HTTP 403")

        runner._crack_for = lambda *a, **k: FakeCrack()
        rep = runner.harvest_content("qidian", limit_chapters=3, workers=2,
                                     log=lambda *_: None)
        ok &= check("正文：成功 1 章", rep.content_ok == 1, str(rep.content_ok))
        ok &= check("正文：失败 1 章", rep.content_fail == 1, str(rep.content_fail))
        ok &= check("正文：风控 1 次", rep.blocked == 1, str(rep.blocked))
        ok &= check("by_path 记下主路", rep.by_path.get("web") == 1, str(rep.by_path))

        with store.connect() as conn:
            good = store.get_content(conn, "qidian:1", 0)
            bad = store.get_content(conn, "qidian:1", 1)
            blocked = store.get_content(conn, "qidian:1", 2)
        ok &= check("全文写 ok=1", good is not None and good["ok"] == 1)
        ok &= check("没拿到的不写 ok=1", bad is not None and bad["ok"] == 0, bad["note"])
        ok &= check("风控章留下 blocked 痕迹",
                    blocked is not None and "blocked" in (blocked["note"] or ""),
                    blocked["note"] if blocked else "no row")

        # 熔断：连续 3 章被风控 → 本轮收工（而不是继续打）
        with store.connect() as conn:
            conn.execute("DELETE FROM contents")

        class AllBlocked:
            def content(self, chapter, **kw):
                return plan.Cracked(kind=plan.Kind.BLOCKED, path="web", note="HTTP 403")

        runner._crack_for = lambda *a, **k: AllBlocked()
        rep2 = runner.harvest_content("qidian", limit_chapters=3, workers=1,
                                      log=lambda *_: None)
        ok &= check("连续风控后收工", rep2.blocked >= 1 and "风控中断" in rep2.note, rep2.note)

        # 目录层：空目录不能把书标成「已抓」
        with store.connect() as conn:
            # replace_chapters 会顺手写 cataloged_at，这里复位成「没抓过」
            conn.execute("UPDATE books SET cataloged_at=0")

        class EmptyCatalog:
            def catalog(self, _id):
                return {}

            last_error = ""

        runner._crack_for = lambda *a, **k: EmptyCatalog()
        rep3 = runner.harvest_catalog("qidian", log=lambda *_: None)
        ok &= check("空目录计失败", rep3.catalog_fail == 1 and rep3.catalogs == 0,
                    f"fail={rep3.catalog_fail} ok={rep3.catalogs}")
        with store.connect() as conn:
            still = store.books(conn, "qidian", missing_catalog=True)
        ok &= check("空目录后仍算「没抓过」，下次会重试", len(still) == 1, str(len(still)))
    finally:
        runner._crack_for = old_crack
        config.DB_PATH = old_db
    return ok


def main() -> int:
    ok = True
    for fn in (test_classify, test_strategies, test_ids, test_qidian_parse,
               test_qidian_chain, test_fanqie_parse, test_fanqie_books,
               test_fanqie_chain, test_runner_pipeline):
        print(f"\n== {fn.__name__}")
        ok &= bool(fn())
    total = CHECKS["pass"] + CHECKS["fail"]
    print(f"\n结论：{CHECKS['pass']}/{total} 通过" + ("" if ok else "，有失败项"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
