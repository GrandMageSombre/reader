"""HTTP 层：cookiejar、重试、限速、gzip、编码识别。"""
from __future__ import annotations

import gzip
import json
import random
import threading
import time
import zlib
from pathlib import Path
from urllib.parse import urlparse

import requests

from . import config

_lock = threading.Lock()
_last_call = [0.0]


def _throttle() -> None:
    with _lock:
        gap = config.DELAY
        wait = gap - (time.time() - _last_call[0])
        if wait > 0:
            time.sleep(wait + random.uniform(0, gap / 3))
        _last_call[0] = time.time()


class Session:
    """一个站点一个 Session，cookie 保存在 data/ 下，重启不用重新过反爬。"""

    def __init__(self, name: str, base_url: str, ua: str = config.UA_PC,
                 per_host_delay: float | None = None):
        self.name = name
        self.base = base_url.rstrip("/")
        self.per_host_delay = per_host_delay
        self.s = requests.Session()
        self.s.headers.update({
            "User-Agent": ua,
            "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.6",
            "Accept-Encoding": "gzip, deflate",
            "Connection": "keep-alive",
            "Upgrade-Insecure-Requests": "1",
        })
        self.jar_path = config.DATA_DIR / f"cookies_{name}.json"
        self._load()

    # ---------- cookie ----------
    def _load(self) -> None:
        if self.jar_path.exists():
            try:
                for c in json.loads(self.jar_path.read_text("utf-8")):
                    self.s.cookies.set(c["name"], c["value"], domain=c.get("domain", ""),
                                       path=c.get("path", "/"))
            except Exception:  # noqa: BLE001
                pass

    def save(self) -> None:
        data = [{"name": c.name, "value": c.value, "domain": c.domain, "path": c.path}
                for c in self.s.cookies]
        self.jar_path.write_text(json.dumps(data, ensure_ascii=False, indent=1), "utf-8")

    def cookie_header(self) -> str:
        return "; ".join(f"{c.name}={c.value}" for c in self.s.cookies)

    def set_cookie_header(self, header: str) -> None:
        for part in header.split(";"):
            if "=" not in part:
                continue
            k, v = part.strip().split("=", 1)
            self.s.cookies.set(k, v, domain=urlparse(self.base).hostname)

    def import_cookies(self, text: str) -> int:
        """导入 cookie：支持 `k=v; k=v` 头，也支持浏览器扩展导出的 JSON。

        番茄/起点的风控是按 IP + cookie 的，把你自己浏览器里过完验证码的
        cookie 导进来，比每次让爬虫去撞验证码省事。
        """
        text = (text or "").strip()
        n = 0
        host = urlparse(self.base).hostname
        if text.startswith("[") or text.startswith("{"):
            try:
                data = json.loads(text)
            except json.JSONDecodeError as exc:
                raise ValueError(f"cookie JSON 解析失败：{exc}") from exc
            items = data if isinstance(data, list) else data.get("cookies", [])
            for c in items:
                if not isinstance(c, dict) or "name" not in c:
                    continue
                self.s.cookies.set(c["name"], c.get("value", ""),
                                   domain=c.get("domain") or host,
                                   path=c.get("path") or "/")
                n += 1
        else:
            for part in text.split(";"):
                if "=" not in part:
                    continue
                k, v = part.strip().split("=", 1)
                if not k:
                    continue
                self.s.cookies.set(k, v, domain=host)
                n += 1
        self.save()
        return n

    # ---------- 抓取 ----------
    def get(self, url: str, *, referer: str | None = None, headers: dict | None = None,
            retries: int | None = None, timeout: float | None = None,
            allow_status: tuple[int, ...] = (200,)) -> tuple[int, str]:
        retries = config.RETRIES if retries is None else retries
        timeout = config.TIMEOUT if timeout is None else timeout
        h = {"Referer": referer or self.base + "/"}
        if headers:
            h.update(headers)
        last_exc: Exception | None = None
        for attempt in range(retries + 1):
            _throttle()
            try:
                r = self.s.get(url, headers=h, timeout=timeout)
                body = self._decode(r)
                if r.status_code in allow_status:
                    return r.status_code, body
                if r.status_code in (429, 500, 502, 503, 504):
                    time.sleep(1.5 * (attempt + 1))
                    continue
                return r.status_code, body
            except requests.RequestException as exc:
                last_exc = exc
                time.sleep(1.0 * (attempt + 1))
        if last_exc:
            raise last_exc
        return 0, ""

    def get_json(self, url: str, **kw) -> dict:
        status, body = self.get(url, **kw)
        if not body.strip():
            return {}
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {}

    @staticmethod
    def _decode(r: requests.Response) -> str:
        raw = r.content
        enc = r.headers.get("Content-Encoding", "")
        try:
            if enc == "gzip":
                raw = gzip.decompress(raw)
            elif enc == "deflate":
                raw = zlib.decompress(raw, -zlib.MAX_WBITS)
        except Exception:  # noqa: BLE001
            pass
        ctype = r.headers.get("Content-Type", "").lower()
        for cs in ("utf-8", "gb18030", "gbk"):
            if cs in ctype:
                return raw.decode(cs, "replace")
        head = raw[:2048].lower()
        if b"charset=gbk" in head or b"charset=gb2312" in head:
            return raw.decode("gbk", "replace")
        return raw.decode("utf-8", "replace")

    def get_bytes(self, url: str, *, referer: str | None = None, cache: Path | None = None) -> bytes:
        if cache and cache.exists() and cache.stat().st_size > 1024:
            return cache.read_bytes()
        _throttle()
        r = self.s.get(url, headers={"Referer": referer or self.base + "/"}, timeout=60)
        r.raise_for_status()
        if cache:
            cache.parent.mkdir(parents=True, exist_ok=True)
            cache.write_bytes(r.content)
        return r.content
