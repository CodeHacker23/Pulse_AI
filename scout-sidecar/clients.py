import asyncio
import inspect
import pathlib
import re
from typing import Optional

from datetime import datetime, timezone

from telethon import TelegramClient
from telethon.errors import (
    FloodWaitError,
    PeerFloodError,
    UserBannedInChannelError,
    UsernameInvalidError,
    UsernameNotOccupiedError,
    ChatAdminRequiredError,
)
from telethon.tl.functions.account import UpdateProfileRequest, UpdateUsernameRequest
from telethon.tl.functions.channels import JoinChannelRequest
from telethon.tl.functions.messages import ImportChatInviteRequest
from telethon.tl.functions.photos import UploadProfilePhotoRequest
from telethon.tl.types import (
    ChannelParticipantsRecent,
    ChannelParticipantsSearch,
    User,
    UserStatusOffline,
    UserStatusOnline,
    UserStatusRecently,
)

import config
import proxy_pool
import archive as dm_archive

_clients: dict[int, TelegramClient] = {}
_lock = asyncio.Lock()

# Аккаунты, которым разрешена отправка ЛС
SENDER_TYPES = {"OUTREACH", "SENDER"}
# Только join / parse / scan / vacuum
PARSER_TYPES = {"OBSERVER", "PARSER"}


def account_type(account_id: int) -> str:
    accounts = config.load_accounts()
    if account_id not in accounts:
        raise ValueError(f"Unknown accountId: {account_id}")
    return (accounts[account_id].account_type or "OUTREACH").upper()


async def disconnect_account(account_id: int) -> None:
    dm_archive.detach(account_id)
    async with _lock:
        client = _clients.pop(account_id, None)
        if client:
            await _safe_disconnect(client)


async def get_client(account_id: int) -> TelegramClient:
    async with _lock:
        if account_id in _clients:
            client = _clients[account_id]
            if client.is_connected():
                return client
        accounts = config.load_accounts()
        if account_id not in accounts:
            raise ValueError(f"Unknown accountId: {account_id}")
        acc = accounts[account_id]
        path = config.session_path(acc)
        session_paths = [pathlib.Path(str(path)), pathlib.Path(str(path) + ".session")]
        had_session = any(p.exists() for p in session_paths)
        proxy = proxy_pool.proxy_for_account(account_id)
        client = TelegramClient(
            str(path),
            config.API_ID,
            config.API_HASH,
            proxy=proxy,
        )
        try:
            await client.connect()
            authorized = await client.is_user_authorized()
        except Exception:
            # иначе висит открытый коннект и залоченный .session
            await _safe_disconnect(client, drop_session=not had_session)
            if not had_session:
                _remove_files(session_paths)
            raise
        if not authorized:
            # Telethon создаёт пустой .session при коннекте — не выдаём его за живую сессию
            await _safe_disconnect(client, drop_session=not had_session)
            if not had_session:
                _remove_files(session_paths)
            raise RuntimeError(
                f"Session not authorized: {acc.label}. Run: python scripts/login.py {account_id}"
            )
        _clients[account_id] = client
        dm_archive.attach(account_id, client)
        return client


def _remove_files(paths: list["pathlib.Path"]) -> None:
    import time

    for p in paths:
        for _ in range(5):
            try:
                if not p.exists():
                    break
                p.unlink()
                break
            except PermissionError:
                time.sleep(0.3)
            except Exception:
                break


async def _safe_disconnect(client: TelegramClient, drop_session: bool = False) -> None:
    try:
        result = client.disconnect()
        # disconnect() отдаёт Task, а не корутину — без await он не успевает закрыться
        if inspect.isawaitable(result):
            await result
    except Exception:
        pass
    # SQLiteSession держит свой коннект к .session — без close() файл залочен и не удалится
    try:
        client.session.close()
    except Exception:
        pass
    if drop_session:
        try:
            _remove_files([pathlib.Path(client.session.filename)])
        except Exception:
            pass


def normalize_username(username: str) -> str:
    u = username.strip().lstrip("@")
    if not re.match(r"^[a-zA-Z][a-zA-Z0-9_]{4,31}$", u):
        raise ValueError(f"Invalid username: {username}")
    return u


async def resolve_link(client: TelegramClient, link: str):
    raw = link.strip()
    if raw.startswith("https://t.me/"):
        raw = raw[len("https://t.me/") :]
    if raw.startswith("@"):
        raw = raw[1:]
    if raw.startswith("+") or "joinchat" in link:
        invite = raw.replace("joinchat/", "").lstrip("+")
        try:
            updates = await client(ImportChatInviteRequest(invite))
            if updates.chats:
                return updates.chats[0]
        except Exception:
            pass
    entity = await client.get_entity(link if link.startswith("@") else link)
    if hasattr(entity, "username") and entity.username:
        try:
            await client(JoinChannelRequest(entity))
        except Exception:
            pass
    return entity


async def join_chat(account_id: int, link: str) -> tuple[bool, Optional[str], Optional[str]]:
    """PARSER/OBSERVER: вступить в группу/канал/инвайт."""
    try:
        if account_type(account_id) in SENDER_TYPES:
            # отправителям тоже можно, но основной путь — парсеры
            pass
        client = await get_client(account_id)
        entity = await resolve_link(client, link)
        title = getattr(entity, "title", None) or getattr(entity, "username", str(entity))
        return True, str(title), None
    except FloodWaitError as e:
        return False, None, f"FLOOD_WAIT {e.seconds}s"
    except Exception as e:
        return False, None, str(e)


async def send_dm(account_id: int, username: str, text: str) -> tuple[bool, Optional[int], Optional[str]]:
    try:
        if account_type(account_id) not in SENDER_TYPES:
            return False, None, "account is PARSER/OBSERVER — DM forbidden (use OUTREACH/SENDER)"
        client = await get_client(account_id)
        user = normalize_username(username)
        msg = await client.send_message(user, text)
        return True, msg.id, None
    except FloodWaitError as e:
        return False, None, f"FLOOD_WAIT {e.seconds}s"
    except PeerFloodError:
        return False, None, "PEER_FLOOD"
    except (UsernameInvalidError, UsernameNotOccupiedError):
        return False, None, f"username not found: {username}"
    except Exception as e:
        return False, None, str(e)


def _status_freshness(user) -> tuple[str, int]:
    """Возвращает (label, score 0..40) по last_seen / online."""
    st = getattr(user, "status", None)
    if isinstance(st, UserStatusOnline):
        return "online", 40
    if isinstance(st, UserStatusRecently):
        return "recently", 30
    if isinstance(st, UserStatusOffline) and getattr(st, "was_online", None):
        was = st.was_online
        if was.tzinfo is None:
            was = was.replace(tzinfo=timezone.utc)
        days = (datetime.now(timezone.utc) - was).days
        if days <= 3:
            return f"offline_{days}d", 25
        if days <= 14:
            return f"offline_{days}d", 15
        if days <= 60:
            return f"offline_{days}d", 5
        return f"dead_{days}d", 0
    return "unknown", 8


def score_audience_user(user) -> dict:
    """Оценка «живой ЦА» vs мёртвый/фейк. 0..100."""
    if getattr(user, "bot", False) or getattr(user, "deleted", False):
        return {
            "username": (user.username or "").lower(),
            "user_id": user.id,
            "score": 0,
            "tier": "skip",
            "reasons": ["bot_or_deleted"],
        }
    score = 0
    reasons: list[str] = []
    uname = (user.username or "").lower()
    if uname:
        score += 20
        reasons.append("has_username")
    else:
        reasons.append("no_username")
    if getattr(user, "photo", None):
        score += 15
        reasons.append("has_photo")
    else:
        reasons.append("no_photo")
    name = ((user.first_name or "") + " " + (user.last_name or "")).strip()
    if name and len(name) >= 2:
        score += 10
        reasons.append("has_name")
    freshness, fscore = _status_freshness(user)
    score += fscore
    reasons.append(freshness)
    if getattr(user, "premium", False):
        score += 10
        reasons.append("premium")
    if score >= 55:
        tier = "hot"
    elif score >= 35:
        tier = "warm"
    elif score >= 20:
        tier = "cold"
    else:
        tier = "dead"
    return {
        "username": uname,
        "user_id": user.id,
        "display_name": name[:80],
        "score": min(score, 100),
        "tier": tier,
        "reasons": reasons,
    }


async def _collect_participants(client: TelegramClient, entity, limit: int) -> list[User]:
    """Сбор участников: полный список → recent/search → авторы сообщений (без прав админа)."""
    users: list[User] = []
    seen: set[int] = set()

    def add(user) -> bool:
        if not isinstance(user, User) or user.id in seen:
            return False
        seen.add(user.id)
        users.append(user)
        return len(users) >= limit

    # 1) обычный iter (часто требует админку в каналах/закрытых чатах)
    try:
        async for user in client.iter_participants(entity, limit=min(limit, 500)):
            if add(user):
                return users
        if users:
            return users
    except ChatAdminRequiredError:
        pass
    except Exception:
        pass

    # 2) recent / search — иногда доступны обычному участнику
    for filt in (ChannelParticipantsRecent(), ChannelParticipantsSearch("")):
        try:
            async for user in client.iter_participants(
                entity, limit=min(limit, 500), filter=filt
            ):
                if add(user):
                    return users
            if users:
                return users
        except ChatAdminRequiredError:
            continue
        except Exception:
            continue

    # 3) fallback: авторы недавних сообщений (не нужен admin)
    msg_limit = min(max(limit * 4, 200), 1500)
    try:
        async for msg in client.iter_messages(entity, limit=msg_limit):
            try:
                sender = await msg.get_sender()
            except Exception:
                continue
            if add(sender):
                return users
    except Exception:
        pass

    return users


def is_burned_error(err: str) -> bool:
    """Ключ убит Telegram (AuthKeyDuplicated / unregistered) — сессия не восстановима."""
    e = (err or "").lower()
    return (
        "two different ip" in e
        or "authkeyduplicated" in e
        or "auth_key_duplicated" in e
        or "authkeyunregistered" in e
        or "auth key unregistered" in e
        or "session revoked" in e
        or "user_deactivated" in e
        or "userdeactivated" in e
    )


def humanize_tg_error(err: str) -> str:
    e = (err or "").lower()
    if "chat admin privileges" in e or "chatadminrequired" in e or "getparticipantsrequest" in e:
        return (
            "Нет прав читать участников (нужна админка или скрытый список). "
            "Пробуем запасной способ по сообщениям; если пусто — возьми публичную группу "
            "или парсер должен быть участником чата."
        )
    if "two different ip" in e or "authkeyduplicated" in e or "authorization key" in e and "ip" in e:
        return (
            "Сессия СГОРЕЛА: один auth_key/tdata открыли с двух IP сразу "
            "(Desktop + sidecar, или прокси ↔ без прокси). "
            "Старый secrets/auth_key уже мёртв — нужен НОВЫЙ tdata.zip с лолза "
            "или свежий логин. Дальше: 1 акк = 1 прокси = только sidecar."
        )
    if "flood" in e:
        return err
    if "banned" in e:
        return "Аккаунт забанен в этом чате"
    return err


async def parse_members(account_id: int, link: str, limit: int) -> tuple[bool, list[str], Optional[str]]:
    ok, scored, error = await parse_audience(account_id, link, limit, min_score=0)
    if not ok:
        return False, [], error
    usernames = [u["username"] for u in scored if u.get("username")]
    return True, usernames, None


async def parse_audience(
    account_id: int, link: str, limit: int, min_score: int = 35
) -> tuple[bool, list[dict], Optional[str]]:
    """Парс участников с скорингом ЦА (отсекает мёртвых/ботов)."""
    try:
        if account_type(account_id) in SENDER_TYPES:
            return False, [], "use PARSER/OBSERVER for group parse (save sender for DMs)"
        client = await get_client(account_id)
        entity = await resolve_link(client, link)
        raw_users = await _collect_participants(client, entity, min(limit, 500))
        if not raw_users:
            return False, [], (
                "Участников не получили: список скрыт / нет прав / чат пустой. "
                "Зайди парсером в группу и попробуй публичный суперчат."
            )
        scored: list[dict] = []
        for user in raw_users:
            row = score_audience_user(user)
            if row["score"] >= min_score and row["username"]:
                scored.append(row)
        scored.sort(key=lambda x: x["score"], reverse=True)
        return True, scored, None
    except FloodWaitError as e:
        return False, [], f"FLOOD_WAIT {e.seconds}s"
    except UserBannedInChannelError:
        return False, [], "BANNED_IN_CHANNEL"
    except ChatAdminRequiredError as e:
        return False, [], humanize_tg_error(str(e))
    except Exception as e:
        return False, [], humanize_tg_error(str(e))


async def scan_chat(account_id: int, link: str, keywords: list[str], message_limit: int = 80):
    try:
        client = await get_client(account_id)
        entity = await resolve_link(client, link)
        hits = []
        patterns = [k.lower() for k in keywords if k]
        async for msg in client.iter_messages(entity, limit=message_limit):
            text = (msg.message or "").strip()
            if not text:
                continue
            lower = text.lower()
            for kw in patterns:
                if kw in lower:
                    snippet = text[:200].replace("\n", " ")
                    hits.append({"snippet": snippet, "keyword": kw})
                    break
        return True, hits[:20], None
    except Exception as e:
        return False, [], str(e)


async def vacuum_posts(account_id: int, link: str, limit: int = 50):
    """Пылесос контента: последние посты чата/канала для трендов."""
    try:
        if account_type(account_id) in SENDER_TYPES:
            return False, [], "use PARSER/OBSERVER for content vacuum"
        client = await get_client(account_id)
        entity = await resolve_link(client, link)
        posts = []
        async for msg in client.iter_messages(entity, limit=min(limit, 100)):
            text = (msg.message or "").strip()
            if not text:
                continue
            posts.append({
                "id": msg.id,
                "date": msg.date.isoformat() if msg.date else None,
                "text": text[:500],
                "views": getattr(msg, "views", None),
            })
        return True, posts, None
    except Exception as e:
        return False, [], str(e)


async def spambot_start(account_id: int) -> tuple[bool, Optional[str], Optional[str]]:
    """Снять peer flood: /start @SpamBot (как делали вручную)."""
    try:
        client = await get_client(account_id)
        entity = await client.get_entity("SpamBot")
        await client.send_message(entity, "/start")
        # подождать ответ
        await asyncio.sleep(2)
        reply_text = ""
        async for msg in client.iter_messages(entity, limit=3):
            if msg.message:
                reply_text = msg.message[:400]
                break
        # Иногда SpamBot шлёт кнопки — жмём «Это ошибка» если есть
        # Упрощённо: повторный /start часто достаточно для «Good news...»
        if reply_text and ("Good news" in reply_text or "не огранич" in reply_text.lower()
                           or "no limits" in reply_text.lower() or "свободен" in reply_text.lower()):
            return True, reply_text, None
        return True, reply_text or "SpamBot contacted", None
    except FloodWaitError as e:
        return False, None, f"FLOOD_WAIT {e.seconds}s"
    except Exception as e:
        return False, None, str(e)


async def rotate_proxy_and_reconnect(account_id: int) -> tuple[bool, Optional[dict], Optional[str]]:
    try:
        await disconnect_account(account_id)
        entry = proxy_pool.rotate_proxy(account_id)
        # reconnect with new proxy
        await get_client(account_id)
        if entry is None:
            return True, None, "no valid proxies left"
        return True, entry.as_dict(), None
    except Exception as e:
        return False, None, str(e)


async def get_me(account_id: int) -> tuple[bool, Optional[dict], Optional[str]]:
    try:
        client = await get_client(account_id)
        me = await client.get_me()
        return True, {
            "id": me.id,
            "username": me.username or "",
            "first_name": me.first_name or "",
            "last_name": me.last_name or "",
            "phone": me.phone or "",
            "premium": bool(getattr(me, "premium", False)),
        }, None
    except Exception as e:
        return False, None, str(e)


async def update_profile(
    account_id: int,
    first_name: Optional[str] = None,
    last_name: Optional[str] = None,
    username: Optional[str] = None,
    about: Optional[str] = None,
) -> tuple[bool, Optional[dict], Optional[str]]:
    try:
        client = await get_client(account_id)
        me = await client.get_me()
        fn = first_name if first_name is not None else (me.first_name or "")
        ln = last_name if last_name is not None else (me.last_name or "")
        kwargs = {"first_name": fn, "last_name": ln}
        if about is not None:
            kwargs["about"] = about
        if first_name is not None or last_name is not None or about is not None:
            await client(UpdateProfileRequest(**kwargs))
        if username is not None:
            uname = username.lstrip("@").strip()
            await client(UpdateUsernameRequest(uname))
        return await get_me(account_id)
    except FloodWaitError as e:
        return False, None, f"FLOOD_WAIT {e.seconds}s"
    except Exception as e:
        return False, None, str(e)


async def update_photo(account_id: int, image_bytes: bytes) -> tuple[bool, Optional[dict], Optional[str]]:
    try:
        client = await get_client(account_id)
        uploaded = await client.upload_file(image_bytes)
        await client(UploadProfilePhotoRequest(file=uploaded))
        return await get_me(account_id)
    except FloodWaitError as e:
        return False, None, f"FLOOD_WAIT {e.seconds}s"
    except Exception as e:
        return False, None, str(e)


async def list_dialogs(account_id: int, limit: int = 40) -> tuple[bool, list[dict], Optional[str]]:
    try:
        from telethon.tl.types import Channel, Chat

        client = await get_client(account_id)
        rows: list[dict] = []
        async for d in client.iter_dialogs(limit=min(limit, 100)):
            entity = d.entity
            kind = "user"
            username = ""
            peer_id = getattr(entity, "id", None)
            if isinstance(entity, User):
                if getattr(entity, "bot", False):
                    kind = "bot"
                username = (entity.username or "").lower()
            elif isinstance(entity, Channel):
                kind = "channel" if getattr(entity, "broadcast", False) else "group"
                username = (getattr(entity, "username", None) or "").lower()
            elif isinstance(entity, Chat):
                kind = "group"
            else:
                continue
            if peer_id is None:
                continue
            unread = int(d.unread_count or 0)
            rows.append({
                "peer_id": str(peer_id),
                "username": username,
                "name": d.name or username or str(peer_id),
                "kind": kind,
                "unread": unread,
                "last_message": (d.message.message[:120] if d.message and d.message.message else ""),
                "date": d.date.isoformat() if d.date else None,
            })
        return True, rows, None
    except Exception as e:
        return False, [], str(e)


async def resolve_peer(account_id: int, query: str) -> tuple[bool, Optional[dict], Optional[str]]:
    """Resolve @username / t.me link / numeric id for open-or-join UX."""
    try:
        client = await get_client(account_id)
        q = (query or "").strip()
        if not q:
            return False, None, "empty query"
        if "t.me/" in q:
            q = q.split("t.me/", 1)[1].split("?")[0].strip("/")
            if q.startswith("+"):
                # invite hash — just return hint to join
                return True, {"kind": "invite", "link": query.strip(), "name": q}, None
        if q.isdigit():
            entity = await client.get_entity(int(q))
        else:
            entity = await client.get_entity(q.lstrip("@"))
        kind = "user"
        username = ""
        if isinstance(entity, User):
            kind = "bot" if getattr(entity, "bot", False) else "user"
            username = (entity.username or "").lower()
            name = " ".join(x for x in [entity.first_name, entity.last_name] if x) or username
        else:
            from telethon.tl.types import Channel, Chat
            if isinstance(entity, Channel):
                kind = "channel" if getattr(entity, "broadcast", False) else "group"
                username = (getattr(entity, "username", None) or "").lower()
            elif isinstance(entity, Chat):
                kind = "group"
            name = getattr(entity, "title", None) or username or str(entity.id)
        return True, {
            "peer_id": str(entity.id),
            "username": username,
            "name": name,
            "kind": kind,
        }, None
    except Exception as e:
        return False, None, str(e)


async def get_dialog_messages(
    account_id: int, peer: str, limit: int = 40
) -> tuple[bool, list[dict], Optional[str]]:
    """peer = @username или numeric user id."""
    try:
        client = await get_client(account_id)
        entity = await client.get_entity(int(peer) if peer.isdigit() else peer.lstrip("@"))
        me = await client.get_me()
        msgs: list[dict] = []
        async for m in client.iter_messages(entity, limit=min(limit, 80)):
            msgs.append({
                "id": m.id,
                "out": bool(m.out),
                "text": (m.message or "")[:2000],
                "date": m.date.isoformat() if m.date else None,
                "from_me": m.sender_id == me.id if m.sender_id else bool(m.out),
            })
        msgs.reverse()
        return True, msgs, None
    except Exception as e:
        return False, [], str(e)


async def mark_dialog_read(account_id: int, peer: str) -> tuple[bool, Optional[str], Optional[str]]:
    """Отметить прочитанным ТОЛЬКО по явной кнопке оператора."""
    try:
        client = await get_client(account_id)
        entity = await client.get_entity(int(peer) if peer.isdigit() else peer.lstrip("@"))
        await client.send_read_acknowledge(entity)
        return True, "marked_read", None
    except Exception as e:
        return False, None, str(e)


async def reply_dialog(account_id: int, peer: str, text: str) -> tuple[bool, Optional[int], Optional[str]]:
    try:
        client = await get_client(account_id)
        entity = await client.get_entity(int(peer) if peer.isdigit() else peer.lstrip("@"))
        msg = await client.send_message(entity, text)
        return True, msg.id, None
    except FloodWaitError as e:
        return False, None, f"FLOOD_WAIT {e.seconds}s"
    except PeerFloodError:
        return False, None, "PEER_FLOOD"
    except Exception as e:
        return False, None, str(e)
