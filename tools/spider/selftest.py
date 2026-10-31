"""自测：章节对齐 + 正文指纹。

    python -m tools.spider.selftest
"""
from __future__ import annotations

import sys

from tools.spider import align
from tools.spider.vipfill import _shingles, is_note_chapter


def check(name: str, cond: bool, detail: str = "") -> bool:
    print(f"{'PASS' if cond else 'FAIL'}  {name}" + (f"  ({detail})" if detail else ""))
    return cond


def main() -> int:
    ok = True

    # 1) 章名归一化
    ok &= check("去掉第N章", align.norm_title("第一章 剧本不对") == "剧本不对")
    ok &= check("多层卷章前缀", align.norm_title("第11卷 第3章 穷文富武") == "穷文富武")
    ok &= check("去括注", align.norm_title("第一千六百四十章 我已非红尘。（1+1/2）") == "我已非红尘")
    ok &= check("章名本身就是第3章时不能剥空", align.norm_title("第3章") == "第3章")

    # 2) 同一章判定
    ok &= check("全角数字也能对上", align.same_chapter("第三章 穷文富武", "第3章 穷文富武"))
    ok &= check("不同章不能对上", not align.same_chapter("第三章 穷文富武", "第四章 武道社"))

    # 3) 书名：去掉噪声后缀后完全相等，不许包含即算
    ok &= check("校对版算同一本", align.same_book("全球高武", "全球高武（校对版）"))
    ok &= check("同人作不算同一本", not align.same_book("全球高武", "全球高武之死气面板"))
    ok &= check("《》不影响", align.same_book("《玄鉴仙族》", "玄鉴仙族"))

    # 4) 同名章按预期位置纠偏
    toc = ["第一章 开局", "感谢", "第二章 出发", "感谢"]
    ok &= check("hint=3 取后面那个感谢", align.find_chapter(toc, "感谢", 3) == 3)
    ok &= check("hint=0 取前面那个感谢", align.find_chapter(toc, "感谢", 0) == 1)
    ok &= check("空目录返回 -1", align.find_chapter([], "第一章 开局") == -1)

    # 5) 预期位置折算
    ok &= check("首章折算到 0 附近", align.expected_index(0, 1479, 1494) == 0)
    ok &= check("末章折算到末尾附近", align.expected_index(1478, 1479, 1494) >= 1490)
    ok &= check("总数未知时不纠偏", align.expected_index(10, 0, 100) == -1)

    # 6) 正文指纹：差一个标点不能崩（等距抽样会崩，连续窗口不会）
    base = "底下三楼。快，四楼干起来了！一挑三百，上去看看！呼喝声在底下三层楼传开。" * 3
    shifted = "，".join([base[:10], base[10:]])   # 插入一个标点
    a, b = _shingles(base), _shingles(shifted)
    overlap = len(a & b) / max(1, min(len(a), len(b)))
    ok &= check("插入标点后重合度仍高", overlap > 0.9, f"{overlap:.0%}")

    other = "鼎盛大厦。方平径直进了公司。此刻的远方，总算是有了前台。" * 3
    c = _shingles(other)
    cross = len(a & c) / max(1, min(len(a), len(c)))
    ok &= check("无关正文重合度接近 0", cross < 0.1, f"{cross:.0%}")

    # 7) 作者话/公告要能被识别（不参与对齐与补全）
    ok &= check("感言被识别", is_note_chapter("感谢诸位！"))
    ok &= check("公告被识别", is_note_chapter("请假一天，明天两更"))
    ok &= check("正常章不被误杀", not is_note_chapter("第一千六百四十章 我已非红尘"))

    print("\n结论:", "全部通过" if ok else "有失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
