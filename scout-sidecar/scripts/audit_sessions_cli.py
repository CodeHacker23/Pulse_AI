"""Локальный аудит .session: отпечаток auth_key + кто закеширован внутри.

Сети не касается — только читает файлы, поэтому ключи не сгорят.

    python scripts/audit_sessions_cli.py
"""
from __future__ import annotations

import hashlib
import sqlite3
import sys
from pathlib import Path

SESSIONS = Path(__file__).resolve().parent.parent / "sessions"


def read_session(path: Path) -> dict:
    out: dict = {"file": path.name}
    try:
        con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    except Exception as ex:
        out["error"] = str(ex)
        return out
    try:
        row = con.execute("select dc_id, server_address, auth_key from sessions").fetchone()
        if row:
            out["dc"] = row[0]
            out["server"] = row[1]
            if row[2]:
                out["keyHash"] = hashlib.sha1(bytes(row[2])).hexdigest()[:12]
        try:
            out["entities"] = con.execute("select count(*) from entities").fetchone()[0]
            out["phones"] = con.execute(
                "select id, name, username, phone from entities "
                "where phone is not null and phone != '' limit 5"
            ).fetchall()
        except Exception:
            out["entities"] = 0
            out["phones"] = []
    finally:
        con.close()
    return out


def main() -> int:
    if not SESSIONS.is_dir():
        print(f"нет папки {SESSIONS}")
        return 1
    rows = [read_session(p) for p in sorted(SESSIONS.glob("*.session"))]
    groups: dict[str, list[str]] = {}
    for r in rows:
        print(
            f"{r['file']:<28} dc={r.get('dc')} key={r.get('keyHash')} "
            f"entities={r.get('entities')} {r.get('error', '')}"
        )
        for ent in r.get("phones") or []:
            print(f"    +{ent[3]}  id={ent[0]}  {ent[1] or ''} @{ent[2] or ''}")
        if r.get("keyHash"):
            groups.setdefault(r["keyHash"], []).append(r["file"])

    dups = {k: v for k, v in groups.items() if len(v) > 1}
    if dups:
        print("\nДУБЛИ auth_key (Telegram убьёт такие ключи):")
        for k, files in dups.items():
            print(f"  {k}: {' + '.join(files)}")
    else:
        print("\nдублей ключей нет")
    return 0


if __name__ == "__main__":
    sys.exit(main())
