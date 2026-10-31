"""签名探针命令行：对比「无签名 / 桩签名 / 真机签名」下接口的返回。

    python -m tools.spider.sign.verify --url "https://.../reading/reader/full?item_id=1"
    python -m tools.spider.sign.verify --url "https://..." --signer "python signer.py"
    python -m tools.spider.sign.verify --url "https://..." --param item_id=1 --param book_id=2
"""
from __future__ import annotations

import argparse
import time

from . import secsdk, verify


def main() -> None:
    ap = argparse.ArgumentParser(prog="python -m tools.spider.sign.verify")
    ap.add_argument("--url", required=True, help="要探测的接口（含 query）")
    ap.add_argument("--signer", default="", help="外部签名器命令（读 stdin JSON、写 stdout JSON）")
    ap.add_argument("--device-id", default="modu-probe", help="签名用的设备 id")
    ap.add_argument("--aid", type=int, default=1967, help="番茄=1967")
    ap.add_argument("--param", action="append", default=[], metavar="K=V",
                    help="参与签名的参数，可多次")
    args = ap.parse_args()

    params = dict(p.split("=", 1) for p in args.param if "=" in p)
    req = secsdk.SignRequest(
        device_id=args.device_id, aid=args.aid, url=args.url,
        params=params, ts_ms=int(time.time() * 1000)
    )

    cases: dict[str, dict[str, str]] = {"无签名": {}}
    stub = secsdk.StubSigner().sign(req)
    cases["桩签名(不会被认可)"] = stub
    if args.signer:
        try:
            cases["真机/外部签名"] = secsdk.ExternalCommandSigner(args.signer).sign(req)
        except Exception as exc:  # noqa: BLE001
            print("外部签名器失败：", exc)

    results = verify.compare(args.url, cases)
    for name, r in results.items():
        flag = "✅ 通过" if r.accepted else "❌ 未通过"
        print(f"\n[{name}] {flag}  HTTP {r.status}  code={r.code}  message={r.message[:60]}")
        print("   body:", r.body[:200].replace("\n", " "))

    print("\n提示：字节系失败也是 HTTP 200，只有 code==0 才算签名有效。")


if __name__ == "__main__":
    main()
