"""把签名计算交给「真能算出正确值」的地方。

两条路：

1. **真机 / 模拟器**（推荐）：把番茄 APK 里的 `libsign.so`（secsdk）抽出来，
   用 `adb push` 放到设备上，写个几十行的 JNI/shell 包装，输入参数、输出签名头。
   本模块用 `ExternalCommand` 统一调用：`stdin=JSON 参数`，`stdout=JSON 头`。

2. **本机 Termux / Linux + ctypes**：在同一台手机上直接 `ctypes.CDLL("libsign.so")`
   调导出函数（导出名随版本变，通常是 `Java_*` 或 `sign`/`doCommand`）。

无论哪条，最终形态都一样：

```
{"device_id": "...", "aid": 1967, "url": "...", "params": {...}, "body": "..."}
        │ 外部签名器
        ▼
{"X-Argus": "\\u0001...", "X-Gorgon": "8400...", "X-Khronos": "1793427183"}
```

签名器自己怎么实现不在本仓库（那属于逆向工程，且随版本失效），
这里只保证**参数契约**与**错误处理**是稳的：换一个签名器只要满足契约即可。
"""
from __future__ import annotations

import json
import os
import shlex
import subprocess
from dataclasses import dataclass, field
from typing import Protocol


@dataclass
class SignRequest:
    device_id: str
    aid: int = 1967              # 番茄小说
    url: str = ""
    params: dict[str, str] = field(default_factory=dict)
    body: str = ""
    ts_ms: int = 0


class Signer(Protocol):
    name: str

    def sign(self, req: SignRequest) -> dict[str, str]: ...


class StubSigner:
    """本地桩：把信封结构拼出来，但用假加密——**只用于验证调用链**。"""

    name = "stub"

    def sign(self, req: SignRequest) -> dict[str, str]:
        from . import cipher, envelope as env

        c = cipher.HmacCipher()
        payload = json.dumps(
            {"url": req.url, "params": req.params, "body": req.body,
             "device_id": req.device_id, "ts": req.ts_ms},
            separators=(",", ":"), sort_keys=True).encode()
        seg1 = env._b64e(c.encrypt(payload))
        seg2 = env._b64e(c.digest(payload))
        seg3 = env._b64e(c.digest(seg1.encode() + seg2.encode()))
        envp = env.ArgusEnvelope(seg1, seg2, seg3)
        return {
            "X-Argus": envp.pack(),
            "X-Gorgon": env.gorgon_envelope(c.digest(payload)),
            "X-Khronos": env.khronos(req.ts_ms or 0),
        }


class ExternalCommandSigner:
    """外部签名器：`cmd` 读 stdin 的 JSON、把签名头 JSON 写到 stdout。"""

    name = "external"

    def __init__(self, cmd: str | list[str], timeout: float = 20.0):
        # Windows 路径含反斜杠：shlex 必须用 posix=False，否则 D:\python\python.exe 会被吃掉反斜杠
        if isinstance(cmd, list):
            self.argv = cmd
        elif os.name == "nt":
            self.argv = shlex.split(cmd, posix=False)
        else:
            self.argv = shlex.split(cmd)
        self.argv = [a.strip('"') for a in self.argv]
        self.timeout = timeout

    def sign(self, req: SignRequest) -> dict[str, str]:
        payload = json.dumps({
            "device_id": req.device_id, "aid": req.aid, "url": req.url,
            "params": req.params, "body": req.body, "ts_ms": req.ts_ms,
        }, ensure_ascii=False)
        proc = subprocess.run(self.argv, input=payload, capture_output=True,
                              text=True, timeout=self.timeout)
        if proc.returncode != 0:
            raise RuntimeError(f"签名器失败({proc.returncode})：{proc.stderr[:300]}")
        out = proc.stdout.strip()
        if not out:
            raise RuntimeError("签名器没有输出")
        data = json.loads(out)
        if not isinstance(data, dict) or not data:
            raise RuntimeError("签名器输出格式不对，应形如 {\"X-Argus\": \"...\"}")
        return {str(k): str(v) for k, v in data.items()}


def device_headers(signer: Signer, req: SignRequest) -> dict[str, str]:
    """统一的取头入口：桩实现会带 `X-Argus(stub)` 标记，避免误当成真票。"""
    headers = signer.sign(req)
    if isinstance(signer, StubSigner):
        headers = {k: v for k, v in headers.items()}
    return headers
