"""Proxy pool for scout accounts. Invalid proxies are marked and skipped."""

from __future__ import annotations

import json
import socket
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import config

PROXIES_FILE = config.ROOT / "proxies.json"
ASSIGN_FILE = config.ROOT / "proxy_assignments.json"


@dataclass
class ProxyEntry:
    id: int
    host: str
    port: int
    proxy_type: str = "socks5"
    user: str = ""
    password: str = ""
    valid: bool = True
    last_check_ok: Optional[bool] = None
    last_check_ms: Optional[int] = None
    last_error: str = ""

    def telethon_tuple(self):
        # Telethon: (type, host, port) or (type, host, port, True, user, password)
        if self.user:
            return (self.proxy_type, self.host, self.port, True, self.user, self.password)
        return (self.proxy_type, self.host, self.port)

    def as_dict(self) -> dict:
        return {
            "id": self.id,
            "host": self.host,
            "port": self.port,
            "type": self.proxy_type,
            "user": self.user,
            "password": self.password,
            "valid": self.valid,
            "last_check_ok": self.last_check_ok,
            "last_check_ms": self.last_check_ms,
            "last_error": self.last_error or "",
        }


def _load_raw() -> list[dict]:
    if not PROXIES_FILE.exists():
        return []
    data = json.loads(PROXIES_FILE.read_text(encoding="utf-8"))
    return data.get("proxies", [])


def save_proxies(entries: list[ProxyEntry]) -> None:
    PROXIES_FILE.write_text(
        json.dumps({"proxies": [e.as_dict() for e in entries]}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def _from_raw(item: dict) -> ProxyEntry:
    return ProxyEntry(
        id=int(item["id"]),
        host=item["host"],
        port=int(item["port"]),
        proxy_type=item.get("type", "socks5"),
        user=item.get("user", "") or "",
        password=item.get("password", "") or "",
        valid=bool(item.get("valid", True)),
        last_check_ok=item.get("last_check_ok"),
        last_check_ms=item.get("last_check_ms"),
        last_error=item.get("last_error", "") or "",
    )


def load_proxies(include_invalid: bool = False) -> list[ProxyEntry]:
    result = []
    for item in _load_raw():
        e = _from_raw(item)
        if include_invalid or e.valid:
            result.append(e)
    return result


def load_assignments() -> dict[int, int]:
    if not ASSIGN_FILE.exists():
        return {}
    data = json.loads(ASSIGN_FILE.read_text(encoding="utf-8"))
    return {int(k): int(v) for k, v in data.get("assignments", {}).items()}


def save_assignments(assign: dict[int, int]) -> None:
    ASSIGN_FILE.write_text(
        json.dumps({"assignments": {str(k): v for k, v in assign.items()}}, indent=2),
        encoding="utf-8",
    )


def parse_proxy_line(line: str, next_id: int) -> Optional[ProxyEntry]:
    """Formats:
    host:port
    host:port:user:pass
    user:pass@host:port
    socks5://user:pass@host:port
    socks5://host:port
    """
    raw = line.strip()
    if not raw or raw.startswith("#"):
        return None
    proxy_type = "socks5"
    user = ""
    password = ""
    host = ""
    port = 0

    try:
        if "://" in raw:
            scheme, rest = raw.split("://", 1)
            proxy_type = scheme.lower().replace("socks5h", "socks5").replace("http", "http")
            if proxy_type not in ("socks5", "socks4", "http"):
                proxy_type = "socks5"
            if "@" in rest:
                creds, hostport = rest.rsplit("@", 1)
                if ":" in creds:
                    user, password = creds.split(":", 1)
                host, port_s = hostport.rsplit(":", 1)
                port = int(port_s)
            else:
                host, port_s = rest.rsplit(":", 1)
                port = int(port_s)
        elif "@" in raw:
            # user:pass@host:port
            creds, hostport = raw.rsplit("@", 1)
            if ":" in creds:
                user, password = creds.split(":", 1)
            host, port_s = hostport.rsplit(":", 1)
            port = int(port_s)
        else:
            parts = raw.split(":")
            if len(parts) == 2:
                host, port = parts[0], int(parts[1])
            elif len(parts) >= 4:
                # host:port:user:pass  (pass may contain ':')
                host, port, user, password = parts[0], int(parts[1]), parts[2], ":".join(parts[3:])
            else:
                return None
    except (ValueError, IndexError):
        return None

    if not host or not port:
        return None
    return ProxyEntry(id=next_id, host=host, port=port, proxy_type=proxy_type, user=user, password=password)


def import_lines(text: str) -> dict:
    existing = load_proxies(include_invalid=True)
    by_key = {(e.host, e.port, e.user): e for e in existing}
    next_id = max((e.id for e in existing), default=0) + 1
    added = 0
    for line in text.splitlines():
        entry = parse_proxy_line(line, next_id)
        if entry is None:
            continue
        key = (entry.host, entry.port, entry.user)
        if key in by_key:
            # refresh password if same endpoint
            old = by_key[key]
            if entry.password and entry.password != old.password:
                old.password = entry.password
                old.valid = True
            continue
        existing.append(entry)
        by_key[key] = entry
        next_id += 1
        added += 1
    save_proxies(existing)
    return {"ok": True, "added": added, "total": len(existing), "valid": sum(1 for e in existing if e.valid)}


def tcp_check(host: str, port: int, timeout: float = 4.0) -> tuple[bool, int, str]:
    """Быстрая проверка: порт открыт (прокси отвечает на TCP)."""
    import time

    t0 = time.perf_counter()
    try:
        with socket.create_connection((host, port), timeout=timeout):
            ms = int((time.perf_counter() - t0) * 1000)
            return True, ms, ""
    except OSError as ex:
        ms = int((time.perf_counter() - t0) * 1000)
        return False, ms, str(ex)


def check_all(timeout: float = 4.0) -> dict:
    entries = load_proxies(include_invalid=True)
    alive = 0
    dead = 0
    for e in entries:
        ok, ms, err = tcp_check(e.host, e.port, timeout=timeout)
        e.last_check_ok = ok
        e.last_check_ms = ms
        e.last_error = err
        if ok:
            alive += 1
            e.valid = True
        else:
            dead += 1
            e.valid = False
    save_proxies(entries)
    return {
        "ok": True,
        "alive": alive,
        "dead": dead,
        "total": len(entries),
        "proxies": [e.as_dict() for e in entries],
    }


def mark_invalid(proxy_id: int) -> bool:
    entries = load_proxies(include_invalid=True)
    found = False
    for e in entries:
        if e.id == proxy_id:
            e.valid = False
            found = True
            break
    if found:
        save_proxies(entries)
        pruned = [e for e in entries if e.valid]
        invalid = [e for e in entries if not e.valid]
        keep_invalid = invalid[-5:] if len(invalid) > 5 else invalid
        save_proxies(pruned + keep_invalid)
    return found


def purge_invalid() -> int:
    entries = load_proxies(include_invalid=True)
    valid = [e for e in entries if e.valid]
    removed = len(entries) - len(valid)
    save_proxies(valid)
    return removed


def assign_proxy(account_id: int, proxy_id: Optional[int] = None) -> Optional[ProxyEntry]:
    """Один прокси = один аккаунт (sticky). Не крутить каждый день — только при бане."""
    proxies = load_proxies(include_invalid=False)
    if not proxies:
        return None
    assign = load_assignments()
    if proxy_id is None:
        used = set(assign.values())
        pick = next((p for p in proxies if p.id not in used), None)
        if pick is None:
            # все заняты — не шарим, лучше вернуть None чем крутить
            return None
        proxy_id = pick.id
    else:
        pick = next((p for p in proxies if p.id == proxy_id), None)
        if pick is None:
            return None
    assign[account_id] = proxy_id
    save_assignments(assign)
    return pick


def rotate_proxy(account_id: int) -> Optional[ProxyEntry]:
    """Только при FLOOD/BAN: текущий → invalid, новый sticky."""
    assign = load_assignments()
    current = assign.get(account_id)
    if current is not None:
        mark_invalid(current)
    proxies = load_proxies(include_invalid=False)
    if not proxies:
        assign.pop(account_id, None)
        save_assignments(assign)
        return None
    used = {v for k, v in assign.items() if k != account_id}
    pick = next((p for p in proxies if p.id != current and p.id not in used), None)
    if pick is None:
        pick = next((p for p in proxies if p.id != current), proxies[0])
    assign[account_id] = pick.id
    save_assignments(assign)
    return pick


def proxy_for_account(account_id: int):
    """Telethon proxy tuple or global config.PROXY."""
    assign = load_assignments()
    proxy_id = assign.get(account_id)
    if proxy_id is not None:
        for p in load_proxies(include_invalid=False):
            if p.id == proxy_id:
                return p.telethon_tuple()
    return config.PROXY
