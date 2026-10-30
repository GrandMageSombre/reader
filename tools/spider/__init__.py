"""墨读 · 全站爬虫

把起点 / 番茄的书目、目录、正文落到本地 SQLite，再通过本地 HTTP 接口
喂给阅读器（书源「本地爬虫库」）。

    python -m tools.spider.cli --help
"""

__all__ = ["config", "net", "store", "qidian", "fanqie", "fontfix", "export", "serve"]
__version__ = "1.0.0"
