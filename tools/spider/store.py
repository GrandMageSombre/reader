"""SQLite 存储：书目 / 目录 / 正文 / 抓取状态。

所有表都以 `key = 源:站点 id` 为主键，重复跑不会产生重复数据，
断点续抓靠 contents.ok 与 chapters 是否已存在判断。
"""
from __future__ import annotations

import sqlite3
import time
from contextlib import contextmanager
from typing import Iterable, Iterator

from . import config

SCHEMA = """
CREATE TABLE IF NOT EXISTS books (
  key           TEXT PRIMARY KEY,
  source        TEXT NOT NULL,
  native_id     TEXT NOT NULL,
  title         TEXT,
  author        TEXT,
  cover         TEXT,
  intro         TEXT,
  category      TEXT,
  status        TEXT,
  words         INTEGER DEFAULT 0,
  updated       TEXT,
  url           TEXT,
  chapter_count INTEGER DEFAULT 0,
  cataloged_at  INTEGER DEFAULT 0,
  fetched_at    INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_books_source ON books(source, category);

CREATE TABLE IF NOT EXISTS chapters (
  key       TEXT NOT NULL,
  idx       INTEGER NOT NULL,
  title     TEXT,
  url       TEXT,
  native_id TEXT,
  vip       INTEGER DEFAULT 0,
  word      INTEGER DEFAULT 0,
  PRIMARY KEY (key, idx)
);

CREATE TABLE IF NOT EXISTS contents (
  key    TEXT NOT NULL,
  idx    INTEGER NOT NULL,
  text   TEXT,
  chars  INTEGER DEFAULT 0,
  ok     INTEGER DEFAULT 1,
  note   TEXT,
  ts     INTEGER DEFAULT 0,
  PRIMARY KEY (key, idx)
);

CREATE TABLE IF NOT EXISTS filters (
  source TEXT NOT NULL,
  name   TEXT NOT NULL,
  url    TEXT NOT NULL,
  kind   TEXT DEFAULT 'category',
  PRIMARY KEY (source, name)
);

CREATE TABLE IF NOT EXISTS state (
  k TEXT PRIMARY KEY,
  v TEXT
);
"""


def now() -> int:
    return int(time.time())


@contextmanager
def connect(path=None) -> Iterator[sqlite3.Connection]:
    conn = sqlite3.connect(path or config.DB_PATH, timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    try:
        conn.executescript(SCHEMA)
        yield conn
        conn.commit()
    finally:
        conn.close()


# ---------------- books ----------------
def upsert_book(conn: sqlite3.Connection, **b) -> None:
    b.setdefault("fetched_at", now())
    cols = ("key", "source", "native_id", "title", "author", "cover", "intro",
            "category", "status", "words", "updated", "url", "chapter_count",
            "cataloged_at", "fetched_at")
    vals = [b.get(c) for c in cols]
    placeholders = ",".join("?" * len(cols))
    updates = ",".join(f"{c}=excluded.{c}" for c in cols if c != "key")
    conn.execute(
        f"INSERT INTO books ({','.join(cols)}) VALUES ({placeholders}) "
        f"ON CONFLICT(key) DO UPDATE SET {updates}", vals)


def book_exists(conn: sqlite3.Connection, key: str) -> bool:
    return conn.execute("SELECT 1 FROM books WHERE key=?", (key,)).fetchone() is not None


def books(conn: sqlite3.Connection, source: str | None = None,
          missing_catalog: bool = False, limit: int | None = None) -> list[sqlite3.Row]:
    sql = "SELECT * FROM books"
    where, args = [], []
    if source:
        where.append("source=?")
        args.append(source)
    if missing_catalog:
        where.append("COALESCE(cataloged_at,0)=0")
    if where:
        sql += " WHERE " + " AND ".join(where)
    sql += " ORDER BY title"
    if limit:
        sql += f" LIMIT {int(limit)}"
    return conn.execute(sql, args).fetchall()


def set_chapter_count(conn: sqlite3.Connection, key: str, n: int) -> None:
    conn.execute("UPDATE books SET chapter_count=?, cataloged_at=? WHERE key=?",
                 (n, now(), key))


# ---------------- chapters ----------------
def replace_chapters(conn: sqlite3.Connection, key: str,
                     rows: Iterable[tuple[int, str, str, str, int]]) -> int:
    rows = list(rows)
    conn.execute("DELETE FROM chapters WHERE key=?", (key,))
    conn.executemany(
        "INSERT INTO chapters (key, idx, title, url, native_id, vip) VALUES (?,?,?,?,?,?)",
        [(key, i, t, u, nid, vip) for i, t, u, nid, vip in rows])
    set_chapter_count(conn, key, len(rows))
    return len(rows)


def chapters(conn: sqlite3.Connection, key: str) -> list[sqlite3.Row]:
    return conn.execute("SELECT * FROM chapters WHERE key=? ORDER BY idx", (key,)).fetchall()


def pending_contents(conn: sqlite3.Connection, source: str, limit: int,
                     include_vip: bool = False) -> list[sqlite3.Row]:
    sql = """
    SELECT c.key, c.idx, c.title, c.url, c.native_id, c.vip, b.title AS book_title
    FROM chapters c JOIN books b ON b.key = c.key
    LEFT JOIN contents t ON t.key = c.key AND t.idx = c.idx AND t.ok = 1
    WHERE b.source = ? AND t.key IS NULL
    """
    if not include_vip:
        sql += " AND c.vip = 0"
    # 先抓免费章；再按「上次尝试时间」排序：抓失败/被风控的章会排到后面，
    # 不会年年占着队头让后面的书永远轮不到。
    sql += (" ORDER BY c.vip, COALESCE((SELECT ts FROM contents x WHERE x.key=c.key"
            " AND x.idx=c.idx), 0), c.key, c.idx LIMIT ?")
    return conn.execute(sql, (source, limit)).fetchall()


# ---------------- contents ----------------
def save_content(conn: sqlite3.Connection, key: str, idx: int, text: str,
                 ok: bool = True, note: str = "") -> None:
    conn.execute(
        "INSERT INTO contents (key, idx, text, chars, ok, note, ts) VALUES (?,?,?,?,?,?,?) "
        "ON CONFLICT(key, idx) DO UPDATE SET text=excluded.text, chars=excluded.chars, "
        "ok=excluded.ok, note=excluded.note, ts=excluded.ts",
        (key, idx, text, len(text), 1 if ok else 0, note, now()))


def get_content(conn: sqlite3.Connection, key: str, idx: int) -> sqlite3.Row | None:
    return conn.execute("SELECT * FROM contents WHERE key=? AND idx=?", (key, idx)).fetchone()


# ---------------- filters / state ----------------
def save_filters(conn: sqlite3.Connection, source: str,
                 items: Iterable[tuple[str, str]]) -> None:
    conn.executemany(
        "INSERT INTO filters (source, name, url, kind) VALUES (?,?,?,'category') "
        "ON CONFLICT(source, name) DO UPDATE SET url=excluded.url",
        [(source, n, u) for n, u in items])


def filters(conn: sqlite3.Connection, source: str) -> list[sqlite3.Row]:
    return conn.execute("SELECT * FROM filters WHERE source=? ORDER BY name", (source,)).fetchall()


def set_state(conn: sqlite3.Connection, k: str, v: str) -> None:
    conn.execute("INSERT INTO state (k,v) VALUES (?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v",
                 (k, v))


def get_state(conn: sqlite3.Connection, k: str, default: str = "") -> str:
    row = conn.execute("SELECT v FROM state WHERE k=?", (k,)).fetchone()
    return row["v"] if row else default


def stats(conn: sqlite3.Connection) -> list[sqlite3.Row]:
    return conn.execute("""
    SELECT b.source,
           COUNT(*) AS books,
           SUM(b.chapter_count) AS chapters,
           (SELECT COUNT(*) FROM contents t WHERE substr(t.key,1,instr(t.key,':')-1)=b.source) AS contents
    FROM books b GROUP BY b.source
    """).fetchall()
