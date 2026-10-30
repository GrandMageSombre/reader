"""全局配置：路径、UA、限速、超时。"""
from __future__ import annotations

import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = Path(os.environ.get("SPIDER_DATA", ROOT / "data"))
DB_PATH = Path(os.environ.get("SPIDER_DB", DATA_DIR / "library.db"))
ASSET_DIR = Path(__file__).resolve().parent / "assets"
CACHE_DIR = DATA_DIR / "cache"
EXPORT_DIR = DATA_DIR / "export"

for _d in (DATA_DIR, ASSET_DIR, CACHE_DIR, EXPORT_DIR):
    _d.mkdir(parents=True, exist_ok=True)

UA_PC = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
         "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
UA_MOBILE = ("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
             "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 "
             "Mobile/15E148 Safari/604.1")

QIDIAN_BASE = "https://www.qidian.com"
FANQIE_BASE = "https://fanqienovel.com"

# 请求间隔（秒）：默认 0.35s，约 3 请求/秒；站点压力测试时自己调大
DELAY = float(os.environ.get("SPIDER_DELAY", "0.35"))
TIMEOUT = float(os.environ.get("SPIDER_TIMEOUT", "25"))
RETRIES = int(os.environ.get("SPIDER_RETRIES", "3"))

# 起点的目录列表每页上限（站点 data-pagemax，实测 50）
QIDIAN_PAGE_MAX = 50

# 番茄榜单接口能用的 type 档位
FANQIE_RANK_TYPES = (0, 1, 2)

# 反混淆参考字体（番茄混淆字体 = SourceHanSansSC-Normal 2.002R 子集）
REF_FONT_NAME = "SourceHanSansSC-Normal-2.002R.otf"
REF_FONT_URLS = (
    "https://gcore.jsdelivr.net/gh/adobe-fonts/source-han-sans@2.002R/"
    "OTF/SimplifiedChinese/SourceHanSansSC-Normal.otf",
    "https://cdn.jsdelivr.net/gh/adobe-fonts/source-han-sans@2.002R/"
    "OTF/SimplifiedChinese/SourceHanSansSC-Normal.otf",
)
