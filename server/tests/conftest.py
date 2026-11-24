from pathlib import Path
from typing import AsyncIterator

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from fullskat_server.app import create_app
from fullskat_server.config import Settings
from helpers import FakeClock, FakeLLM


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(_env_file=None, data_dir=tmp_path, anthropic_api_key="test-key", openrouter_api_key="test-key")


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


@pytest.fixture
def app(settings: Settings, clock: FakeClock) -> FastAPI:
    application = create_app(settings=settings, clock=clock)
    application.state.llm = FakeLLM()
    application.state.timing.coalesce = 0.01
    application.state.timing.save_interval = 0.05
    return application


@pytest.fixture
async def client(app: FastAPI) -> AsyncIterator[AsyncClient]:
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        yield ac
