"""破解层：起点 / 番茄「全部资源」的一条龙搬运。

和 `tools/spider/{qidian,fanqie}.py` 的分工：

| 层 | 干什么 |
|----|--------|
| `qidian.py` / `fanqie.py` | **单点抓取**：一个列表页、一本书的目录、一章的正文 |
| `crack/`（本包） | **多入口破解 + 编排**：同一份资源同时试多条入口，谁给全文用谁；断了换路，缺了补路 |

「全部资源」的口径（`crack.plan.TARGETS`）：

- 起点：137 个筛选 × 50 页的书目 → 每本全量目录 → 每章正文（免费章直取；VIP 章试读时走
  移动端 / ajax / 采集站三条补全链）。
- 番茄：把书池从「榜单 26 本」扩到「榜单 × 维度 × 搜索 × 分类 × 同作者」能枚举到的全部，
  每本全量目录 → 每章正文（web SSR + 字体反混淆；App 签名接口兜付费章）。

用法见 `tools/spider/harvest.py`：

    python -m tools.spider.harvest --source both --what all --workers 3
"""
from __future__ import annotations

__all__ = ["plan", "qidian", "fanqie", "appclient", "runner"]
