"""签名头的信封结构。

字节系（番茄 / 抖音同源）的 `X-Argus` 是一个**分段信封**：

```
X-Argus = 0x01 + base64(seg1) + 0x02 + base64(seg2) + 0x03 + base64(seg3)
```

- seg1：设备参数（AES-CBC 加密的 protobuf，密钥由设备 id/随机数派生）
- seg2：与 seg1 相关的时间/随机段
- seg3：前两段的签名（MD5/HMAC 类）

`X-Gorgon` 是另一套：`0x84 0x00` + 20 字节定长（含时间、cookie、params 摘要），
`X-Khronos` 是秒级时间戳，`X-Helios`/`X-Ladon` 是更后的补充头。

本模块只实现**信封的拼装与拆解**（这部分是确定的、可测的），加密由 `cipher.py` 注入。
"""
from __future__ import annotations

import base64
from dataclasses import dataclass

MAGIC = (0x01, 0x02, 0x03)


def _b64e(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii")


def _b64d(text: str) -> bytes:
    pad = "=" * (-len(text) % 4)
    return base64.b64decode(text + pad)


@dataclass
class ArgusEnvelope:
    """三段式信封。seg1/seg2 是密文，seg3 是签名。"""

    seg1: str
    seg2: str
    seg3: str

    def pack(self) -> str:
        return (chr(MAGIC[0]) + self.seg1 +
                chr(MAGIC[1]) + self.seg2 +
                chr(MAGIC[2]) + self.seg3)

    @staticmethod
    def unpack(value: str) -> "ArgusEnvelope":
        parts = value.split(chr(MAGIC[1]))
        if len(parts) != 2 or not parts[0].startswith(chr(MAGIC[0])):
            raise ValueError("不是 Argus 信封：缺少 0x01/0x02 分段")
        head = parts[0][1:]
        tail = parts[1].split(chr(MAGIC[2]))
        if len(tail) != 2:
            raise ValueError("不是 Argus 信封：缺少 0x03 分段")
        return ArgusEnvelope(head, tail[0], tail[1])


def gorgon_envelope(payload: bytes) -> str:
    """X-Gorgon：`0x84 0x00` + 20 字节（hex 输出）。"""
    body = payload[:20].ljust(20, b"\x00")
    return "8400" + body.hex()


def parse_gorgon(value: str) -> bytes:
    if not value.startswith("8400"):
        raise ValueError("不是 Gorgon 头：应以 8400 开头")
    return bytes.fromhex(value[4:])


def khronos(ts_ms: int) -> str:
    """X-Khronos：秒级时间戳（字符串）。"""
    return str(ts_ms // 1000)


def canonical_query(params: dict[str, str]) -> str:
    """参与签名的 query 规范化：按 key 升序、空值保留、不做额外编码。

    两站都用「排序后的原始 query」参与摘要——顺序错了签名必然不过，
    所以这里单独抽出来并加测试。
    """
    return "&".join(f"{k}={params[k]}" for k in sorted(params))
