"""`python -m fullskat_server` — run the server with uvicorn."""

from __future__ import annotations

import logging
import os

import uvicorn

from .config import load_settings


def main() -> None:
    settings = load_settings()
    logging.basicConfig(
        level=settings.log_level.upper(),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", "8080"))
    uvicorn.run(
        "fullskat_server.app:create_app",
        factory=True,
        host=host,
        port=port,
        log_level=settings.log_level.lower(),
        proxy_headers=settings.trust_proxy_headers,
        forwarded_allow_ips="*" if settings.trust_proxy_headers else None,
        ws_max_size=256 * 1024,
    )


if __name__ == "__main__":
    main()
