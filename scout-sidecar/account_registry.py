"""CRUD for accounts.json + session files (Telethon .session / auth key)."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Optional

from telethon import TelegramClient
from telethon.crypto import AuthKey
from telethon.sessions import SQLiteSession

import config
import clients

# Official Telegram DC map (IPv4)
_DC_MAP = {
    1: ("149.154.175.53", 443),
    2: ("149.154.167.51", 443),
    3: ("149.154.175.100", 443),
    4: ("149.154.167.91", 443),
    5: ("91.108.56.130", 443),
}


def _read_accounts_file() -> dict:
    if not config.ACCOUNTS_FILE.exists():
        return {"accounts": []}
    return json.loads(config.ACCOUNTS_FILE.read_text(encoding="utf-8-sig"))


def _write_accounts_file(data: dict) -> None:
    config.ACCOUNTS_FILE.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def upsert_account(account_id: int, label: str, account_type: str, session: Optional[str] = None) -> dict:
    data = _read_accounts_file()
    accounts: list[dict] = data.get("accounts", [])
    found = None
    for item in accounts:
        if int(item["id"]) == int(account_id):
            found = item
            break
    if found is None:
        found = {"id": int(account_id)}
        accounts.append(found)
    found["label"] = label
    found["type"] = account_type.upper()
    # Never wipe an existing session file binding — sync from admin used to replace
    # outreach-1 → acc-1 and broke live logins.
    if session and not found.get("session"):
        found["session"] = session
    elif not found.get("session"):
        found["session"] = session or f"acc-{account_id}"
    data["accounts"] = accounts
    _write_accounts_file(data)
    return found


def _auth_key_hint(hex_key: str) -> str:
    h = "".join(c for c in (hex_key or "").strip().lower() if c in "0123456789abcdef")
    if len(h) < 16:
        return ""
    return f"{h[:8]}…{h[-8:]}"


def _secrets_path() -> Path:
    return config.ROOT / "accounts.secrets.json"


def load_secret_row(account_id: int) -> Optional[dict]:
    path = _secrets_path()
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
    except Exception:
        return None
    for item in data.get("accounts", []):
        if int(item.get("id", -1)) == int(account_id):
            return item
    return None


def secrets_identity(account_id: int) -> dict[str, Any]:
    """Lolz-style fields from accounts.secrets.json (never returns full auth key)."""
    row = load_secret_row(account_id)
    if not row:
        return {"hasSecrets": False}
    comment = str(row.get("comment") or "")
    phone = ""
    for token in comment.replace("—", " ").replace("-", " ").split():
        digits = "".join(c for c in token if c.isdigit())
        if len(digits) >= 10:
            phone = digits
            break
    return {
        "hasSecrets": True,
        "phone": phone or None,
        "userId": row.get("user_id"),
        "dcId": row.get("dc_id"),
        "authKeyHint": _auth_key_hint(str(row.get("auth_key_hex") or "")),
        "shopNote": comment or None,
        "source": "accounts.secrets.json",
    }


def _patch_account_row(account_id: int, patch: dict[str, Any]) -> dict:
    data = _read_accounts_file()
    accounts: list[dict] = data.get("accounts", [])
    found = None
    for item in accounts:
        if int(item["id"]) == int(account_id):
            found = item
            break
    if found is None:
        found = {"id": int(account_id)}
        accounts.append(found)
    for k, v in patch.items():
        if v is None or v == "":
            continue
        found[k] = v
    data["accounts"] = accounts
    _write_accounts_file(data)
    return found


def save_identity(
    account_id: int,
    me: Optional[dict] = None,
    dc_id: Optional[int] = None,
    auth_key_hex: Optional[str] = None,
    shop_note: Optional[str] = None,
    phone: Optional[str] = None,
    user_id: Optional[int] = None,
) -> dict:
    """Persist identity so card stays identifiable after session loss."""
    patch: dict[str, Any] = {}
    if me:
        if me.get("id") is not None:
            patch["tgUserId"] = int(me["id"])
        if me.get("phone"):
            patch["phone"] = str(me["phone"])
        if me.get("username") is not None:
            patch["username"] = me.get("username") or ""
        if me.get("first_name") is not None:
            patch["firstName"] = me.get("first_name") or ""
        if me.get("last_name") is not None:
            patch["lastName"] = me.get("last_name") or ""
    if user_id is not None:
        patch["tgUserId"] = int(user_id)
    if phone:
        patch["phone"] = str(phone).strip()
    if dc_id is not None:
        patch["dcId"] = int(dc_id)
    hint = _auth_key_hint(auth_key_hex or "")
    if hint:
        patch["authKeyHint"] = hint
    if shop_note is not None:
        patch["shopNote"] = shop_note
    if not patch:
        return {}
    return _patch_account_row(account_id, patch)


def cached_identity(account_id: int) -> dict[str, Any]:
    data = _read_accounts_file()
    for item in data.get("accounts", []):
        if int(item.get("id", -1)) != int(account_id):
            continue
        return {
            "phone": item.get("phone"),
            "userId": item.get("tgUserId"),
            "username": item.get("username"),
            "firstName": item.get("firstName"),
            "lastName": item.get("lastName"),
            "dcId": item.get("dcId"),
            "authKeyHint": item.get("authKeyHint"),
            "shopNote": item.get("shopNote"),
            "source": "accounts.json",
        }
    return {}


def session_meta(account_id: int) -> dict[str, Any]:
    """Read dc / auth hint from .session without connecting to Telegram."""
    accounts = config.load_accounts()
    acc = accounts.get(int(account_id))
    if acc is None:
        return {}
    path = config.session_path(acc)
    candidates = [path, Path(str(path) + ".session")]
    session_file = next((p for p in candidates if p.exists()), None)
    if session_file is None:
        return {}
    try:
        # SQLiteSession wants path without .session suffix
        base = str(session_file)
        if base.endswith(".session"):
            base = base[: -len(".session")]
        sess = SQLiteSession(base)
        out: dict[str, Any] = {}
        if getattr(sess, "dc_id", None):
            out["dcId"] = int(sess.dc_id)
        key = getattr(sess, "auth_key", None)
        raw = getattr(key, "key", None) if key is not None else None
        if raw:
            hx = bytes(raw).hex()
            out["authKeyHint"] = _auth_key_hint(hx)
        return out
    except Exception:
        return {}


def _session_key_hash(path: Path) -> str | None:
    """SHA1-отпечаток auth_key из .session — чтобы ловить дубли ключей."""
    import hashlib
    import sqlite3

    try:
        con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        try:
            row = con.execute("select dc_id, auth_key from sessions").fetchone()
        finally:
            con.close()
    except Exception:
        return None
    if not row or not row[1]:
        return None
    return hashlib.sha1(bytes(row[1])).hexdigest()[:12]


def audit_sessions() -> dict[str, Any]:
    """Один auth_key в двух .session = Telegram убьёт ключ (AuthKeyDuplicated).

    Возвращает отпечатки по аккаунтам, группы дублей и «бесхозные» сессии.
    """
    accounts = config.load_accounts()
    by_account: dict[str, Any] = {}
    owned: set[str] = set()
    groups: dict[str, list[str]] = {}

    for acc_id, acc in accounts.items():
        path = config.session_path(acc)
        session_file = next(
            (p for p in (path, Path(str(path) + ".session")) if p.exists()), None
        )
        entry: dict[str, Any] = {"sessionFile": session_file is not None}
        if session_file is not None:
            owned.add(str(session_file.resolve()).lower())
            key_hash = _session_key_hash(session_file)
            entry["keyHash"] = key_hash
            entry["file"] = session_file.name
            if key_hash:
                groups.setdefault(key_hash, []).append(f"acc#{acc_id}")
        by_account[str(acc_id)] = entry

    orphans: list[dict[str, Any]] = []
    if config.SESSIONS_DIR.is_dir():
        for p in sorted(config.SESSIONS_DIR.glob("*.session")):
            if str(p.resolve()).lower() in owned:
                continue
            key_hash = _session_key_hash(p)
            orphans.append({"file": p.name, "keyHash": key_hash})
            if key_hash:
                groups.setdefault(key_hash, []).append(p.name)

    duplicates = [
        {"keyHash": k, "used_by": v} for k, v in groups.items() if len(v) > 1
    ]
    dup_hashes = {d["keyHash"] for d in duplicates}
    for acc_id, entry in by_account.items():
        entry["duplicateKey"] = entry.get("keyHash") in dup_hashes
        if entry["duplicateKey"]:
            entry["duplicateWith"] = [
                name for name in groups.get(entry["keyHash"], []) if name != f"acc#{acc_id}"
            ]

    return {
        "ok": True,
        "accounts": by_account,
        "duplicates": duplicates,
        "orphans": orphans,
    }


def delete_orphan_session(file_name: str) -> dict:
    """Снести бесхозный .session (не привязан ни к одному аккаунту)."""
    audit = audit_sessions()
    names = {o["file"] for o in audit.get("orphans", [])}
    if file_name not in names:
        return {"ok": False, "error": "это не бесхозная сессия"}
    target = config.SESSIONS_DIR / file_name
    try:
        target.unlink()
    except Exception as ex:
        return {"ok": False, "error": str(ex)}
    return {"ok": True, "removed": file_name}


def merge_identity(account_id: int) -> dict[str, Any]:
    """Best-effort identity for admin card: live cache + secrets + session file."""
    cached = cached_identity(account_id)
    secret = secrets_identity(account_id)
    meta = session_meta(account_id)
    out: dict[str, Any] = {
        "phone": cached.get("phone") or secret.get("phone"),
        "userId": cached.get("userId") or secret.get("userId"),
        "username": cached.get("username"),
        "firstName": cached.get("firstName"),
        "lastName": cached.get("lastName"),
        "dcId": cached.get("dcId") or secret.get("dcId") or meta.get("dcId"),
        "authKeyHint": cached.get("authKeyHint")
        or secret.get("authKeyHint")
        or meta.get("authKeyHint"),
        "shopNote": cached.get("shopNote") or secret.get("shopNote"),
        "hasSecrets": bool(secret.get("hasSecrets")),
    }
    sources = []
    if any(cached.get(k) for k in ("phone", "userId", "dcId", "authKeyHint", "shopNote")):
        sources.append("accounts.json")
    if secret.get("hasSecrets"):
        sources.append("secrets")
    if meta:
        sources.append("session")
    out["sources"] = sources
    return out


def account_status(account_id: int) -> dict[str, Any]:
    accounts = config.load_accounts()
    acc = accounts.get(int(account_id))
    if acc is None:
        return {
            "ok": True,
            "registered": False,
            "sessionFile": False,
            "authorized": False,
            "error": "account not in sidecar accounts.json",
            "identity": merge_identity(account_id),
        }
    path = config.session_path(acc)
    session_file = path.with_suffix(".session") if path.suffix != ".session" else path
    # Telethon SQLiteSession adds .session to path if not present
    candidates = [session_file, Path(str(path) + ".session"), path]
    exists = any(p.exists() for p in candidates)
    return {
        "ok": True,
        "registered": True,
        "label": acc.label,
        "type": acc.account_type,
        "session": acc.session,
        "sessionFile": exists,
        "sessionPath": str(candidates[0]),
        "identity": merge_identity(account_id),
    }


def save_session_bytes(account_id: int, raw: bytes, filename: str = "") -> dict:
    accounts = config.load_accounts()
    acc = accounts.get(int(account_id))
    if acc is None:
        return {"ok": False, "error": "account not registered in sidecar — create card again"}
    config.SESSIONS_DIR.mkdir(parents=True, exist_ok=True)
    dest = config.session_path(acc)
    # Telethon expects path without mandatory .session suffix sometimes; write as name.session
    if not str(dest).endswith(".session"):
        dest = Path(str(dest) + ".session")

    import tempfile

    probe = Path(tempfile.mkdtemp(prefix="pulse-session-")) / "probe.session"
    try:
        probe.write_bytes(raw)
        incoming_hash = _session_key_hash(probe)
    finally:
        try:
            probe.unlink()
            probe.parent.rmdir()
        except Exception:
            pass
    if incoming_hash:
        for other_id, other in accounts.items():
            if int(other_id) == int(account_id):
                continue
            other_path = config.session_path(other)
            other_file = next(
                (p for p in (other_path, Path(str(other_path) + ".session")) if p.exists()),
                None,
            )
            if other_file is not None and _session_key_hash(other_file) == incoming_hash:
                return {
                    "ok": False,
                    "error": (
                        f"этот .session уже стоит в acc#{other_id} ({other.label}). "
                        "Один ключ в двух сессиях = Telegram убьёт аккаунт."
                    ),
                }

    dest.write_bytes(raw)
    return {"ok": True, "path": str(dest), "bytes": len(raw), "filename": filename}


def _session_files(account_id: int) -> list[Path]:
    accounts = config.load_accounts()
    acc = accounts.get(int(account_id))
    if acc is None:
        return []
    path = config.session_path(acc)
    return [
        path,
        Path(str(path) + ".session"),
        Path(str(path) + ".session-journal"),
        Path(str(path) + "-journal"),
    ]


async def _wipe_session_files(account_id: int) -> dict:
    import time

    files = _session_files(account_id)
    if not files:
        return {"ok": False, "error": "account not registered in sidecar"}
    await invalidate_client(account_id)
    removed: list[str] = []
    for _ in range(5):
        blocked = False
        for p in files:
            if not p.exists():
                continue
            try:
                p.unlink()
                removed.append(p.name)
            except PermissionError:
                blocked = True
        if not blocked:
            break
        time.sleep(0.4)
    else:
        return {"ok": False, "error": "session-файл занят — подожди пару секунд и повтори"}
    return {"ok": True, "removed": removed}


def find_key_owner(auth_key: bytes, skip_account_id: int | None = None) -> str | None:
    """Кто уже держит этот auth_key: 'acc#N (label)' или имя файла сессии."""
    import hashlib

    target = hashlib.sha1(bytes(auth_key)).hexdigest()[:12]
    accounts = config.load_accounts()
    owned: set[str] = set()
    for acc_id, acc in accounts.items():
        path = config.session_path(acc)
        session_file = next(
            (p for p in (path, Path(str(path) + ".session")) if p.exists()), None
        )
        if session_file is None:
            continue
        owned.add(str(session_file.resolve()).lower())
        if skip_account_id is not None and int(acc_id) == int(skip_account_id):
            continue
        if _session_key_hash(session_file) == target:
            return f"acc#{acc_id} ({acc.label})"
    if config.SESSIONS_DIR.is_dir():
        for p in sorted(config.SESSIONS_DIR.glob("*.session")):
            if str(p.resolve()).lower() in owned:
                continue
            if _session_key_hash(p) == target:
                return f"файл {p.name}"
    return None


def clear_live_identity(account_id: int) -> dict:
    """Сбросить то, что пришло из сессии (кто в неё реально залогинен).

    shopNote / dcId / authKeyHint остаются — по ним и опознаём покупку.
    Нужно после снесённой или подменённой сессии, иначе в карточке висит чужой номер.
    """
    data = _read_accounts_file()
    for item in data.get("accounts", []):
        if int(item.get("id", -1)) != int(account_id):
            continue
        for key in ("phone", "tgUserId", "username", "firstName", "lastName"):
            item.pop(key, None)
        _write_accounts_file(data)
        return item
    return {}


async def wipe_session(account_id: int) -> dict:
    """Снести .session; данные покупки (shopNote / secrets) остаются."""
    res = await _wipe_session_files(account_id)
    if res.get("ok"):
        clear_live_identity(account_id)
    return res


async def delete_account(account_id: int, wipe_session: bool = True) -> dict:
    """Убрать карточку из accounts.json (+ по умолчанию удалить сессию)."""
    removed: list[str] = []
    if wipe_session:
        res = await _wipe_session_files(account_id)
        if not res.get("ok"):
            return res
        removed = res.get("removed", [])
    else:
        await invalidate_client(account_id)

    data = _read_accounts_file()
    accounts: list[dict] = data.get("accounts", [])
    before = len(accounts)
    accounts = [a for a in accounts if int(a.get("id", -1)) != int(account_id)]
    data["accounts"] = accounts
    _write_accounts_file(data)

    assignments_removed = False
    try:
        import proxy_pool

        assign = proxy_pool.load_assignments()
        for key in (int(account_id), str(account_id)):
            if key in assign:
                assign.pop(key, None)
                assignments_removed = True
        if assignments_removed:
            proxy_pool.save_assignments(assign)
    except Exception:
        pass

    return {
        "ok": True,
        "deleted": before != len(accounts),
        "sessionRemoved": removed,
        "proxyReleased": assignments_removed,
    }


async def invalidate_client(account_id: int) -> None:
    try:
        await clients.disconnect_account(int(account_id))
    except Exception:
        pass


async def import_auth_key(account_id: int, auth_key_hex: str, dc_id: int) -> dict:
    accounts = config.load_accounts()
    acc = accounts.get(int(account_id))
    if acc is None:
        return {"ok": False, "error": "account not registered in sidecar"}
    hex_clean = "".join(c for c in auth_key_hex.strip().lower() if c in "0123456789abcdef")
    if len(hex_clean) != 512:
        return {
            "ok": False,
            "error": f"auth_key_hex must be 512 hex chars (got {len(hex_clean)})",
        }
    if dc_id not in _DC_MAP:
        return {"ok": False, "error": f"dc_id must be 1..5 (got {dc_id})"}
    clash = find_key_owner(bytes.fromhex(hex_clean), skip_account_id=account_id)
    if clash:
        return {
            "ok": False,
            "error": (
                f"этот auth_key уже стоит в {clash}. Один ключ в двух сессиях = "
                "Telegram убьёт аккаунт. Заведи скауту свой аккаунт или сначала "
                "сбрось сессию у того слота."
            ),
        }
    ip, port = _DC_MAP[dc_id]
    path = config.session_path(acc)
    # remove old
    for p in (path, Path(str(path) + ".session")):
        if p.exists():
            p.unlink()
    session = SQLiteSession(str(path))
    session.set_dc(dc_id, ip, port)
    session.auth_key = AuthKey(bytes.fromhex(hex_clean))
    session.save()
    await invalidate_client(account_id)
    client = TelegramClient(session, config.API_ID, config.API_HASH)
    try:
        await client.connect()
        if not await client.is_user_authorized():
            return {"ok": False, "error": "session not authorized — bad key/dc"}
        me = await client.get_me()
        session.save()
        me_dict = {
            "id": me.id,
            "username": me.username or "",
            "first_name": me.first_name or "",
            "last_name": me.last_name or "",
            "phone": me.phone or "",
        }
        save_identity(account_id, me=me_dict, dc_id=dc_id, auth_key_hex=hex_clean)
        return {
            "ok": True,
            "me": me_dict,
            "identity": merge_identity(account_id),
        }
    finally:
        await client.disconnect()


async def restore_from_secrets(account_id: int) -> dict:
    """Re-import auth key from accounts.secrets.json for this account id."""
    row = load_secret_row(account_id)
    if not row:
        return {
            "ok": False,
            "error": f"нет записи id={account_id} в accounts.secrets.json",
        }
    hex_key = str(row.get("auth_key_hex") or "")
    try:
        dc_id = int(row.get("dc_id") or 0)
    except (TypeError, ValueError):
        dc_id = 0
    if not hex_key or not dc_id:
        return {"ok": False, "error": "в secrets нет auth_key_hex / dc_id"}
    result = await import_auth_key(account_id, hex_key, dc_id)
    if result.get("ok"):
        note = str(row.get("comment") or "")
        save_identity(account_id, shop_note=note or None)
        result["restoredFrom"] = "accounts.secrets.json"
        result["identity"] = merge_identity(account_id)
    return result


async def verify_session(account_id: int) -> dict:
    accounts = config.load_accounts()
    acc = accounts.get(int(account_id))
    if acc is None:
        return {"ok": False, "error": "not registered"}
    path = config.session_path(acc)
    session_exists = path.exists() or Path(str(path) + ".session").exists()
    if not session_exists:
        return {"ok": False, "error": "no session file", "sessionFile": False}
    client = TelegramClient(str(path), config.API_ID, config.API_HASH)
    try:
        await client.connect()
        if not await client.is_user_authorized():
            return {"ok": False, "error": "session file exists but not authorized", "sessionFile": True}
        me = await client.get_me()
        me_dict = {
            "id": me.id,
            "username": me.username or "",
            "first_name": me.first_name or "",
            "last_name": me.last_name or "",
            "phone": me.phone or "",
        }
        meta = session_meta(account_id)
        save_identity(account_id, me=me_dict, dc_id=meta.get("dcId"))
        return {
            "ok": True,
            "sessionFile": True,
            "authorized": True,
            "me": me_dict,
            "identity": merge_identity(account_id),
        }
    finally:
        await client.disconnect()


def _find_tdata_root(root: Path) -> Path | None:
    """Accept extracted zip that contains tdata/ or is already the tdata folder."""
    if (root / "key_datas").exists():
        return root
    direct = root / "tdata"
    if direct.is_dir() and (direct / "key_datas").exists():
        return direct
    for child in root.rglob("key_datas"):
        return child.parent
    return None


async def import_tdata_zip(account_id: int, zip_bytes: bytes) -> dict:
    import io
    import shutil
    import tempfile
    import zipfile

    accounts = config.load_accounts()
    acc = accounts.get(int(account_id))
    if acc is None:
        return {"ok": False, "error": "account not registered in sidecar"}

    from tgcrypto_shim import ensure_tgcrypto

    ensure_tgcrypto()

    try:
        from opentele.td import TDesktop
        from opentele.api import UseCurrentSession, API
    except Exception as ex:
        return {"ok": False, "error": f"opentele unavailable: {ex}"}

    tmp = Path(tempfile.mkdtemp(prefix="pulse-tdata-"))
    try:
        zip_path = tmp / "upload.zip"
        zip_path.write_bytes(zip_bytes)
        extract_dir = tmp / "extracted"
        extract_dir.mkdir()
        try:
            with zipfile.ZipFile(zip_path, "r") as zf:
                zf.extractall(extract_dir)
        except zipfile.BadZipFile:
            return {"ok": False, "error": "not a zip — упакуй папку tdata в .zip"}

        tdata_root = _find_tdata_root(extract_dir)
        if tdata_root is None:
            return {
                "ok": False,
                "error": "внутри zip нет tdata/key_datas — заархивируй папку tdata целиком",
            }

        td = TDesktop(str(tdata_root))
        if not td.isLoaded() or not td.accounts:
            return {"ok": False, "error": "tdata не читается (битая / с паролем / не Desktop)"}

        try:
            td_key = getattr(td.accounts[0].authKey, "key", None)
        except Exception:
            td_key = None
        if td_key:
            clash = find_key_owner(bytes(td_key), skip_account_id=account_id)
            if clash:
                return {
                    "ok": False,
                    "error": (
                        f"этот tdata уже залит в {clash}. Один аккаунт в двух слотах = "
                        "Telegram убьёт ключ. Заведи скауту отдельный аккаунт, "
                        "либо сначала «Сбросить сессию» у того слота."
                    ),
                }

        path = config.session_path(acc)
        await invalidate_client(account_id)
        # SQLite may stay locked briefly after disconnect
        import time
        for attempt in range(5):
            try:
                for p in (path, Path(str(path) + ".session"), Path(str(path) + ".session-journal")):
                    if p.exists():
                        p.unlink()
                break
            except PermissionError:
                time.sleep(0.4)
        else:
            return {
                "ok": False,
                "error": "session-файл занят sidecar — подожди 2 сек и загрузи tdata ещё раз",
            }

        client = await td.ToTelethon(
            session=str(path),
            flag=UseCurrentSession,
            api=API.TelegramDesktop,
        )
        try:
            await client.connect()
            if not await client.is_user_authorized():
                # don't leave a dead session pretending to be ready
                await client.disconnect()
                for p in (path, Path(str(path) + ".session")):
                    if p.exists():
                        try:
                            p.unlink()
                        except Exception:
                            pass
                return {"ok": False, "error": "tdata конвертирован, но сессия не авторизована (битый tdata?)"}
            me = await client.get_me()
            me_dict = {
                "id": me.id,
                "username": me.username or "",
                "first_name": me.first_name or "",
                "last_name": me.last_name or "",
                "phone": me.phone or "",
            }
            meta = session_meta(account_id)
            save_identity(account_id, me=me_dict, dc_id=meta.get("dcId"))
            return {
                "ok": True,
                "authorized": True,
                "me": me_dict,
                "path": str(path) + ".session",
                "identity": merge_identity(account_id),
            }
        finally:
            await client.disconnect()
    except Exception as ex:
        return {"ok": False, "error": str(ex)}
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


async def import_tdata_dir(account_id: int, tdata_path: str) -> dict:
    """Console helper: path to tdata folder or parent containing tdata/."""
    import io
    import zipfile

    p = Path(tdata_path)
    if not p.exists():
        return {"ok": False, "error": f"path not found: {tdata_path}"}
    if p.is_file() and p.suffix.lower() == ".zip":
        return await import_tdata_zip(account_id, p.read_bytes())

    root = _find_tdata_root(p)
    if root is None and (p / "tdata").exists():
        root = _find_tdata_root(p / "tdata")
    if root is None:
        return {"ok": False, "error": "нет key_datas в этой папке"}

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in root.rglob("*"):
            if f.is_file():
                zf.write(f, arcname=str(Path("tdata") / f.relative_to(root)))
    return await import_tdata_zip(account_id, buf.getvalue())
