"""真机签名器：把 `libsign.so`（字节 secsdk）包成「stdin JSON → stdout JSON」的命令。

`tools/spider/sign/secsdk.py` 里的 [secsdk.ExternalCommandSigner] 只认这个契约：

```
stdin : {"device_id":..., "aid":1967, "url":..., "params":{...}, "body":"", "ts_ms":...}
stdout: {"X-Argus":"\\u0001...", "X-Gorgon":"8400...", "X-Khronos":"1793427183"}
```

本模块负责**把 so 调起来**。三条路，按可靠性排序：

1. `--so` 直接给 `.so` 路径 → `ctypes.CDLL` 调导出函数（Termux / 桌面 Linux / 真机可跑）；
2. `--func` 指定导出名（导出名随版本变，`--list` 能列出 so 里的候选）；
3. `--cmd` 包一层外部程序（例如用 `adb shell` 在设备上跑）。

**它做的事只有「调库」，不做算法**：签名值由 so 内部算，本模块不猜、不模拟。
算不出来就**明确报错**（stderr + 非 0 退出码），让上游 `ExternalCommandSigner` 抛异常、
`verify.py` 显示未通过 —— 绝不返回假头冒充成功。

导出表怎么读：优先 `pyelftools`，没有就用手写的 ELF64 `.dynsym` 解析，
再不行退回 `nm -D` / `readelf -sW`。三者都拿不到时 `--func` 必须显式给。
"""
from __future__ import annotations

import ctypes
import json
import os
import struct
import subprocess
import sys
from dataclasses import dataclass, field

# 常见导出名形态（版本差异极大，按优先级贪心匹配）
SYMBOL_HINTS = (
    "Java_",            # JNI 包装：Java_com_bytedance_secsdk_...
    "secsdk",           # secsdk_* / _ZN...secsdk...
    "doCommand",        # 部分版本的总入口
    "sign",             # sign / signString / getSign
    "argus", "gorgon",  # 分头导出
)

# 明显不是签名入口的符号，别误选
SYMBOL_BLOCKLIST = ("JNI_OnLoad", "JNI_OnUnload", "pthread_", "__cxa_", "_init", "_fini")


# ---------------------------------------------------------------- ELF 导出表

def elf_exports(data: bytes) -> list[str]:
    """从 ELF64 的 `.dynsym` 里取导出名；解析失败返回空列表（不抛异常）。

    只做「够用」的实现：读节头表 → 找 `SHT_DYNSYM` → 遍历符号表 → 取字符串表里的名字。
    不依赖 pyelftools，因为目标环境经常是 Termux / 精简手机系统。
    """
    try:
        if len(data) < 64 or data[:4] != b"\x7fELF":
            return []
        ei_class = data[4]
        if ei_class != 2:            # 只处理 ELF64
            return []
        e_shoff = struct.unpack_from("<Q", data, 0x28)[0]
        e_shentsize = struct.unpack_from("<H", data, 0x3A)[0]
        e_shnum = struct.unpack_from("<H", data, 0x3C)[0]
        e_shstrndx = struct.unpack_from("<H", data, 0x3E)[0]
        if not e_shoff or not e_shnum or e_shentsize < 64:
            return []

        def section(i: int):
            off = e_shoff + i * e_shentsize
            sh_name, sh_type = struct.unpack_from("<II", data, off)
            sh_offset = struct.unpack_from("<Q", data, off + 0x18)[0]
            sh_size = struct.unpack_from("<Q", data, off + 0x20)[0]
            sh_link, sh_entsize = struct.unpack_from("<II", data, off + 0x28)
            return sh_name, sh_type, sh_offset, sh_size, sh_link, sh_entsize

        strtab = b""
        if 0 <= e_shstrndx < e_shnum:
            _, _, s_off, s_size, _, _ = section(e_shstrndx)
            strtab = data[s_off:s_off + s_size]

        out: list[str] = []
        for i in range(e_shnum):
            sh_name, sh_type, sh_offset, sh_size, sh_link, sh_entsize = section(i)
            if sh_type != 11:                     # SHT_DYNSYM
                continue
            if not (0 <= sh_link < e_shnum) or not sh_entsize:
                continue
            _, _, st_off, st_size, _, _ = section(sh_link)
            names = data[st_off:st_off + st_size]
            count = sh_size // sh_entsize
            for j in range(count):
                entry = sh_offset + j * sh_entsize
                if entry + 4 > len(data):
                    break
                st_name = struct.unpack_from("<I", data, entry)[0]
                st_info = data[entry + 4] if entry + 4 < len(data) else 0
                if not st_name or st_name >= len(names):
                    continue
                end = names.find(b"\x00", st_name)
                if end < 0:
                    continue
                name = names[st_name:end].decode("utf-8", "replace")
                # 过滤：类型必须是有定义的函数/对象（STT_NOTYPE 常来自 soname 等杂项），
                # 名字必须可打印、不是版本串/节名
                if (st_info & 0x0F) not in (1, 2):
                    continue
                if len(name) < 3 or name.startswith(".") or not name.isprintable():
                    continue
                out.append(name)
        return sorted(set(out))
    except Exception:  # noqa: BLE001  解析失败就是「读不出来」，交给其它路
        return []


def list_exports(so_path: str) -> list[str]:
    """读 so 的导出名：内置 ELF 解析 → `nm -D` → `readelf -sW`。"""
    try:
        with open(so_path, "rb") as fh:
            names = elf_exports(fh.read())
        if names:
            return names
    except OSError:
        return []
    for cmd in (["nm", "-D", "--defined-only", so_path],
                ["readelf", "-sW", "--dyn-syms", so_path]):
        try:
            out = subprocess.run(cmd, capture_output=True, text=True, timeout=30).stdout
        except (OSError, subprocess.SubprocessError):
            continue
        found = []
        for line in out.splitlines():
            parts = line.split()
            if not parts:
                continue
            name = parts[-1].split("@")[0]
            if name and name not in ("Name", "Num:", ""):
                found.append(name)
        if found:
            return sorted(set(found))
    return []


def pick_symbol(exports: list[str]) -> str:
    """从导出表里挑最像「签名入口」的那个；挑不出返回空串。

    黑名单按**前缀**匹配（`_init` / `pthread_` 这类），不能用包含匹配：
    `secsdk_init` 里含 `_init`，包含匹配会把真正的入口自己筛掉（踩过）。
    """
    cands = [e for e in exports if not any(e.startswith(b) for b in SYMBOL_BLOCKLIST)]
    for hint in SYMBOL_HINTS:
        hit = [c for c in cands if hint in c]
        if hit:
            # 同优先级里优先短名（`sign` 比 `sign_verify_v2_impl` 更像总入口）
            return sorted(hit, key=lambda s: (len(s), s))[0]
    return ""


# ---------------------------------------------------------------- 调用 so

@dataclass
class SignResult:
    headers: dict[str, str] = field(default_factory=dict)
    error: str = ""

    @property
    def ok(self) -> bool:
        return bool(self.headers) and not self.error


REQUIRED_HEADERS = ("X-Argus", "X-Gorgon", "X-Khronos")


class NativeSigner:
    """`ctypes` 调 so。导出函数签名随版本变，所以按几种常见形态依次试。"""

    def __init__(self, so_path: str, func: str = "", log=print):
        self.so_path = so_path
        self.log = log
        self.func_name = func
        self._lib = None

    # ---------- 加载 ----------
    def load(self) -> None:
        if self._lib is not None:
            return
        if not os.path.exists(self.so_path):
            raise FileNotFoundError(f"找不到 so：{self.so_path}")
        self._lib = ctypes.CDLL(self.so_path)
        if not self.func_name:
            exports = list_exports(self.so_path)
            self.func_name = pick_symbol(exports)
            if not self.func_name:
                raise RuntimeError(
                    "导出表里挑不出签名入口，请用 --list 看候选再用 --func 指定"
                    f"（读到 {len(exports)} 个导出）")
            self.log(f"[signer] 选用导出函数：{self.func_name}")

    # ---------- 调用 ----------
    def sign(self, req: dict) -> SignResult:
        """调用 so。三条尝试：`char* -> char*`（最常见）、`void(char*)` 写回、`(char*, size) -> size`。"""
        try:
            self.load()
        except Exception as exc:  # noqa: BLE001
            return SignResult(error=f"加载 so 失败：{exc}")
        if self._lib is None:
            return SignResult(error="so 未加载")
        fn = getattr(self._lib, self.func_name)
        payload = json.dumps(req, ensure_ascii=False).encode()

        # 形态 1：char* sign(const char*) → 返回 JSON 字符串
        try:
            fn.argtypes = [ctypes.c_char_p]
            fn.restype = ctypes.c_char_p
            raw = fn(payload)
            if raw:
                return self._parse(raw)
        except Exception:  # noqa: BLE001
            pass

        # 形态 2：int sign(const char*, char*, int) → 写入缓冲
        try:
            buf = ctypes.create_string_buffer(8192)
            fn.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int]
            fn.restype = ctypes.c_int
            n = fn(payload, buf, 8192)
            if n and n > 0:
                return self._parse(buf.raw[:n])
        except Exception:  # noqa: BLE001
            pass

        return SignResult(error=(
            f"调用 {self.func_name} 没有得到可用返回。说明：导出函数的入参形态与模板不符，"
            "请看 `--list` 的导出名并用 `--func` 指定，或在设备上用 JNI 包装后走 --cmd。"))

    @staticmethod
    def _parse(raw: bytes) -> SignResult:
        text = raw.decode("utf-8", "replace").strip()
        if not text:
            return SignResult(error="so 返回空串")
        try:
            data = json.loads(text)
        except json.JSONDecodeError:
            return SignResult(error=f"so 返回的不是 JSON：{text[:120]}")
        if not isinstance(data, dict):
            return SignResult(error="so 返回结构不是对象")
        headers = {str(k): str(v) for k, v in data.items() if v}
        missing = [h for h in REQUIRED_HEADERS if h not in headers]
        if missing:
            return SignResult(error=f"缺少头：{', '.join(missing)}")
        return SignResult(headers=headers)


# ---------------------------------------------------------------- CLI

def _self_test() -> int:
    """不联网自检：导出名挑选 + JSON 契约 + 错误路径。"""
    ok = True

    def check(name: str, cond: bool, detail: str = "") -> None:
        nonlocal ok
        ok = ok and cond
        print(f"{'PASS' if cond else 'FAIL'}  {name}" + (f"  ({detail})" if detail else ""))

    exports = [
        "JNI_OnLoad", "Java_com_bytedance_secsdk_SignUtil_sign",
        "secsdk_init", "doCommand", "sign", "sign_verify_v2_impl", "pthread_mutex_lock",
    ]
    check("优先选 JNI 包装", pick_symbol(exports).startswith("Java_"), pick_symbol(exports))
    check("没有 JNI 时选 secsdk", pick_symbol(["secsdk_init", "foo"]).startswith("secsdk"))
    check("再退到 doCommand", pick_symbol(["doCommand", "sign_x"]) == "doCommand")
    check("挑不出就返回空", pick_symbol(["foo", "bar"]) == "")
    check("黑名单不选", pick_symbol(["JNI_OnLoad", "JNI_OnUnload"]) == "")
    check("坏数据不抛异常", elf_exports(b"not elf") == [])
    check("非 ELF64 返回空", elf_exports(b"\x7fELF\x01" + b"\x00" * 100) == [])

    r = NativeSigner._parse(b'{"X-Argus":"a","X-Gorgon":"b","X-Khronos":"1"}')
    check("全套头判成功", r.ok, str(r.headers))
    r2 = NativeSigner._parse(b'{"X-Argus":"a"}')
    check("缺头判失败", not r2.ok and "缺少头" in r2.error, r2.error)
    r3 = NativeSigner._parse(b"<html>no</html>")
    check("非 JSON 判失败", not r3.ok and "不是 JSON" in r3.error, r3.error)

    # 契约往返：走真实子进程，模拟 ExternalCommandSigner 的调用方式
    import tempfile
    from pathlib import Path

    fake = Path(tempfile.gettempdir()) / "modu_signer_selfcheck.py"
    fake.write_text(
        "import json,sys\n"
        "req=json.load(sys.stdin)\n"
        "print(json.dumps({'X-Argus':'stub','X-Gorgon':'8400','X-Khronos':str(req['ts_ms'])}))\n",
        encoding="utf-8")
    from . import secsdk

    try:
        out = secsdk.ExternalCommandSigner(f"{sys.executable} {fake}").sign(
            secsdk.SignRequest(device_id="d", ts_ms=1793427183156))
        check("契约往返可用", out.get("X-Argus") == "stub", str(out))
    except Exception as exc:  # noqa: BLE001
        check("契约往返可用", False, str(exc))

    print("\n结论:", "全部通过" if ok else "有失败项")
    return 0 if ok else 1


def build_parser():
    import argparse

    p = argparse.ArgumentParser(
        prog="python -m tools.spider.sign.signer",
        description="把 libsign.so 包成「stdin JSON → stdout JSON」的签名器")
    p.add_argument("--so", default=os.environ.get("FANQIE_LIBSIGN", ""),
                   help="libsign.so 路径（也可用环境变量 FANQIE_LIBSIGN）")
    p.add_argument("--func", default="", help="导出函数名（默认自动挑，见 --list）")
    p.add_argument("--cmd", default="",
                   help="改走外部命令（如 adb shell 里的设备侧签名器），同样 stdin/stdout JSON")
    p.add_argument("--list", action="store_true", help="只列出 so 里的候选导出名")
    p.add_argument("--self-test", action="store_true", help="不联网自检")
    return p


def _run_cmd(cmd: str, payload: str) -> int:
    """设备侧签名器：把同一个 JSON 转给外部命令，原样透出它的 stdout / stderr。"""
    proc = subprocess.run(cmd, shell=True, input=payload, capture_output=True, text=True)
    if proc.returncode != 0 or not proc.stdout.strip():
        print(f"--cmd 失败({proc.returncode})：{proc.stderr.strip()[:300]}", file=sys.stderr)
        return 1
    print(proc.stdout.strip())
    return 0


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.self_test:
        return _self_test()
    if args.cmd:
        return _run_cmd(args.cmd, sys.stdin.read())
    if not args.so:
        print("需要 --so /path/to/libsign.so（或设 FANQIE_LIBSIGN），也可以用 --cmd", file=sys.stderr)
        return 2
    if args.list:
        exports = list_exports(args.so)
        print(f"导出 {len(exports)} 个；候选：")
        for e in exports:
            hint = " ← 选中" if e == pick_symbol(exports) else ""
            print(f"  {e}{hint}")
        return 0

    req = json.load(sys.stdin)
    result = NativeSigner(args.so, func=args.func).sign(req)
    if not result.ok:
        # 契约要求：失败必须报错（非 0 退出码 + stderr），不能返回空对象冒充成功
        print(result.error, file=sys.stderr)
        return 1
    print(json.dumps(result.headers, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
