"""Модульные тесты чистых функций: пересечения интервалов и разбор значений таблицы."""
from datetime import date, datetime, time, timedelta

import pytest

from app.logic import Item, find_conflicts
from app.routers.excel import header_date, parse_date, parse_time, slot_times

DAY = datetime(2026, 11, 13)
ANYA, BORYA = ("u", 1), ("u", 2)


def item(key, person, start_hour, end_hour) -> Item:
    return Item(key, person, DAY.replace(hour=start_hour), DAY.replace(hour=end_hour), str(person))


def conflict_keys(pool, person, start_hour, end_hour, capacity=1):
    found = find_conflicts(pool, person, DAY.replace(hour=start_hour), DAY.replace(hour=end_hour), capacity)
    return [c.key for c in found]


class TestFindConflicts:
    pool = [item("a", ANYA, 9, 10), item("b", BORYA, 12, 14)]

    def test_free_time_has_no_conflicts(self):
        assert conflict_keys(self.pool, BORYA, 10, 12) == []

    def test_touching_intervals_do_not_overlap(self):
        assert conflict_keys(self.pool, BORYA, 10, 12) == []
        assert conflict_keys(self.pool, BORYA, 8, 9) == []

    def test_taken_time_conflicts(self):
        assert conflict_keys(self.pool, BORYA, 9, 10) == ["a"]

    def test_partial_overlap_conflicts(self):
        assert conflict_keys(self.pool, ANYA, 13, 15) == ["b"]

    def test_interval_covering_several_bookings_reports_all(self):
        assert conflict_keys(self.pool, ("g", "гость"), 8, 15) == ["a", "b"]

    def test_capacity_allows_parallel_bookings_of_different_people(self):
        assert conflict_keys(self.pool, BORYA, 9, 10, capacity=2) == []

    def test_same_person_cannot_overlap_even_with_spare_capacity(self):
        assert conflict_keys(self.pool, ANYA, 9, 10, capacity=2) == ["a"]

    def test_capacity_is_counted_per_moment_not_per_interval(self):
        # две записи подряд не занимают два места одновременно
        pool = [item("a", ANYA, 9, 10), item("b", BORYA, 10, 11)]
        assert conflict_keys(pool, ("u", 3), 9, 11, capacity=2) == []
        assert conflict_keys(pool + [item("c", ("u", 4), 9, 11)], ("u", 3), 9, 11, capacity=2) == ["a", "c", "b"]


@pytest.mark.parametrize(
    "value, expected",
    [
        ("09:00", (9, 0)),
        ("9:30", (9, 30)),
        ("9.30", (9, 30)),
        ("09:00:00", (9, 0)),
        ("7", (7, 0)),
        ("24:00", (24, 0)),
        (time(14, 45), (14, 45)),
        (datetime(2026, 1, 1, 8, 15), (8, 15)),
        (timedelta(hours=10, minutes=30), (10, 30)),
        (0.5, (12, 0)),  # доля суток — так Excel хранит время
        (15, (15, 0)),
        ("25:00", None),
        ("24:30", None),
        ("12:60", None),
        ("утром", None),
        ("", None),
        (None, None),
        (True, None),
    ],
)
def test_parse_time(value, expected):
    assert parse_time(value) == expected


@pytest.mark.parametrize(
    "value, expected",
    [
        (date(2026, 11, 13), date(2026, 11, 13)),
        (datetime(2026, 11, 13, 9, 30), date(2026, 11, 13)),
        ("2026-11-13", date(2026, 11, 13)),
        ("13.11.2026", date(2026, 11, 13)),
        ("13/11/2026", date(2026, 11, 13)),
        ("13.11.26", date(2026, 11, 13)),
        ("31.02.2026", None),
        ("завтра", None),
        (None, None),
    ],
)
def test_parse_date(value, expected):
    assert parse_date(value) == expected


@pytest.mark.parametrize(
    "value, today, expected",
    [
        ("пт 13.11", date(2026, 11, 12), date(2026, 11, 13)),
        ("13.11.2026", date(2026, 11, 12), date(2026, 11, 13)),
        ("чт, 13/11/26", date(2026, 11, 12), date(2026, 11, 13)),
        # год не указан — берётся ближайшая дата, в том числе через границу года
        ("02.01", date(2026, 12, 30), date(2027, 1, 2)),
        ("30.12", date(2027, 1, 2), date(2026, 12, 30)),
        (datetime(2026, 11, 13), date(2026, 11, 12), date(2026, 11, 13)),
        ("Время", date(2026, 11, 12), None),
        ("31.02", date(2026, 11, 12), None),
        (None, date(2026, 11, 12), None),
    ],
)
def test_header_date(value, today, expected):
    assert header_date(value, today) == expected


@pytest.mark.parametrize(
    "value, expected",
    [
        ("09:00", ("09:00", "10:00")),
        ("9", ("09:00", "10:00")),
        ("09:00–10:30", ("09:00", "10:30")),
        ("9-11", ("09:00", "11:00")),
        ("23:00", ("23:00", "24:00")),  # последний час заканчивается в полночь
        (time(7, 30), ("07:30", "08:30")),
        ("утром", None),
        (None, None),
    ],
)
def test_slot_times(value, expected):
    assert slot_times(value) == expected
