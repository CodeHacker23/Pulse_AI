"""Ops-бэкап ЛС скаутов: текст / edit / delete / медиа → Java.

Не релей-бот. Группы и каналы не пишем.
Медиа качаем сразу (у Telegram короткий TTL на файлы).
"""

from __future__ import annotations

import asyncio
import json
import logging
import mimetypes
import os
import uuid
import urllib.error
import urllib.request

from datetime import datetime, timezone
from pathlib import Path

from telethon import events
from telethon.errors import FloodWaitError
from telethon.tl.types import (
    DocumentAttributeAudio,
    DocumentAttributeFilename,
    DocumentAttributeSticker,
    DocumentAttributeVideo,
    MessageMediaDocument,
    MessageMediaPhoto,
    User,
)

log = logging.getLogger("pulse.archive")

ARCHIVE_URL = os.getenv("PULSE_ARCHIVE_URL", "http://127.0.0.1:8081/internal/scout-archive").rstrip("/")
ARCHIVE_TOKEN = os.getenv(
    "PULSE_ARCHIVE_TOKEN",
    os.getenv("PULSE_ADMIN_TOKEN", "pulse-local-admin"),
)
MAX_MEDIA_BYTES = int(os.getenv("PULSE_ARCHIVE_MAX_MEDIA_BYTES", str(25 * 1024 * 1024)))
BACKFILL_STATE = Path(os.getenv(
    "PULSE_ARCHIVE_BACKFILL_STATE",
    str(Path(__file__).resolve().parent / "sessions" / "archive_backfill.json"),
))
BACKFILL_MAX_DIALOGS = int(os.getenv("PULSE_ARCHIVE_BACKFILL_DIALOGS", "40"))
BACKFILL_MAX_PER_DIALOG = int(os.getenv("PULSE_ARCHIVE_BACKFILL_PER_DIALOG", "80"))
BACKFILL_TTL_HOURS = int(os.getenv("PULSE_ARCHIVE_BACKFILL_TTL_HOURS", str(7 * 24)))

_attached: set[int] = set()
_backfill_tasks: dict[int, asyncio.Task] = {}
_backfill_status: dict[int, dict] = {}


def detach(account_id: int) -> None:
    _attached.discard(account_id)


def attach(account_id: int, client) -> None:
    if account_id in _attached:
        return
    _attached.add(account_id)

    @client.on(events.NewMessage(func=lambda e: e.is_private))
    async def on_new(event):
        await _emit_event(account_id, event, "new")

    @client.on(events.MessageEdited(func=lambda e: e.is_private))
    async def on_edit(event):
        await _emit_event(account_id, event, "edit")

    @client.on(events.MessageDeleted)
    async def on_delete(event):
        chat_id = event.chat_id
        if chat_id is None or int(chat_id) < 0:
            return
        for mid in event.deleted_ids or []:
            await push({
                "accountId": account_id,
                "peerId": str(chat_id),
                "messageId": int(mid),
                "event": "delete",
            })


async def _emit_event(account_id: int, event, kind: str) -> None:
    msg = event.message
    if msg is None:
        return
    try:
        chat = await event.get_chat()
    except Exception:
        return
    await emit_message(account_id, msg, chat, kind, download_media=True)


async def emit_message(account_id: int, msg, chat, kind: str, download_media: bool = True) -> None:
    if msg is None or not isinstance(chat, User):
        return
    username = (chat.username or "").lower()
    name = " ".join(x for x in [chat.first_name, chat.last_name] if x) or username or str(chat.id)
    payload = {
        "accountId": account_id,
        "peerId": str(chat.id),
        "peerUsername": username,
        "peerName": name,
        "messageId": msg.id,
        "out": bool(msg.out),
        "text": (msg.message or "")[:8000],
        "date": msg.date.isoformat() if msg.date else None,
        "event": kind,
    }

    media_bytes = None
    filename = None
    mime = None
    media_kind = None
    if kind != "delete" and msg.media:
        media_kind, filename, mime = _media_meta(msg)
        if media_kind:
            payload["mediaKind"] = media_kind
        if download_media:
            try:
                data = await msg.download_media(file=bytes)
                if isinstance(data, (bytes, bytearray)) and data:
                    if len(data) > MAX_MEDIA_BYTES:
                        log.warning(
                            "archive skip large media acc=%s msg=%s size=%s",
                            account_id, msg.id, len(data),
                        )
                    else:
                        media_bytes = bytes(data)
            except Exception as ex:
                log.warning("archive media download failed acc=%s msg=%s: %s", account_id, msg.id, ex)

    if media_bytes:
        if not filename:
            filename = f"{msg.id}{_ext_for(media_kind, mime)}"
        if not mime:
            mime = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        await push_media(payload, media_bytes, filename, mime)
    else:
        await push(payload)


def _media_meta(msg) -> tuple[str | None, str | None, str | None]:
    media = msg.media
    if isinstance(media, MessageMediaPhoto):
        return "photo", f"{msg.id}.jpg", "image/jpeg"
    if isinstance(media, MessageMediaDocument) and media.document:
        doc = media.document
        mime = getattr(doc, "mime_type", None) or "application/octet-stream"
        filename = None
        kind = "document"
        for attr in getattr(doc, "attributes", []) or []:
            if isinstance(attr, DocumentAttributeFilename) and attr.file_name:
                filename = attr.file_name
            elif isinstance(attr, DocumentAttributeSticker):
                kind = "sticker"
            elif isinstance(attr, DocumentAttributeVideo):
                kind = "video"
            elif isinstance(attr, DocumentAttributeAudio):
                kind = "voice" if getattr(attr, "voice", False) else "audio"
        if kind == "document":
            if mime.startswith("image/"):
                kind = "photo"
            elif mime.startswith("video/"):
                kind = "video"
            elif mime.startswith("audio/"):
                kind = "audio"
        return kind, filename, mime
    return None, None, None


def _ext_for(kind: str | None, mime: str | None) -> str:
    if mime:
        ext = mimetypes.guess_extension(mime.split(";")[0].strip())
        if ext:
            return ext
    return {
        "photo": ".jpg",
        "video": ".mp4",
        "voice": ".ogg",
        "audio": ".mp3",
        "sticker": ".webp",
        "document": ".bin",
    }.get(kind or "", ".bin")


async def push(payload: dict) -> None:
    if not ARCHIVE_URL:
        return
    await asyncio.to_thread(_post_json, payload)


async def push_media(payload: dict, file_bytes: bytes, filename: str, mime: str) -> None:
    if not ARCHIVE_URL:
        return
    await asyncio.to_thread(_post_multipart, payload, file_bytes, filename, mime)


def _post_json(payload: dict) -> None:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        ARCHIVE_URL,
        data=data,
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "X-Admin-Token": ARCHIVE_TOKEN,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=12) as resp:
            resp.read()
    except (urllib.error.URLError, TimeoutError, OSError) as ex:
        log.warning("archive push failed acc=%s: %s", payload.get("accountId"), ex)


def _post_multipart(payload: dict, file_bytes: bytes, filename: str, mime: str) -> None:
    boundary = "----Pulse" + uuid.uuid4().hex
    meta = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    safe_name = (filename or "file.bin").replace('"', "_")
    parts: list[bytes] = []
    parts.append(
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="meta"\r\n'
        f"Content-Type: application/json; charset=utf-8\r\n\r\n".encode("utf-8")
        + meta
        + b"\r\n"
    )
    parts.append(
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{safe_name}"\r\n'
        f"Content-Type: {mime or 'application/octet-stream'}\r\n\r\n".encode("utf-8")
        + file_bytes
        + b"\r\n"
    )
    parts.append(f"--{boundary}--\r\n".encode("utf-8"))
    body = b"".join(parts)
    url = ARCHIVE_URL + "/media"
    req = urllib.request.Request(
        url,
        data=body,
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "X-Admin-Token": ARCHIVE_TOKEN,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            resp.read()
    except (urllib.error.URLError, TimeoutError, OSError) as ex:
        log.warning("archive media push failed acc=%s: %s", payload.get("accountId"), ex)
        # текст всё равно сохраним
        _post_json(payload)


def backfill_status(account_id: int) -> dict:
    running = _task_running(account_id)
    live = dict(_backfill_status.get(account_id) or {})
    saved = _load_state().get(str(account_id), {})
    out = {
        "accountId": account_id,
        "state": "running" if running else (live.get("state") or ("done" if saved.get("lastOk") else "idle")),
        "dialogs": live.get("dialogs", saved.get("dialogs", 0)),
        "messages": live.get("messages", saved.get("messages", 0)),
        "media": live.get("media", saved.get("media", 0)),
        "error": live.get("error") or saved.get("error"),
        "lastOk": saved.get("lastOk"),
        "startedAt": live.get("startedAt"),
        "recent": _recently_done(account_id),
    }
    return out


def start_backfill(account_id: int, client, force: bool = False,
                   max_dialogs: int | None = None, max_per_dialog: int | None = None) -> dict:
    if _task_running(account_id):
        return {"ok": True, "started": False, "reason": "already running", **backfill_status(account_id)}
    if not force and _recently_done(account_id):
        return {"ok": True, "started": False, "reason": "recent", **backfill_status(account_id)}
    dialogs = max(1, min(int(max_dialogs or BACKFILL_MAX_DIALOGS), 80))
    per = max(1, min(int(max_per_dialog or BACKFILL_MAX_PER_DIALOG), 120))
    task = asyncio.create_task(_run_backfill(account_id, client, force, dialogs, per))
    _backfill_tasks[account_id] = task
    return {"ok": True, "started": True, "state": "running", "accountId": account_id}


def _task_running(account_id: int) -> bool:
    task = _backfill_tasks.get(account_id)
    return task is not None and not task.done()


def _recently_done(account_id: int) -> bool:
    last = _load_state().get(str(account_id), {}).get("lastOk")
    if not last:
        return False
    try:
        at = datetime.fromisoformat(str(last).replace("Z", "+00:00"))
        if at.tzinfo is None:
            at = at.replace(tzinfo=timezone.utc)
        age_h = (datetime.now(timezone.utc) - at).total_seconds() / 3600
        return age_h < BACKFILL_TTL_HOURS
    except ValueError:
        return False


def _load_state() -> dict:
    if not BACKFILL_STATE.exists():
        return {}
    try:
        data = json.loads(BACKFILL_STATE.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def _save_account_state(account_id: int, row: dict) -> None:
    data = _load_state()
    data[str(account_id)] = row
    BACKFILL_STATE.parent.mkdir(parents=True, exist_ok=True)
    BACKFILL_STATE.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


async def _run_backfill(account_id: int, client, force: bool, max_dialogs: int, max_per_dialog: int) -> None:
    started = datetime.now(timezone.utc).isoformat()
    stats = {
        "state": "running",
        "startedAt": started,
        "dialogs": 0,
        "messages": 0,
        "media": 0,
        "error": None,
    }
    _backfill_status[account_id] = stats
    download_media = force or not bool(
        _load_state().get(str(account_id), {}).get("lastOk")
    )
    log.info(
        "archive backfill start acc=%s dialogs=%s per=%s media=%s",
        account_id, max_dialogs, max_per_dialog, download_media,
    )
    try:
        seen = 0
        async for d in client.iter_dialogs(limit=max(max_dialogs * 4, 80)):
            entity = d.entity
            if not isinstance(entity, User) or getattr(entity, "deleted", False):
                continue
            seen += 1
            n_msg, n_media = await _backfill_dialog(
                account_id, client, entity, max_per_dialog, download_media
            )
            stats["dialogs"] = seen
            stats["messages"] = int(stats["messages"]) + n_msg
            stats["media"] = int(stats["media"]) + n_media
            if seen >= max_dialogs:
                break
            await asyncio.sleep(0.35)
        stats["state"] = "done"
        _save_account_state(account_id, {
            "lastOk": datetime.now(timezone.utc).isoformat(),
            "dialogs": stats["dialogs"],
            "messages": stats["messages"],
            "media": stats["media"],
        })
        log.info(
            "archive backfill done acc=%s dialogs=%s messages=%s media=%s",
            account_id, stats["dialogs"], stats["messages"], stats["media"],
        )
    except asyncio.CancelledError:
        stats["state"] = "cancelled"
        raise
    except Exception as ex:
        stats["state"] = "error"
        stats["error"] = str(ex)
        log.warning("archive backfill failed acc=%s: %s", account_id, ex)


async def _backfill_dialog(account_id: int, client, chat: User, limit: int, download_media: bool) -> tuple[int, int]:
    n_msg = 0
    n_media = 0
    try:
        async for msg in client.iter_messages(chat, limit=limit):
            if msg is None:
                continue
            has_media = bool(msg.media) and download_media
            await emit_message(account_id, msg, chat, "new", download_media=download_media)
            n_msg += 1
            if has_media:
                n_media += 1
                await asyncio.sleep(0.25)
            else:
                await asyncio.sleep(0.04)
    except FloodWaitError as ex:
        wait = min(int(getattr(ex, "seconds", 5) or 5), 90)
        log.warning("archive backfill FLOOD acc=%s peer=%s sleep=%ss", account_id, chat.id, wait)
        await asyncio.sleep(wait)
    except Exception as ex:
        log.warning("archive backfill dialog acc=%s peer=%s: %s", account_id, getattr(chat, "id", "?"), ex)
    return n_msg, n_media

