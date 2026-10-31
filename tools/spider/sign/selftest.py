"""自测：信封、参数规范化、签名器契约。

    python -m tools.spider.sign.selftest
"""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

from . import cipher, envelope as env, secsdk


def check(name: str, cond: bool, detail: str = "") -> bool:
    print(f"{'PASS' if cond else 'FAIL'}  {name}" + (f"  ({detail})" if detail else ""))
    return cond


def main() -> int:
    ok = True

    # 1) 信封往返
    e = env.ArgusEnvelope("AAA", "BBB", "CCC")
    packed = e.pack()
    ok &= check("信封前缀是 0x01", packed[0] == "\x01", repr(packed[:1]))
    back = env.ArgusEnvelope.unpack(packed)
    ok &= check("信封拆解可还原", (back.seg1, back.seg2, back.seg3) == ("AAA", "BBB", "CCC"))

    # 2) 坏信封要报错
    for bad in ("", "xxx", "\x01AAA"):
        try:
            env.ArgusEnvelope.unpack(bad)
            ok &= check(f"坏信封应报错: {bad!r}", False)
        except ValueError:
            ok &= check(f"坏信封报错: {bad!r}", True)

    # 3) Gorgon 形状
    g = env.gorgon_envelope(b"1234567890abcdefghij")
    ok &= check("Gorgon 以 8400 开头且 20 字节", g.startswith("8400") and len(g) == 4 + 40, g)
    ok &= check("Gorgon 可解析回 20 字节", len(env.parse_gorgon(g)) == 20)

    # 4) Khronos 是秒
    ok &= check("Khronos 取秒", env.khronos(1793427183156) == "1793427183")

    # 5) 参数规范化：按 key 升序
    q = env.canonical_query({"b": "2", "a": "1", "c": ""})
    ok &= check("query 规范化按 key 升序", q == "a=1&b=2&c=", q)

    # 6) 桩签名器输出全套头，且信封可拆
    signer = secsdk.StubSigner()
    req = secsdk.SignRequest(device_id="dev-1", url="/reading/reader/full",
                             params={"item_id": "1"}, ts_ms=1793427183156)
    headers = signer.sign(req)
    ok &= check("桩签名给出 Argus/Gorgon/Khronos",
                {"X-Argus", "X-Gorgon", "X-Khronos"} <= set(headers), str(list(headers)))
    ok &= check("桩 Argus 可拆解",
                env.ArgusEnvelope.unpack(headers["X-Argus"]).seg1 != "")

    # 7) 加密可逆（桩）
    c = cipher.XorCipher()
    data = "番茄".encode()
    ok &= check("桩加密可逆", c.decrypt(c.encrypt(data)) == data)
    ok &= check("摘要长度固定", len(c.digest(data)) == 32)

    # 8) 外部签名器契约：写一个临时签名器脚本，验证 JSON 往返回路
    script = Path(tempfile.gettempdir()) / "modu_fake_signer.py"
    script.write_text(
        "import json,sys\n"
        "d=json.load(sys.stdin)\n"
        "print(json.dumps({'X-Argus': d['device_id'], 'X-Khronos': str(d['ts_ms'])}))\n",
        encoding="utf-8",
    )
    ext = secsdk.ExternalCommandSigner(f"{sys.executable} {script}")
    out = ext.sign(req)
    ok &= check("外部签名器按契约工作", out.get("X-Argus") == "dev-1", str(out))

    # 9) 签名器输出坏 JSON 时应报错
    bad_script = Path(tempfile.gettempdir()) / "modu_bad_signer.py"
    bad_script.write_text("print('not json')\n", encoding="utf-8")
    try:
        secsdk.ExternalCommandSigner(f"{sys.executable} {bad_script}").sign(req)
        ok &= check("坏签名器应报错", False)
    except Exception:  # noqa: BLE001
        ok &= check("坏签名器报错", True)

    print("\n结论:", "全部通过" if ok else "有失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
