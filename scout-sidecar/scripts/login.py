"""Авторизация user-аккаунта для scout sidecar.

Usage:
  python scripts/login.py 1
  (id из accounts.json, создаёт sessions/<session>.session)
"""

import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from telethon import TelegramClient

import config


async def main(account_id: int) -> None:
    accounts = config.load_accounts()
    if account_id not in accounts:
        print(f"Unknown account id {account_id}. See accounts.json")
        sys.exit(1)
    acc = accounts[account_id]
    path = config.session_path(acc)
    print(f"Login: {acc.label} -> {path}")
    client = TelegramClient(
        str(path),
        config.API_ID,
        config.API_HASH,
        proxy=config.PROXY,
    )
    await client.start()
    me = await client.get_me()
    print(f"OK: @{me.username or me.id} ({me.first_name})")
    await client.disconnect()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python scripts/login.py <accountId>")
        sys.exit(1)
    asyncio.run(main(int(sys.argv[1])))
