"""番茄 App 接口客户端：签名头装配 + 业务码判定 + 可注入传输层。

番茄 web 端只暴露极少数接口（榜单 26 本、书籍页、目录、免费章 SSR）；
「按分类列书 / 搜索 / 批量正文 / 付费章」全在 App 接口上，而这些接口要求：

| 头 | 含义 |
|----|------|
| `X-Argus` | 三段信封：设备参数密文 + 时间随机段 + 签名段（`sign/envelope.py`） |
| `X-Gorgon` | `8400` + 20 字节（时间 + cookie/params 摘要） |
| `X-Khronos` | 秒级时间戳 |
| `X-Helios` / `X-Ladon` | 新版补充头（部分接口才校验） |

签名值只能由 `libsign.so` 算（native、随版本变化），所以这里**不自己实现加密**：
签名器通过 `sign/secsdk.py` 注入，命令行参数 `--signer "python signer.py"` 或环境变量
`FANQIE_SIGNER` 指定。没有签名器时用 `StubSigner`，接口会返回非 0 业务码 —— 
这不是 bug，是「没装签名器」的明确信号，[AppClient.last_error] 里能看到。

判定一律看**业务码**：字节系失败也是 HTTP 200，`code != 0` 就是没通过。
"""
from __future__ import annotations

import json
import os
import time
import urllib.parse
from dataclasses import dataclass, field
from typing import Callable

from .. import config
from ..sign import envelope, secsdk

APP_HOSTS = (
    "https://api5-normal-sinfonlineb.fqnovel.com",
    "https://api5-normal-c-lq.fqnovel.com",
)

UA_APP = ("com.dragon.read/6.4.0 (Linux; U; Android 13; zh_CN; Pixel 7; "
          "Build/TQ3A.230805.001; Cronet/58.0.2991.0)")

Fetch = Callable[[str, dict], tuple[int, str]]


@dataclass
class ApiResult:
    ok: bool = False
    code: int | None = None
    message: str = ""
    data: dict = field(default_factory=dict)
    raw: str = ""


class AppClient:
    """一个 AppClient 复用一份签名器与设备号（签名与设备绑定，别每请求都换）。"""

    def __init__(self, host: str = APP_HOSTS[0], signer=None, device_id: str = "",
                 aid: int = 1967, fetch: Fetch | None = None, log=print,
                 timeout: float = 20.0):
        self.host = host.rstrip("/")
        self.aid = int(aid)
        self.device_id = device_id or os.environ.get("FANQIE_DEVICE_ID", "modu-local-device")
        self.signer = signer or build_signer(log=log)
        self._fetch = fetch or self._default_fetch(timeout)
        self.log = log
        self.last_error = ""

    # ---------------- 传输 ----------------
    @staticmethod
    def _default_fetch(timeout: float) -> Fetch:
        import urllib.error
        import urllib.request

        def fetch(url: str, headers: dict) -> tuple[int, str]:
            req = urllib.request.Request(url, headers=headers)
            try:
                with urllib.request.urlopen(req, timeout=timeout) as resp:
                    raw = resp.read()
                    if resp.headers.get("Content-Encoding") == "gzip":
                        import gzip

                        raw = gzip.decompress(raw)
                    return resp.status, raw.decode("utf-8", "replace")
            except urllib.error.HTTPError as exc:
                return exc.code, (exc.read() or b"").decode("utf-8", "replace")
            except Exception as exc:  # noqa: BLE001
                return -1, f'{{"code":-1,"message":"{type(exc).__name__}"}}'

        return fetch

    # ---------------- 签名 ----------------
    def headers(self, path: str, params: dict[str, str]) -> dict[str, str]:
        ts_ms = int(time.time() * 1000)
        req = secsdk.SignRequest(
            device_id=self.device_id, aid=self.aid,
            url=f"{self.host}{path}", params=params, ts_ms=ts_ms,
        )
        signed = secsdk.device_headers(self.signer, req)
        base = {
            "User-Agent": UA_APP,
            "Accept": "application/json",
            "Accept-Encoding": "gzip",
        }
        # 时间戳放在前后都能被签名器覆盖：签名器给的值优先（它是权威）
        base.setdefault("X-Khronos", str(ts_ms // 1000))
        merged = dict(base)
        merged.update({k: str(v) for k, v in signed.items() if str(v)})
        merged.setdefault("X-Khronos", str(ts_ms // 1000))
        return {k: v for k, v in merged.items() if v}

    def url_for(self, path: str, params: dict[str, str]) -> str:
        q = envelope.canonical_query(params)
        return f"{self.host}{path}" + (f"?{q}" if q else "")

    @staticmethod
    def encode_params(params: dict[str, str]) -> dict[str, str]:
        """参数先做 URL 编码，**签的也是编码后的值** —— 不然中文关键字（`query=都市`）
        会带着裸汉字进 URL，urllib 直接抛 UnicodeEncodeError（实测踩过）。
        逗号保留：批量接口的 `item_ids=a,b` 编成 `%2C` 会被当成一个 id。"""
        return {str(k): urllib.parse.quote(str(v), safe=",") for k, v in params.items()}

    # ---------------- 请求 ----------------
    def get(self, path: str, params: dict[str, str] | None = None) -> ApiResult:
        params = self.encode_params({k: str(v) for k, v in (params or {}).items()})
        url = self.url_for(path, params)
        try:
            status, body = self._fetch(url, self.headers(path, params))
        except Exception as exc:  # noqa: BLE001
            self.last_error = f"{type(exc).__name__}: {exc}"
            return ApiResult(ok=False, message=self.last_error)
        return parse_api(status, body)


def parse_api(status: int, body: str) -> ApiResult:
    """业务码判定：HTTP 200 不算成功，`code == 0` 才算。"""
    res = ApiResult(raw=body)
    if status != 200:
        res.message = f"HTTP {status}"
        return res
    body = (body or "").strip()
    if not body:
        res.message = "空 body（多半是签名没通过）"
        return res
    try:
        obj = json.loads(body)
    except json.JSONDecodeError:
        res.message = "返回不是 JSON"
        return res
    if not isinstance(obj, dict):
        res.message = "返回结构异常"
        return res
    code = obj.get("code")
    res.code = code if isinstance(code, int) else None
    res.message = str(obj.get("message") or obj.get("msg") or "")
    data = obj.get("data")
    res.data = data if isinstance(data, dict) else {}
    # 只认「code == 0」或「没有 code 但有 data」；HTML 错误页在这里已经因 JSON 解析失败被拦下
    res.ok = (res.code == 0) or (res.code is None and bool(res.data))
    if not res.ok and code is None:
        res.message = res.message or "没有 code 也没有 data"
    return res


def build_signer(cmd: str = "", log=print):
    """按「命令行参数 → 环境变量 → 桩」的顺序选签名器。"""
    cmd = cmd or os.environ.get("FANQIE_SIGNER", "")
    if cmd:
        log(f"[sign] 使用外部签名器：{cmd}")
        return secsdk.ExternalCommandSigner(cmd)
    log("[sign] 没有签名器（设 FANQIE_SIGNER 或 --signer），接口只会返回非 0 业务码")
    return secsdk.StubSigner()


# ---------------- 接口封装（路径集中在常量里，换版本只改这里） ----------------

class FanqieApp:
    """App 侧接口清单。每个方法只做「拼参数 + 取字段」，签名/重试都在 [AppClient]。"""

    SEARCH = "/reading/bookapi/search/page/v/"
    CATEGORY = "/reading/bookapi/bookmall/cell/books/v/"
    DIRECTORY = "/reading/reader/directory/detail"
    FULL = "/reading/reader/full"
    BATCH_FULL = "/reading/reader/batch_full/v"
    BOOK_INFO = "/reading/bookapi/book/mmid/v/"

    def __init__(self, client: AppClient, log=print):
        self.client = client
        self.log = log

    # 书池
    def search(self, keyword: str, offset: int = 0, count: int = 20) -> list[dict]:
        res = self.client.get(self.SEARCH, {
            "query": keyword, "offset": offset, "count": count,
            "aid": self.client.aid, "device_id": self.client.device_id,
        })
        return _books_of(res)

    def category(self, gender: int = 0, category_id: str = "", offset: int = 0,
                 count: int = 20) -> list[dict]:
        res = self.client.get(self.CATEGORY, {
            "gender": gender, "category_id": category_id, "offset": offset,
            "count": count, "aid": self.client.aid,
        })
        return _books_of(res)

    # 目录
    def directory(self, book_id: str) -> list[dict]:
        res = self.client.get(self.DIRECTORY, {"book_id": book_id})
        payload = res.data or {}
        return payload.get("chapterListWithVolume") or []

    # 正文
    def full(self, item_id: str) -> tuple[str, str]:
        """单章正文 → (正文, 备注)。"""
        res = self.client.get(self.FULL, {"item_id": item_id})
        return _content_of(res)

    def batch_full(self, item_ids: list[str]) -> dict[str, str]:
        """批量正文：一次多章，风控比逐章松（这就是它存在的理由）。"""
        if not item_ids:
            return {}
        res = self.client.get(self.BATCH_FULL, {"item_ids": ",".join(item_ids)})
        out: dict[str, str] = {}
        rows = res.data.get("data") or res.data.get("chapterData") or []
        if isinstance(rows, list):
            for row in rows:
                if not isinstance(row, dict):
                    continue
                iid = str(row.get("itemId") or row.get("item_id") or "")
                text = row.get("content") or row.get("chapterContent") or ""
                if iid and text:
                    out[iid] = text
        return out


def _books_of(res: ApiResult) -> list[dict]:
    if not res.ok:
        return []
    for key in ("book_data", "books", "list", "book_list", "cell_book_list"):
        rows = res.data.get(key)
        if isinstance(rows, list):
            return [r for r in rows if isinstance(r, dict)]
    return []


def _content_of(res: ApiResult) -> tuple[str, str]:
    if not res.ok:
        return "", res.message
    data = res.data
    chapter = data.get("chapterData") or data
    text = chapter.get("content") or chapter.get("chapterContent") or ""
    locked = bool(data.get("chapterLock") or chapter.get("isChapterLock"))
    if not text:
        return "", "chapterLock" if locked else "空正文"
    return text, ""
