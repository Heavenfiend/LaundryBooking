"""Вспомогательные функции тестов: пользователи, слоты, Excel-таблицы."""
import io
import uuid
from datetime import date, datetime, time, timedelta

from openpyxl import Workbook, load_workbook

from app.config import now_local

XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"


class Actor:
    """Вошедший пользователь: делает запросы к API от своего имени."""

    def __init__(self, client, token: str, user: dict):
        self.client = client
        self.headers = {"Authorization": f"Bearer {token}"}
        self.id = user["id"]
        self.username = user["username"]
        self.name = user["display_name"]

    def request(self, method: str, path: str, **kwargs):
        return self.client.request(method, "/api" + path, headers=self.headers, **kwargs)

    def get(self, path, **kwargs):
        return self.request("GET", path, **kwargs)

    def post(self, path, **kwargs):
        return self.request("POST", path, **kwargs)

    def put(self, path, **kwargs):
        return self.request("PUT", path, **kwargs)

    def patch(self, path, **kwargs):
        return self.request("PATCH", path, **kwargs)

    def delete(self, path, **kwargs):
        return self.request("DELETE", path, **kwargs)


def unique_username() -> str:
    return "u" + uuid.uuid4().hex[:10]


def register(client, display_name: str = "Тест") -> Actor:
    response = client.post(
        "/api/auth/register",
        json={"username": unique_username(), "display_name": display_name, "password": "secret1"},
    )
    assert response.status_code == 200, response.text
    data = response.json()
    return Actor(client, data["token"], data["user"])


def add_member(client, admin: Actor, code: str, display_name: str = "Участник") -> Actor:
    """Регистрирует человека, подаёт заявку и одобряет её от имени admin."""
    actor = register(client, display_name)
    assert actor.post(f"/laundries/{code}/join").status_code == 200
    response = admin.post(f"/laundries/{code}/members/{actor.id}/approve")
    assert response.status_code == 200, response.text
    return actor


# ---------- время ----------

def day(offset: int) -> date:
    """Дата через offset дней от «сегодня» по часам сервера."""
    return now_local().date() + timedelta(days=offset)


def slot(on: date, hour: int, hours: int = 1) -> dict:
    """Тело запроса на запись: hours часов начиная с hour:00."""
    start = datetime.combine(on, time(hour))
    return {"start": start.isoformat(), "end": (start + timedelta(hours=hours)).isoformat()}


def book(actor: Actor, code: str, on: date, hour: int, hours: int = 1) -> dict:
    response = actor.post(f"/laundries/{code}/bookings", json=slot(on, hour, hours))
    assert response.status_code == 200, response.text
    return response.json()


def bookings(actor: Actor, code: str) -> list[dict]:
    response = actor.get(f"/laundries/{code}/bookings")
    assert response.status_code == 200, response.text
    return response.json()["bookings"]


# ---------- Excel ----------

def grid(header: list, *rows: list) -> Workbook:
    """Таблица как на сайте: header — первая строка, rows — строки «время, ячейки…»."""
    wb = Workbook()
    ws = wb.active
    ws.append(header)
    for row in rows:
        ws.append(row)
    return wb


def export(actor: Actor, code: str) -> Workbook:
    response = actor.get(f"/laundries/{code}/export")
    assert response.status_code == 200, response.text
    return load_workbook(io.BytesIO(response.content))


def column_of(ws, on: date) -> int:
    """Номер столбца (с 1) с датой on в экспортированной таблице."""
    for cell in ws[1][1:]:
        value = cell.value.date() if isinstance(cell.value, datetime) else cell.value
        if value == on:
            return cell.column
    raise AssertionError(f"в таблице нет столбца {on}")


def row_of(hour: int) -> int:
    """Номер строки (с 1) для часа hour в экспортированной таблице."""
    return hour + 2


def upload(actor: Actor, code: str, source):
    """Загружает таблицу на предпросмотр импорта; source — Workbook или байты файла."""
    if isinstance(source, Workbook):
        buffer = io.BytesIO()
        source.save(buffer)
        source = buffer.getvalue()
    return actor.post(
        f"/laundries/{code}/import/parse", files={"file": ("table.xlsx", source, XLSX_MIME)}
    )


def apply_import(actor: Actor, code: str, rows: list, delete: list = (), dry_run: bool = False):
    return actor.post(
        f"/laundries/{code}/import/apply",
        json={"rows": rows, "delete_booking_ids": list(delete), "dry_run": dry_run},
    )


def statuses(body: dict) -> list[str]:
    return [result["status"] for result in body["results"]]
