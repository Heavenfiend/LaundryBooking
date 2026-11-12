"""Общая подготовка: отдельная тестовая база и HTTP-клиент приложения.

Тесты никогда не трогают рабочую базу: имя базы принудительно получает суффикс
`_test`, и только после этого импортируется приложение.
"""
import os

from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url

_url = make_url(
    os.environ.get("DATABASE_URL", "postgresql+psycopg://laundry:laundry@db:5432/laundry")
)
if not _url.database.endswith("_test"):
    _url = _url.set(database=_url.database + "_test")
os.environ["DATABASE_URL"] = _url.render_as_string(hide_password=False)

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app.db import Base, engine  # noqa: E402
from app.main import app  # noqa: E402
from helpers import add_member, register  # noqa: E402


@pytest.fixture(scope="session", autouse=True)
def database():
    """Создаёт тестовую базу, если её нет, и чистую схему на каждый прогон."""
    admin = create_engine(_url.set(database="postgres"), isolation_level="AUTOCOMMIT")
    with admin.connect() as conn:
        exists = conn.scalar(
            text("SELECT 1 FROM pg_database WHERE datname = :name"), {"name": _url.database}
        )
        if not exists:
            conn.execute(text(f'CREATE DATABASE "{_url.database}"'))
    admin.dispose()
    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)
    yield
    engine.dispose()


@pytest.fixture(scope="session")
def client(database):
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def owner(client):
    """Создатель стиралки по имени «Аня»."""
    return register(client, "Аня")


@pytest.fixture
def code(owner):
    """Новая стиралка с лимитом 5 участников; возвращает её ID."""
    response = owner.post("/laundries", json={"name": "Общежитие 5", "description": "3 этаж", "capacity": 5})
    assert response.status_code == 200, response.text
    return response.json()["code"]


@pytest.fixture
def member(client, owner, code):
    """Обычный одобренный участник по имени «Боря»."""
    return add_member(client, owner, code, "Боря")
