"""验证「签名/接口能不能过」——唯一可靠的验收标准。

签名对不对，不要靠猜：打一个**需要签名**的接口，看业务码。
字节系接口的失败形态很统一：`{"code":-1,"message":"..."}` 或空 body；
成功则是 `code: 0` 且 `data` 有内容。

所以这里给出一个统一探针，用来对比「无签名 / 桩签名 / 真机签名」三种情况的返回。
"""
from __future__ import annotations

import gzip
import json
import urllib.error
import urllib.request
from dataclasses import dataclass

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")


@dataclass
class ProbeResult:
    status: int
    body: str
    code: int | None
    message: str

    @property
    def accepted(self) -> bool:
        """业务层是否被接受（HTTP 2xx + code 为 0 或没有 code 字段但有内容）"""
        if not (200 <= self.status < 300):
            return False
        if self.code is None:
            return bool(self.body.strip())
        return self.code == 0


def probe(url: str, headers: dict[str, str] | None = None,
          timeout: float = 20.0) -> ProbeResult:
    h = {"User-Agent": UA, "Accept": "application/json,*/*",
         "Accept-Encoding": "gzip"}
    if headers:
        h.update(headers)
    try:
        req = urllib.request.Request(url, headers=h)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            if resp.headers.get("Content-Encoding") == "gzip":
                raw = gzip.decompress(raw)
            body = raw.decode("utf-8", "replace")
            status = resp.status
    except urllib.error.HTTPError as exc:
        body = (exc.read() or b"").decode("utf-8", "replace")
        return ProbeResult(exc.code, body, None, f"HTTP {exc.code}")
    except Exception as exc:  # noqa: BLE001
        return ProbeResult(-1, "", None, type(exc).__name__)

    code, msg = None, ""
    try:
        obj = json.loads(body)
        if isinstance(obj, dict):
            code = obj.get("code")
            msg = str(obj.get("message") or obj.get("msg") or "")
    except Exception:  # noqa: BLE001
        pass
    return ProbeResult(status, body, code, msg)


def compare(url: str, header_sets: dict[str, dict[str, str]]) -> dict[str, ProbeResult]:
    """一次跑多组头，用来对比哪种签名被接受。"""
    return {name: probe(url, h) for name, h in header_sets.items()}
