"""为 v1.9.0 建 tag + GitHub Release 并上传两个 APK 资产。

github.com:443 被墙时，push 走不了，但 api.github.com 通常还通；
Release 的 tag 由 API 自动创建（target_commitish=main）。
"""
import json
import mimetypes
import os
import subprocess
import urllib.request
from pathlib import Path

OWNER, REPO = "GrandMageSombre", "reader"
TAG = "v1.9.0"

NOTES = """## 手机直抓：不用连 PC 了

书城新增内置书源「**手机直抓**」：App 内的隐藏 WebView 过一次反爬，之后直接在手机上抓
起点书库（15 个分类、分页）与番茄榜单的目录与正文。不需要跑 `serve.py`、不需要填 IP。

- 起点：书库 / 全量目录 / 正文（VIP 章只有站方试读，自动落到换源兜底）
- 番茄：榜单 26 本 / 全量目录 / 正文（私有区码位用内置 362 条字形表还原）
- 书城入口调整为：起点 / 番茄 / 手机直抓；「本地爬虫库」移出入口（要连 PC 的人自己开）

## 本轮同时包含

- 全站爬虫 `tools/spider`（PC）：起点 137 个筛选取全、番茄字体反混淆、本地接口
- 付费墙边界实测与自动换源兜底（抽样命中 11/12，VIP 章正文 1172–3379 字）
- 第 5 轮四视角审查的 21 条修复 + P2/P3 清理

## 安装

- `modu-v1.9.0-android.apk`（9.2MB）：发布包，沿用 debug 签名，可直接装
- `modu-v1.9.0-debug.apk`（13MB）：带调试信息，排查问题用

包名 `com.modu.reader`，versionCode 12 / versionName 1.9.0，minSdk 26。
"""

ASSETS = [
    ("releases/modu-v1.9.0-android.apk", "application/vnd.android.package-archive"),
    ("releases/modu-v1.9.0-debug.apk", "application/vnd.android.package-archive"),
]


def token() -> str:
    env = {**os.environ, "GCM_INTERACTIVE": "never",
           "GIT_TERMINAL_PROMPT": "0", "GCM_PROVIDER": "github"}
    r = subprocess.run(["git", "credential", "fill"],
                       input="protocol=https\nhost=github.com\n\n",
                       capture_output=True, text=True, timeout=60, env=env)
    for line in r.stdout.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("取不到 token")


TOKEN = token()


def api(path: str, method: str = "GET", body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        "https://api.github.com" + path, data=data, method=method,
        headers={"Authorization": "Bearer " + TOKEN,
                 "Accept": "application/vnd.github+json",
                 "User-Agent": "wb", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        raw = resp.read().decode()
        return json.loads(raw) if raw.strip() else {}


def upload(upload_url: str, path: Path, ctype: str):
    url = upload_url.split("{", 1)[0] + "?name=" + urllib.parse.quote(path.name)
    data = path.read_bytes()
    req = urllib.request.Request(
        url, data=data, method="POST",
        headers={"Authorization": "Bearer " + TOKEN,
                 "Accept": "application/vnd.github+json",
                 "User-Agent": "wb", "Content-Type": ctype,
                 "Content-Length": str(len(data))})
    with urllib.request.urlopen(req, timeout=600) as resp:
        return json.loads(resp.read().decode())


def main():
    releases = api(f"/repos/{OWNER}/{REPO}/releases?per_page=20")
    rel = next((r for r in releases if r["tag_name"] == TAG), None)
    if rel is None:
        rel = api(f"/repos/{OWNER}/{REPO}/releases", "POST", {
            "tag_name": TAG,
            "target_commitish": "main",
            "name": f"v1.9.0 · 手机直抓 / MoDuReader",
            "body": NOTES,
            "draft": False,
            "prerelease": False,
        })
        print("已创建 Release:", rel["html_url"])
    else:
        print("Release 已存在:", rel["html_url"])

    existing = {a["name"]: a for a in rel.get("assets", [])}
    import urllib.parse  # noqa: F401  (upload 里用到)
    for rel_path, ctype in ASSETS:
        p = Path(rel_path)
        if not p.exists():
            print("缺文件:", p)
            continue
        if p.name in existing:
            api(f"/repos/{OWNER}/{REPO}/releases/assets/{existing[p.name]['id']}",
                "DELETE")
            print("  覆盖旧资产:", p.name)
        a = upload(rel["upload_url"], p, ctype)
        print(f"  上传 {a['name']} {a['size'] // 1024} KB")

    rel = api(f"/repos/{OWNER}/{REPO}/releases/tags/{TAG}")
    print("\n最终资产:")
    for a in rel["assets"]:
        print(f"  {a['name']:32s} {a['size'] // 1024:6d} KB  {a['browser_download_url']}")
    print("页面:", rel["html_url"])


if __name__ == "__main__":
    import urllib.parse  # noqa: F401
    main()
