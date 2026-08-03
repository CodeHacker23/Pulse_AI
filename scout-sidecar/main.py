"""Pulse Scout Sidecar — MTProto (Telethon) REST API for Java bot."""

from fastapi import FastAPI, File, HTTPException, UploadFile
from pydantic import BaseModel, Field

import clients
import config
import proxy_pool
import account_registry


app = FastAPI(title="Pulse Scout Sidecar", version="1.3.0")


class DmRequest(BaseModel):
    accountId: int
    username: str
    text: str = Field(max_length=4096)


class GroupMembersRequest(BaseModel):
    accountId: int
    link: str
    limit: int = Field(default=500, ge=1, le=500)


class AudienceParseRequest(BaseModel):
    accountId: int
    link: str
    limit: int = Field(default=300, ge=1, le=500)
    minScore: int = Field(default=35, ge=0, le=100)


class ChatScanRequest(BaseModel):
    accountId: int
    link: str
    keywords: list[str] = Field(default_factory=list)


class JoinRequest(BaseModel):
    accountId: int
    link: str


class VacuumRequest(BaseModel):
    accountId: int
    link: str
    limit: int = Field(default=50, ge=1, le=100)


class AccountIdRequest(BaseModel):
    accountId: int


class ProxyImportRequest(BaseModel):
    text: str = Field(
        description="Одна строка = один прокси. Форматы: host:port:user:pass ИЛИ user:pass@host:port"
    )


class ProfileUpdateRequest(BaseModel):
    accountId: int
    firstName: str | None = None
    lastName: str | None = None
    username: str | None = None
    about: str | None = None


class DialogPeerRequest(BaseModel):
    accountId: int
    peer: str = Field(description="@username или numeric id")
    limit: int = Field(default=40, ge=1, le=80)


class DialogReplyRequest(BaseModel):
    accountId: int
    peer: str
    text: str = Field(max_length=4096)


class RegisterAccountRequest(BaseModel):
    model_config = {"extra": "ignore", "populate_by_name": True}

    id: int
    label: str = "scout"
    account_type: str = Field(default="SENDER", alias="type")
    session: str | None = None


class AuthKeyImportRequest(BaseModel):
    accountId: int
    authKeyHex: str
    dcId: int = Field(ge=1, le=5)


@app.get("/health")
async def health():
    accounts = []
    try:
        for acc_id, acc in config.load_accounts().items():
            accounts.append({"id": acc_id, "label": acc.label, "type": acc.account_type})
    except Exception as ex:
        return {"ok": False, "error": str(ex)}
    proxies = proxy_pool.load_proxies(include_invalid=True)
    return {
        "ok": True,
        "accounts": accounts,
        "proxies": {"valid": sum(1 for p in proxies if p.valid), "total": len(proxies)},
    }


@app.post("/v1/dm/send")
async def dm_send(body: DmRequest):
    ok, message_id, error = await clients.send_dm(body.accountId, body.username, body.text)
    if ok:
        return {"ok": True, "messageId": message_id}
    return {"ok": False, "error": error or "send failed"}


@app.post("/v1/group/members")
async def group_members(body: GroupMembersRequest):
    ok, usernames, error = await clients.parse_members(body.accountId, body.link, body.limit)
    if ok:
        return {"ok": True, "usernames": usernames}
    return {"ok": False, "error": error or "parse failed", "usernames": []}


@app.post("/v1/audience/parse")
async def audience_parse(body: AudienceParseRequest):
    ok, users, error = await clients.parse_audience(
        body.accountId, body.link, body.limit, body.minScore
    )
    if ok:
        return {
            "ok": True,
            "users": users,
            "count": len(users),
            "hint": "hot/warm = живая ЦА; cold/dead отсечены minScore",
        }
    return {"ok": False, "error": error or "parse failed", "users": []}


@app.post("/v1/chat/scan")
async def chat_scan(body: ChatScanRequest):
    keywords = [k for k in (body.keywords or []) if str(k).strip()]
    if not keywords:
        # empty = no matches, not a hard fail (radar may call with [])
        return {"ok": True, "hits": [], "hint": "keywords empty"}
    ok, hits, error = await clients.scan_chat(body.accountId, body.link, keywords)
    if ok:
        return {"ok": True, "hits": hits}
    return {"ok": False, "error": error or "scan failed", "hits": []}


@app.post("/v1/chat/join")
async def chat_join(body: JoinRequest):
    ok, title, error = await clients.join_chat(body.accountId, body.link)
    if ok:
        return {"ok": True, "title": title}
    return {"ok": False, "error": error or "join failed"}


@app.post("/v1/chat/vacuum")
async def chat_vacuum(body: VacuumRequest):
    ok, posts, error = await clients.vacuum_posts(body.accountId, body.link, body.limit)
    if ok:
        return {"ok": True, "posts": posts}
    return {"ok": False, "error": error or "vacuum failed", "posts": []}


@app.post("/v1/spambot/start")
async def spambot(body: AccountIdRequest):
    ok, reply, error = await clients.spambot_start(body.accountId)
    if ok:
        return {"ok": True, "reply": reply}
    return {"ok": False, "error": error or "spambot failed"}


@app.post("/v1/proxy/import")
async def proxy_import(body: ProxyImportRequest):
    return proxy_pool.import_lines(body.text)


@app.post("/v1/proxy/check")
async def proxy_check():
    return proxy_pool.check_all()


@app.get("/v1/proxy/list")
async def proxy_list():
    entries = proxy_pool.load_proxies(include_invalid=True)
    return {
        "ok": True,
        "proxies": [e.as_dict() for e in entries],
        "assignments": proxy_pool.load_assignments(),
    }


@app.post("/v1/proxy/purge-invalid")
async def proxy_purge():
    removed = proxy_pool.purge_invalid()
    return {"ok": True, "removed": removed}


@app.post("/v1/proxy/assign")
async def proxy_assign(body: AccountIdRequest):
    entry = proxy_pool.assign_proxy(body.accountId)
    if entry is None:
        return {"ok": False, "error": "no free valid proxies (1 proxy = 1 account)"}
    await clients.disconnect_account(body.accountId)
    return {"ok": True, "proxy": entry.as_dict()}


@app.post("/v1/proxy/rotate")
async def proxy_rotate(body: AccountIdRequest):
    ok, proxy, error = await clients.rotate_proxy_and_reconnect(body.accountId)
    if ok:
        return {"ok": True, "proxy": proxy}
    return {"ok": False, "error": error or "rotate failed"}


@app.get("/v1/account/{account_id}/me")
async def account_me(account_id: int):
    ok, me, error = await clients.get_me(account_id)
    if ok:
        return {"ok": True, "me": me}
    return {"ok": False, "error": error or "me failed"}


@app.post("/v1/accounts/register")
async def accounts_register(body: RegisterAccountRequest):
    try:
        label = (body.label or "").strip() or f"acc-{body.id}"
        acc_type = (body.account_type or "SENDER").strip().upper() or "SENDER"
        row = account_registry.upsert_account(body.id, label, acc_type, body.session)
        return {"ok": True, "account": row}
    except Exception as ex:
        return {"ok": False, "error": str(ex)}


@app.get("/v1/accounts/{account_id}/status")
async def accounts_status(account_id: int):
    try:
        st = account_registry.account_status(account_id)
        if not st.get("registered"):
            return st
        # Prefer already-connected Telethon client — avoids SQLite "database is locked"
        ok, me, error = await clients.get_me(account_id)
        if ok:
            st["authorized"] = True
            st["sessionFile"] = True
            st["me"] = me
            meta = account_registry.session_meta(account_id)
            account_registry.save_identity(account_id, me=me, dc_id=meta.get("dcId"))
            st["identity"] = account_registry.merge_identity(account_id)
            return st
        st["authorized"] = False
        if error:
            st["authError"] = clients.humanize_tg_error(error)
            st["burned"] = clients.is_burned_error(error)
            st["rawAuthError"] = error
        if st.get("sessionFile") and "locked" in (error or "").lower():
            st["authError"] = "session busy (уже в работе) — подожди секунду"
            st["burned"] = False
        return st
    except Exception as ex:
        return {"ok": False, "error": str(ex)}


@app.get("/v1/sessions/audit")
async def sessions_audit():
    """Дубли auth_key между .session — главная причина сгорания аккаунтов."""
    try:
        return account_registry.audit_sessions()
    except Exception as ex:
        return {"ok": False, "error": str(ex)}


@app.delete("/v1/sessions/orphan/{file_name}")
async def sessions_delete_orphan(file_name: str):
    try:
        return account_registry.delete_orphan_session(file_name)
    except Exception as ex:
        return {"ok": False, "error": str(ex)}


@app.delete("/v1/accounts/{account_id}")
async def accounts_delete(account_id: int, wipeSession: bool = True):
    """Убрать карточку из sidecar (+ по умолчанию удалить .session)."""
    try:
        return await account_registry.delete_account(account_id, wipe_session=wipeSession)
    except Exception as ex:
        return {"ok": False, "error": str(ex)}


@app.post("/v1/accounts/{account_id}/wipe-session")
async def accounts_wipe_session(account_id: int):
    """Снести только сессию (карточку и данные покупки оставить)."""
    try:
        return await account_registry.wipe_session(account_id)
    except Exception as ex:
        return {"ok": False, "error": str(ex)}


class ShopNoteRequest(BaseModel):
    shopNote: str = Field(default="", max_length=500)
    phone: str | None = None
    userId: int | None = None
    dcId: int | None = Field(default=None, ge=1, le=5)


@app.post("/v1/accounts/{account_id}/identity")
async def accounts_identity_save(account_id: int, body: ShopNoteRequest):
    """Save shop / Lolz fields manually (survives lost tdata)."""
    try:
        account_registry.save_identity(
            account_id,
            phone=body.phone,
            user_id=body.userId,
            dc_id=body.dcId,
            shop_note=body.shopNote,
        )
        return {"ok": True, "identity": account_registry.merge_identity(account_id)}
    except Exception as ex:
        return {"ok": False, "error": str(ex)}


@app.post("/v1/accounts/{account_id}/restore-secrets")
async def accounts_restore_secrets(account_id: int):
    try:
        return await account_registry.restore_from_secrets(account_id)
    except Exception as ex:
        return {"ok": False, "error": str(ex)}


@app.post("/v1/accounts/{account_id}/session")
async def accounts_session_upload(account_id: int, file: UploadFile = File(...)):
    data = await file.read()
    if not data:
        return {"ok": False, "error": "empty file"}
    name = file.filename or "account.session"
    if not name.lower().endswith(".session") and "session" not in name.lower():
        # still accept — sellers sometimes rename
        pass
    result = account_registry.save_session_bytes(account_id, data, name)
    if not result.get("ok"):
        return result
    await account_registry.invalidate_client(account_id)
    verified = await account_registry.verify_session(account_id)
    result["authorized"] = bool(verified.get("ok"))
    result["me"] = verified.get("me")
    result["verifyError"] = verified.get("error")
    return result


@app.post("/v1/accounts/{account_id}/tdata")
async def accounts_tdata_upload(account_id: int, file: UploadFile = File(...)):
    data = await file.read()
    if not data:
        return {"ok": False, "error": "empty file"}
    name = (file.filename or "").lower()
    if name.endswith(".session"):
        result = account_registry.save_session_bytes(account_id, data, file.filename or "account.session")
        await account_registry.invalidate_client(account_id)
        verified = await account_registry.verify_session(account_id)
        result["authorized"] = bool(verified.get("ok"))
        result["me"] = verified.get("me")
        return result
    return await account_registry.import_tdata_zip(account_id, data)


@app.post("/v1/accounts/auth-key")
async def accounts_auth_key(body: AuthKeyImportRequest):
    try:
        return await account_registry.import_auth_key(body.accountId, body.authKeyHex, body.dcId)
    except Exception as ex:
        return {"ok": False, "error": str(ex)}


@app.post("/v1/account/profile")
async def account_profile(body: ProfileUpdateRequest):
    ok, me, error = await clients.update_profile(
        body.accountId,
        first_name=body.firstName,
        last_name=body.lastName,
        username=body.username,
        about=body.about,
    )
    if ok:
        return {"ok": True, "me": me}
    return {"ok": False, "error": error or "profile update failed"}


@app.post("/v1/account/{account_id}/photo")
async def account_photo(account_id: int, file: UploadFile = File(...)):
    data = await file.read()
    if not data:
        return {"ok": False, "error": "empty file"}
    ok, me, error = await clients.update_photo(account_id, data)
    if ok:
        return {"ok": True, "me": me}
    return {"ok": False, "error": error or "photo update failed"}


@app.get("/v1/dialogs")
async def dialogs(accountId: int, limit: int = 40):
    ok, rows, error = await clients.list_dialogs(accountId, limit)
    if ok:
        return {"ok": True, "dialogs": rows}
    return {"ok": False, "error": error or "dialogs failed", "dialogs": []}


class ResolveRequest(BaseModel):
    accountId: int
    query: str = Field(min_length=1, max_length=200)


@app.post("/v1/dialogs/resolve")
async def dialogs_resolve(body: ResolveRequest):
    ok, peer, error = await clients.resolve_peer(body.accountId, body.query)
    if ok:
        return {"ok": True, "peer": peer}
    return {"ok": False, "error": error or "resolve failed"}


@app.post("/v1/dialogs/messages")
async def dialog_messages(body: DialogPeerRequest):
    ok, msgs, error = await clients.get_dialog_messages(body.accountId, body.peer, body.limit)
    if ok:
        return {"ok": True, "messages": msgs}
    return {"ok": False, "error": error or "messages failed", "messages": []}


@app.post("/v1/dialogs/read")
async def dialog_read(body: DialogPeerRequest):
    ok, detail, error = await clients.mark_dialog_read(body.accountId, body.peer)
    if ok:
        return {"ok": True, "detail": detail}
    return {"ok": False, "error": error or "read failed"}


@app.post("/v1/dialogs/reply")
async def dialog_reply(body: DialogReplyRequest):
    ok, mid, error = await clients.reply_dialog(body.accountId, body.peer, body.text)
    if ok:
        return {"ok": True, "messageId": mid}
    return {"ok": False, "error": error or "reply failed"}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=config.PORT, reload=False)
