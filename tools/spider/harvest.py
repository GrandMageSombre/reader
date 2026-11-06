"""一条龙：把起点 / 番茄的「全部资源」搬进本地库。

    # 看口径（每个站点到底能拿多少、走哪几条路）
    python -m tools.spider.harvest --plan

    # 起点：书目 → 目录 → 正文（含 VIP 章破解补全）
    python -m tools.spider.harvest --source qidian --what all --limit-books 200 --limit-chapters 500

    # 番茄：把书池从 26 本扩到「榜单 × 维度 + 搜索 + 分类」能到的全部
    python -m tools.spider.harvest --source fanqie --what books --workers 2

    # 两个站一起，跑完直接给阅读器读
    python -m tools.spider.harvest --source both --what all --workers 3
    python -m tools.spider.cli serve --port 8765

设计取舍：

- **断点续跑**是默认行为：书目按 key 去重、目录按 `cataloged_at` 判、正文按 `contents.ok` 判，
  中途 Ctrl-C 再跑一次就是接着来，不会重抓；
- **限速**统一走 `net._throttle()`（`--delay` 全局生效），并发只并发「取正文」；
- **试读不进库**：拿到试读/空正文写 `ok=0` 并留 note，下次还会再攻坚。
"""
from __future__ import annotations

import argparse
import json
import sys
import time

from . import config
from .crack import plan, runner


def _fmt(rep: runner.Report) -> str:
    return json.dumps(rep.as_dict(), ensure_ascii=False, indent=1)


def cmd_plan() -> None:
    print(json.dumps(plan.summary(), ensure_ascii=False, indent=1))
    print("\n策略顺序：")
    for src, strategies in plan.STRATEGIES.items():
        print(f"  {src}: " + " → ".join(f"{s.name}({s.label})" for s in strategies))


def run_source(source: str, a) -> list[runner.Report]:
    reports: list[runner.Report] = []
    if a.what in ("books", "all"):
        reports.append(runner.harvest_books(
            source, limit_books=a.limit_books, pages=a.pages,
            dims=[d for d in (a.dims or "").split(",") if d] or None,
            seeds=[s for s in (a.seeds or "").split(",") if s] or None))
    if a.what in ("catalog", "all"):
        reports.append(runner.harvest_catalog(source, limit_books=a.limit_books))
    if a.what in ("content", "all"):
        reports.append(runner.harvest_content(
            source, limit_chapters=a.limit_chapters, workers=a.workers,
            include_vip=not a.only_free))
    return reports


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="python -m tools.spider.harvest",
        description="起点/番茄全量资源破解搬运（书目·目录·正文·付费章）")
    p.add_argument("--source", default="both", choices=["qidian", "fanqie", "both"],
                   help="抓哪个站（默认两个都抓）")
    p.add_argument("--what", default="all", choices=["books", "catalog", "content", "all"],
                   help="抓哪一层：books=书目 catalog=目录 content=正文 all=全流程")
    p.add_argument("--pages", type=int, default=1, help="每个书目入口抓几页（起点每页 20 本）")
    p.add_argument("--limit-books", type=int, default=None, help="最多处理几本")
    p.add_argument("--limit-chapters", type=int, default=200,
                   help="本轮最多抓几章正文（0=不限）")
    p.add_argument("--workers", type=int, default=2,
                   help="正文并发数；注意站点限速是全局的，调大只增加被封风险")
    p.add_argument("--delay", type=float, default=None, help="请求间隔秒（默认 0.35）")
    p.add_argument("--only-paid", action="store_true",
                   help="只处理 VIP/付费章（免费章跳过）")
    p.add_argument("--only-free", action="store_true",
                   help="只抓免费章（跳过 VIP/付费章）")
    p.add_argument("--signer", default="", help="番茄 App 接口的签名器命令（也认 FANQIE_SIGNER）")
    p.add_argument("--dims", default="", help="番茄榜单维度，逗号分隔（默认只跑空值；实测加维度不扩池）")
    p.add_argument("--seeds", default="", help="番茄搜索种子词，逗号分隔")
    p.add_argument("--plan", action="store_true", help="只打印口径与策略，不抓")
    p.add_argument("--json", default="", help="把统计写到这个文件")
    return p


def main(argv: list[str] | None = None) -> int:
    a = build_parser().parse_args(argv)
    if a.plan:
        cmd_plan()
        return 0
    if a.delay:
        config.DELAY = float(a.delay)
    if a.signer:
        import os

        os.environ["FANQIE_SIGNER"] = a.signer

    sources = ["qidian", "fanqie"] if a.source == "both" else [a.source]
    t0 = time.time()
    all_reports: list[dict] = []
    for src in sources:
        for rep in run_source(src, a):
            all_reports.append(rep.as_dict())

    summary = {
        "sources": sources,
        "what": a.what,
        "seconds": round(time.time() - t0, 1),
        "reports": all_reports,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    if a.json:
        with open(a.json, "w", encoding="utf-8") as fh:
            json.dump(summary, fh, ensure_ascii=False, indent=1)
        print("统计已写入", a.json)
    print("\n提示：跑完 `python -m tools.spider.cli serve --port 8765`，"
          "阅读器「本地爬虫库」就能读到这些书。")

    # 有失败就让 CI/脚本能看见：全 0 成功且有失败 → 退出码 1
    ok = sum(r.get("content_ok", 0) + r.get("catalogs", 0) + r.get("books_new", 0)
             for r in all_reports)
    fail = sum(r.get("content_fail", 0) + r.get("catalog_fail", 0) + r.get("blocked", 0)
               for r in all_reports)
    return 1 if (ok == 0 and fail > 0) else 0


if __name__ == "__main__":
    sys.exit(main())
