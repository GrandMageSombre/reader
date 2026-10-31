"""可插拔的加密实现。

真实场景里，`X-Argus` 的密文段由**厂商 native 库**算出：
字节 secsdk（`libsign.so`）里的 Argus/Gorgon 实现、阅文客户端的 QDSign，
都是「设备指纹 + 时间戳 + 随机数 → AES/SM3 → 定长输出」，
并且**随版本变化**。所以这里只定义接口：

- `Cipher.encrypt(data) -> bytes`：段加密
- `Cipher.digest(data) -> bytes`：段签名

真正能过服务器的实现只有两种来源：
1. 设备侧（推荐）：把 APP 里的 `.so` 抽出来，在真机/Termux 里调它（见 `secsdk.py`）；
2. 逆向重写：把 so 的反汇编翻成等价算法，版本升级要跟着改。

本文件里的 `XorCipher` / `HmacCipher` 只用于**测试与联调**——它们能验证
「信封拼装、参数规范化、传输链路」是否打通，但服务器不会认。
"""
from __future__ import annotations

import hashlib
import hmac
from typing import Protocol


class Cipher(Protocol):
    name: str

    def encrypt(self, data: bytes) -> bytes: ...

    def digest(self, data: bytes) -> bytes: ...


class XorCipher:
    """仅供联调的假实现：可逆、可预测，方便单测。"""

    name = "xor(stub)"

    def __init__(self, key: bytes = b"modu-stub-key"):
        self.key = key

    def encrypt(self, data: bytes) -> bytes:
        return bytes(b ^ self.key[i % len(self.key)] for i, b in enumerate(data))

    def decrypt(self, data: bytes) -> bytes:
        return self.encrypt(data)

    def digest(self, data: bytes) -> bytes:
        return hashlib.md5(self.key + data).hexdigest().encode()


class HmacCipher:
    """标准 HMAC-SHA256 桩：结构对、密钥不对（服务器不认），用于验证链路。"""

    name = "hmac-sha256(stub)"

    def __init__(self, key: bytes = b"modu-stub-key"):
        self.key = key

    def encrypt(self, data: bytes) -> bytes:
        # 不是真加密，只做混淆，避免误以为能过服务器
        return bytes(b ^ self.key[i % len(self.key)] for i, b in enumerate(data))

    def digest(self, data: bytes) -> bytes:
        return hmac.new(self.key, data, hashlib.sha256).digest()


def aes_cbc_available() -> bool:
    try:
        import cryptography  # noqa: F401
    except Exception:  # noqa: BLE001
        return False
    return True


class AesCbcCipher:
    """真 AES-CBC（需要 `cryptography`）。密钥/IV 仍须来自设备侧，否则过不了。"""

    name = "aes-128-cbc"

    def __init__(self, key: bytes, iv: bytes):
        if len(key) != 16 or len(iv) != 16:
            raise ValueError("AES-128 需要 16 字节 key 与 iv")
        self.key, self.iv = key, iv

    def encrypt(self, data: bytes) -> bytes:
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

        pad = 16 - len(data) % 16
        enc = Cipher(algorithms.AES(self.key), modes.CBC(self.iv)).encryptor()
        return enc.update(data + bytes([pad]) * pad) + enc.finalize()

    def digest(self, data: bytes) -> bytes:
        return hmac.new(self.key, data, hashlib.sha256).digest()
