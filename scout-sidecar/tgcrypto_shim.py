"""Shim tgcrypto → cryptg (Windows without MSVC). Import before opentele."""

from __future__ import annotations

import sys


def ensure_tgcrypto() -> None:
    try:
        import tgcrypto  # noqa: F401

        # real extension or existing shim
        if hasattr(tgcrypto, "ige256_decrypt"):
            return
    except Exception:
        pass

    import cryptg

    def _as_bytes(data) -> bytes:
        if isinstance(data, bytes):
            return data
        if isinstance(data, bytearray):
            return bytes(data)
        if hasattr(data, "data"):
            raw = data.data()
            return bytes(raw) if not isinstance(raw, bytes) else raw
        return bytes(data)

    mod = type(sys)("tgcrypto")
    mod.ige256_encrypt = lambda data, key, iv: cryptg.encrypt_ige(
        _as_bytes(data), _as_bytes(key), _as_bytes(iv)
    )
    mod.ige256_decrypt = lambda data, key, iv: cryptg.decrypt_ige(
        _as_bytes(data), _as_bytes(key), _as_bytes(iv)
    )
    sys.modules["tgcrypto"] = mod
