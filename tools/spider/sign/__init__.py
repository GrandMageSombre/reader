"""签名层：字节系（番茄）/ 阅文系（起点）接口的准入参数构造与验证。

这个包**不假装**能凭空算出加密结果——真实签名靠厂商 native 库（secsdk / QDSign）：
里面用设备指纹、时间戳、随机数、AES/SM3 混合，版本一换算法就变。
所以这里提供的是：

- `envelope.py`：签名头的信封结构（分段 + base64 + 前缀字节），可编解码、可自测；
- `cipher.py`：可插拔的加密实现（真实现由设备侧 .so 提供，这里只放接口 + 测试用桩）；
- `secsdk.py`：把签名计算交给外部命令/真机（adb / Termux ctypes），参数走 JSON；
- `verify.py`：拿到签名后怎么判断「服务器认不认」——这是唯一可靠的验收标准；
- `verify_cli.py`：`python -m tools.spider.sign.verify_cli --url ...` 对比三种签名情况。

用法见 `README.md`。自测：`python -m tools.spider.sign.selftest`
"""

from . import cipher, envelope, secsdk  # noqa: F401

__all__ = ["envelope", "cipher", "secsdk"]
