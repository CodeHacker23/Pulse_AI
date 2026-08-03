"""Import Desktop Telegram tdata → Telethon .session for a scout account.

Usage:
  python scripts/import_tdata.py <accountId> <path-to-tdata-or-zip>

Examples:
  python scripts/import_tdata.py 3 C:\\path\\to\\tdata
  python scripts/import_tdata.py 3 C:\\path\\to\\account.zip
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import account_registry  # noqa: E402
import config  # noqa: E402


async def main() -> None:
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    account_id = int(sys.argv[1])
    path = sys.argv[2]
    if account_id not in config.load_accounts():
        print(f"ERROR: account {account_id} not in accounts.json — create card in admin first")
        sys.exit(2)
    result = await account_registry.import_tdata_dir(account_id, path)
    if not result.get("ok"):
        print("FAIL:", result.get("error"))
        sys.exit(3)
    me = result.get("me") or {}
    print(
        f"OK id={account_id} @{me.get('username') or me.get('id')} "
        f"phone={me.get('phone') or '-'} -> {result.get('path')}"
    )


if __name__ == "__main__":
    asyncio.run(main())
