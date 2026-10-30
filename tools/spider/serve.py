"""本地 HTTP 接口：把爬下来的库喂给阅读器（书源「本地爬虫库」）。

    python -m tools.spider.cli serve --port 8765

接口（全部 GET，返回 JSON）：
    /api/health
    /api/stats
    /api/categories
    /api/books?cat=&page=1&size=20
    /api/search?key=&page=1&size=20
    /api/book/{id}
    /api/book/{id}/chapters
    /api/book/{id}/chapter/{idx}
"""
from __future__ import annotations

import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

from . import store

PAGE_SIZE = 20


def _book_row(row) -> dict:
    cover = (row["cover"] or "").strip()
    if cover.startswith("//"):
        cover = "https:" + cover
    return {
        "id": row["key"], "source": row["source"], "title": row["title"],
        "author": row["author"], "cover": cover, "intro": row["intro"],
        "category": row["category"], "status": row["status"], "words": row["words"],
        "url": row["url"], "chapters": row["chapter_count"],
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "MoDuSpider/1.0"

    def log_message(self, fmt, *args):  # 安静一点
        pass

    # ---------- 响应 ----------
    def _send(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    # ---------- 路由 ----------
    def do_GET(self):  # noqa: N802
        u = urlparse(self.path)
        # 书的 key 里带冒号（qidian:1234），客户端可能编码成 %3A，这里统一解回来
        path = unquote(u.path).rstrip("/") or "/"
        q = parse_qs(u.query)
        try:
            if path == "/api/health":
                return self._send({"ok": True, "name": "modu-spider"})
            if path == "/api/stats":
                with store.connect() as conn:
                    return self._send({"stats": [dict(r) for r in store.stats(conn)]})
            if path == "/api/categories":
                with store.connect() as conn:
                    rows = conn.execute(
                        "SELECT category, COUNT(*) n FROM books "
                        "WHERE category<>'' GROUP BY category ORDER BY n DESC").fetchall()
                    return self._send({"items": [{"name": r["category"], "count": r["n"]}
                                                 for r in rows]})
            if path == "/api/books":
                return self._list(q)
            if path == "/api/search":
                return self._search(q)
            m = re.fullmatch(r"/api/book/([^/]+)", path)
            if m:
                return self._detail(m.group(1))
            m = re.fullmatch(r"/api/book/([^/]+)/chapters", path)
            if m:
                return self._chapters(m.group(1))
            m = re.fullmatch(r"/api/book/([^/]+)/chapter/(\d+)", path)
            if m:
                return self._content(m.group(1), int(m.group(2)))
            return self._send({"error": "not found"}, 404)
        except Exception as exc:  # noqa: BLE001
            return self._send({"error": str(exc)}, 500)

    # ---------- 处理 ----------
    def _list(self, q):
        page = max(1, int(q.get("page", ["1"])[0]))
        size = min(100, int(q.get("size", [str(PAGE_SIZE)])[0]))
        cat = q.get("cat", [""])[0]
        sql = "SELECT * FROM books"
        args: list = []
        if cat:
            sql += " WHERE category LIKE ?"
            args.append(f"%{cat}%")
        sql += " ORDER BY title LIMIT ? OFFSET ?"
        args += [size, (page - 1) * size]
        with store.connect() as conn:
            rows = conn.execute(sql, args).fetchall()
            total = conn.execute(
                "SELECT COUNT(*) c FROM books" + (" WHERE category LIKE ?" if cat else ""),
                ([f"%{cat}%"] if cat else [])).fetchone()["c"]
        return self._send({"page": page, "total": total,
                           "items": [_book_row(r) for r in rows]})

    def _search(self, q):
        key = (q.get("key", [""])[0] or "").strip()
        page = max(1, int(q.get("page", ["1"])[0]))
        size = min(100, int(q.get("size", [str(PAGE_SIZE)])[0]))
        if not key:
            return self._send({"page": page, "total": 0, "items": []})
        like = f"%{key}%"
        with store.connect() as conn:
            rows = conn.execute(
                "SELECT * FROM books WHERE title LIKE ? OR author LIKE ? "
                "ORDER BY (title=?) DESC, title LIMIT ? OFFSET ?",
                (like, like, key, size, (page - 1) * size)).fetchall()
            total = conn.execute("SELECT COUNT(*) c FROM books WHERE title LIKE ? OR author LIKE ?",
                                 (like, like)).fetchone()["c"]
        return self._send({"page": page, "total": total,
                           "items": [_book_row(r) for r in rows]})

    def _detail(self, key):
        with store.connect() as conn:
            row = conn.execute("SELECT * FROM books WHERE key=?", (key,)).fetchone()
            if not row:
                return self._send({"error": "book not found"}, 404)
            return self._send(_book_row(row))

    def _chapters(self, key):
        with store.connect() as conn:
            rows = conn.execute("SELECT * FROM chapters WHERE key=? ORDER BY idx",
                                (key,)).fetchall()
            got = {r["idx"] for r in conn.execute(
                "SELECT idx FROM contents WHERE key=? AND ok=1", (key,))}
        return self._send({"items": [
            {"idx": r["idx"], "title": r["title"], "vip": bool(r["vip"]),
             "cached": r["idx"] in got} for r in rows]})

    def _content(self, key, idx):
        with store.connect() as conn:
            row = conn.execute("SELECT * FROM contents WHERE key=? AND idx=?",
                               (key, idx)).fetchone()
            ch = conn.execute("SELECT * FROM chapters WHERE key=? AND idx=?",
                              (key, idx)).fetchone()
        if not row:
            return self._send({"error": "not cached", "idx": idx}, 404)
        return self._send({
            "idx": idx,
            "title": ch["title"] if ch else "",
            "vip": bool(ch["vip"]) if ch else False,
            "preview": (row["note"] or "") == "vip_preview",
            "text": row["text"] or "",
        })


def serve(port: int = 8765, host: str = "0.0.0.0") -> None:
    httpd = ThreadingHTTPServer((host, port), Handler)
    print(f"本地爬虫库已启动：http://127.0.0.1:{port}/api/health")
    print("在阅读器里添加书源「本地爬虫库」，地址填上面的 host:port")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("已停止")
    finally:
        httpd.server_close()
