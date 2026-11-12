"""Проверка пересечений записей с учётом вместимости стиралки."""
from dataclasses import dataclass
from datetime import datetime

from .models import Booking


SLOT_CAPACITY = 1  # одна стиралка — одна запись на одно и то же время


@dataclass
class Item:
    key: tuple  # ("b", booking_id) — запись в БД, ("r", index) — строка импорта
    person: tuple  # ("u", user_id) или ("g", имя гостя)
    start: datetime
    end: datetime
    label: str


def booking_item(b: Booking) -> Item:
    return Item(("b", b.id), b.person_key, b.start_at, b.end_at, b.person_name)


def find_conflicts(
    pool: list[Item], person: tuple, start: datetime, end: datetime, capacity: int
) -> list[Item]:
    """Записи из pool, которые мешают добавить интервал [start, end) для person.

    Мешают: любая пересекающаяся запись того же человека и все записи,
    из-за которых в какой-то момент интервала уже занято capacity мест.
    """
    overlapping = [p for p in pool if p.start < end and p.end > start]
    found: dict[tuple, Item] = {}
    for p in overlapping:
        if p.person == person:
            found[p.key] = p
    # загрузка меняется только в моменты начала записей — проверяем их
    for t in {max(p.start, start) for p in overlapping}:
        active = [p for p in overlapping if p.start <= t < p.end]
        if len(active) >= capacity:
            for p in active:
                found[p.key] = p
    return sorted(found.values(), key=lambda p: (p.start, p.end))
