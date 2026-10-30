"""发版：建 tag + GitHub Release，并上传 releases/ 下的 APK 资产。

版本号从 `app/build.gradle.kts` 的 `versionName` 读，资产名按
`releases/modu-v<版本>-android.apk` 与 `-debug.apk` 约定查找。

    python -m tools.make_release --dry-run      # 只打印将要做什么
    python -m tools.make_release                # 真发
    python -m tools.make_release --notes-file notes.md

token 从 GCM（git credential fill）取，不落盘、不进日志。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app/build.gradle.kts"
RELEASES = ROOT / "releases"

OWNER, REPO = "GrandMageSombre", "reader"


def version() -> str:
    text = GRADLE.read_text(encoding="utf-8")
    m = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not m:
        raise SystemExit("读不到 versionName")
    return m.group(1)


def token() -> str:
    env = {**os.environ, "GCM_INTERACTIVE": "never",
           "GIT_TERMINAL_PROMPT": "0", "GCM_PROVIDER": "github"}
    r = subprocess.run(["git", "credential", "fill"],
                       input="protocol=https\nhost=github.com\n\n",
                       capture_output=True, text=True, timeout=60, env=env)
    for line in r.stdout.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("取不到 GitHub token（GCM 里没有 github.com 凭据）")


def api(path: str, method: str = "GET", body=None, tok: str = ""):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        "https://api.github.com" + path, data=data, method=method,
        headers={"Authorization": "Bearer " + tok,
                 "Accept": "application/vnd.github+json",
                 "User-Agent": "modu-release", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        raw = resp.read().decode()
        return json.loads(raw) if raw.strip() else {}


def upload(upload_url: str, path: Path, ctype: str, tok: str):
    url = upload_url.split("{", 1)[0] + "?name=" + urllib.parse.quote(path.name)
    data = path.read_bytes()
    req = urllib.request.Request(
        url, data=data, method="POST",
        headers={"Authorization": "Bearer " + tok,
                 "Accept": "application/vnd.github+json",
                 "User-Agent": "modu-release", "Content-Type": ctype,
                 "Content-Length": str(len(data))})
    with urllib.request.urlopen(req, timeout=900) as resp:
        return json.loads(resp.read().decode())


def default_notes(ver: str) -> str:
    """从 版本说明.md 里抠出对应小节的正文当 Release 说明"""
    doc = (ROOT / "版本说明.md")
    if not doc.exists():
        return f"v{ver}"
    text = doc.read_text(encoding="utf-8")
    m = re.search(rf"^## v{re.escape(ver)}（[^）]*）\s*$(.*?)(?=^## v|\Z)",
                  text, re.S | re.M)
    body = (m.group(1).strip() if m else "")
    head = f"## 安装\n\n- `modu-v{ver}-android.apk`：发布包（沿用 debug 签名，可直接装）\n" \
           f"- `modu-v{ver}-debug.apk`：带调试信息\n\n"
    return (body + "\n\n" if body else "") + head


def main() -> None:
    ap = argparse.ArgumentParser(prog="python -m tools.make_release")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--notes-file", default="")
    ap.add_argument("--tag", default="")
    args = ap.parse_args()

    ver = version()
    tag = args.tag or f"v{ver}"
    notes = Path(args.notes_file).read_text(encoding="utf-8") if args.notes_file \
        else default_notes(ver)

    assets = [
        (RELEASES / f"modu-{tag}-android.apk", "application/vnd.android.package-archive"),
        (RELEASES / f"modu-{tag}-debug.apk", "application/vnd.android.package-archive"),
    ]
    print(f"版本 {ver} → tag {tag}")
    for p, _ in assets:
        print(f"  {'OK ' if p.exists() else '缺 '} {p.relative_to(ROOT)} "
              f"{p.stat().st_size // 1024 if p.exists() else 0} KB")
    if not any(p.exists() for p, _ in assets):
        raise SystemExit("releases/ 里没有对应版本的 APK，先跑 assembleRelease/assembleDebug")

    if args.dry_run:
        print("[dry-run] 到此为止")
        return

    tok = token()
    head = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True,
                          text=True).stdout.strip()
    rels = api(f"/repos/{OWNER}/{REPO}/releases?per_page=30", tok=tok)
    rel = next((r for r in rels if r["tag_name"] == tag), None)
    if rel is None:
        rel = api(f"/repos/{OWNER}/{REPO}/releases", "POST", {
            "tag_name": tag,
            "target_commitish": "main",
            "name": f"{tag} · MoDuReader",
            "body": notes,
            "draft": False,
            "prerelease": False,
        }, tok=tok)
        print("已创建 Release:", rel["html_url"])
    else:
        api(f"/repos/{OWNER}/{REPO}/releases/{rel['id']}", "PATCH",
            {"body": notes}, tok=tok)
        print("Release 已存在，更新说明:", rel["html_url"])

    # tag 跟到本地 HEAD（发版提交可能晚于建 tag）
    try:
        ref = api(f"/repos/{OWNER}/{REPO}/git/ref/tags/{tag}", tok=tok)
        if ref["object"]["sha"] != head:
            api(f"/repos/{OWNER}/{REPO}/git/refs/tags/{tag}", "PATCH",
                {"sha": head, "force": True}, tok=tok)
            print("tag 已指向 HEAD:", head[:9])
    except Exception as exc:  # noqa: BLE001
        print("tag 校准跳过:", exc)

    existing = {a["name"]: a for a in rel.get("assets", [])}
    for path, ctype in assets:
        if not path.exists():
            continue
        if path.name in existing:
            api(f"/repos/{OWNER}/{REPO}/releases/assets/{existing[path.name]['id']}",
                "DELETE", tok=tok)
            print("  覆盖旧资产:", path.name)
        a = upload(rel["upload_url"], path, ctype, tok)
        print(f"  上传 {a['name']} {a['size'] // 1024} KB")

    rel = api(f"/repos/{OWNER}/{REPO}/releases/tags/{tag}", tok=tok)
    print("\n最终资产:")
    for a in rel["assets"]:
        print(f"  {a['name']:34s} {a['size'] // 1024:6d} KB  {a['browser_download_url']}")
    print("页面:", rel["html_url"])


if __name__ == "__main__":
    main()
