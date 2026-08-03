import json
import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

ROOT = Path(__file__).resolve().parent


@dataclass(frozen=True)
class AccountConfig:
    id: int
    label: str
    session: str
    account_type: str


def _require(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing env: {name}")
    return value


API_ID = int(_require("TELEGRAM_API_ID"))
API_HASH = _require("TELEGRAM_API_HASH")
PORT = int(os.getenv("SCOUT_SIDECAR_PORT", "8090"))
SESSIONS_DIR = ROOT / os.getenv("SCOUT_SESSIONS_DIR", "sessions")
ACCOUNTS_FILE = ROOT / os.getenv("SCOUT_ACCOUNTS_FILE", "accounts.json")

PROXY = None
if os.getenv("PROXY_HOST"):
    PROXY = (
        os.getenv("PROXY_TYPE", "socks5"),
        os.getenv("PROXY_HOST"),
        int(os.getenv("PROXY_PORT", "1080")),
    )


def load_accounts() -> dict[int, AccountConfig]:
    if not ACCOUNTS_FILE.exists():
        return {}
    data = json.loads(ACCOUNTS_FILE.read_text(encoding="utf-8-sig"))
    result: dict[int, AccountConfig] = {}
    for item in data.get("accounts", []):
        acc = AccountConfig(
            id=int(item["id"]),
            label=item.get("label", str(item["id"])),
            session=item.get("session", str(item["id"])),
            account_type=item.get("type", "OUTREACH"),
        )
        result[acc.id] = acc
    return result


def session_path(account: AccountConfig) -> Path:
    SESSIONS_DIR.mkdir(parents=True, exist_ok=True)
    return SESSIONS_DIR / account.session
