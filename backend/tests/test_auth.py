import pytest

from helpers import register, unique_username


def test_register_returns_token_and_user(client):
    username = unique_username()
    response = client.post(
        "/api/auth/register",
        json={"username": username.upper(), "display_name": "  Аня  ", "password": "secret1"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["token"]
    assert body["user"]["username"] == username  # логин приводится к нижнему регистру
    assert body["user"]["display_name"] == "Аня"


def test_register_duplicate_username_is_rejected(client):
    actor = register(client)
    response = client.post(
        "/api/auth/register",
        json={"username": actor.username.upper(), "display_name": "Другой", "password": "secret1"},
    )
    assert response.status_code == 409


@pytest.mark.parametrize(
    "payload",
    [
        {"username": "ab", "display_name": "Имя", "password": "secret1"},  # короткий логин
        {"username": "имя с пробелом", "display_name": "Имя", "password": "secret1"},
        {"username": "valid_name", "display_name": "Имя", "password": "123"},  # короткий пароль
        {"username": "valid_name", "display_name": "", "password": "secret1"},
    ],
)
def test_register_validates_input(client, payload):
    assert client.post("/api/auth/register", json=payload).status_code == 422


def test_login_is_case_insensitive_for_username(client):
    actor = register(client)
    response = client.post(
        "/api/auth/login", json={"username": actor.username.upper(), "password": "secret1"}
    )
    assert response.status_code == 200
    assert response.json()["user"]["id"] == actor.id


def test_login_with_wrong_password_fails(client):
    actor = register(client)
    response = client.post(
        "/api/auth/login", json={"username": actor.username, "password": "wrong-password"}
    )
    assert response.status_code == 401


def test_login_unknown_user_fails(client):
    response = client.post(
        "/api/auth/login", json={"username": unique_username(), "password": "secret1"}
    )
    assert response.status_code == 401


def test_me_returns_current_user(client):
    actor = register(client, "Боря")
    response = actor.get("/auth/me")
    assert response.status_code == 200
    assert response.json() == {"id": actor.id, "username": actor.username, "display_name": "Боря"}


@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer not-a-token"}])
def test_protected_routes_require_valid_token(client, headers):
    assert client.get("/api/auth/me", headers=headers).status_code == 401
    assert client.get("/api/laundries", headers=headers).status_code == 401
