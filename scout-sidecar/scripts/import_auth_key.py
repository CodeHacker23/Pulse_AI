"""Импорт готовой MTProto-сессии (auth key hex + dc) в Telethon .session.

Usage:
  copy accounts.secrets.example.json accounts.secrets.json  # заполнить локально
  python scripts/import_auth_key.py
  python scripts/import_auth_key.py 1   # только один account id

Файл accounts.secrets.json в .gitignore — не коммитить.
"""

import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from telethon import TelegramClient
from telethon.crypto import AuthKey
from telethon.sessions import SQLiteSession

import config

DC_IPS = {
    1: ("149.154.175.50", 443),
    2: ("149.154.167.41", 443),
    3: ("149.154.175.100", 443),
    4: ("149.154.167.92", 443),
    5: ("91.108.56.130", 443),
}

SECRETS_FILE = config.ROOT / "accounts.secrets.json"


def load_secrets() -> dict[int, dict]:
    if not SECRETS_FILE.exists():
        print(f"Create {SECRETS_FILE.name} from accounts.secrets.example.json")
        sys.exit(1)
    data = json.loads(SECRETS_FILE.read_text(encoding="utf-8"))
    result = {}
    for item in data.get("accounts", []):
        result[int(item["id"])] = item
    return result


def normalize_hex(raw: str) -> str:
    return raw.strip().replace(" ", "").replace("\n", "")


async def import_one(account_id: int, secret: dict) -> None:
    accounts = config.load_accounts()
    if account_id not in accounts:
        print(f"Skip id={account_id}: not in accounts.json")
        return

    acc = accounts[account_id]
    auth_hex = normalize_hex(secret.get("auth_key_hex", ""))
    dc_id = int(secret["dc_id"])
    if "..." in auth_hex or len(auth_hex) < 500:
        raise ValueError(
            f"{acc.label}: auth_key_hex must be full hex (256 bytes = 512 chars), not truncated"
        )
    if dc_id not in DC_IPS:
        raise ValueError(f"{acc.label}: unknown dc_id {dc_id}")

    path = config.session_path(acc)
    ip, port = DC_IPS[dc_id]

    session = SQLiteSession(str(path))
    session.set_dc(dc_id, ip, port)
    session.auth_key = AuthKey(bytes.fromhex(auth_hex))
    session.save()

    client = TelegramClient(session, config.API_ID, config.API_HASH, proxy=config.PROXY)
    await client.connect()
    if not await client.is_user_authorized():
        raise RuntimeError(f"{acc.label}: session not authorized — check auth_key_hex / dc_id")

    me = await client.get_me()
    expected_uid = secret.get("user_id")
    if expected_uid and int(expected_uid) != me.id:
        print(f"WARN {acc.label}: user_id mismatch expected={expected_uid} got={me.id}")

    session.save()
    await client.disconnect()
    print(f"OK id={account_id} {acc.label} -> {path}.session (@{me.username or me.id})")


async def main(selected: list[int] | None) -> None:
    secrets = load_secrets()
    accounts = config.load_accounts()
    ids = selected if selected else sorted(secrets.keys())
    for account_id in ids:
        if account_id not in secrets:
            print(f"Skip id={account_id}: no entry in accounts.secrets.json")
            continue
        if account_id not in accounts:
            continue
        await import_one(account_id, secrets[account_id])


if __name__ == "__main__":
    selected_ids = [int(x) for x in sys.argv[1:]] if len(sys.argv) > 1 else None
    asyncio.run(main(selected_ids))
