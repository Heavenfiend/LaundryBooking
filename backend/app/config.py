import os
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

DATABASE_URL = os.getenv(
    "DATABASE_URL", "postgresql+psycopg://laundry:laundry@db:5432/laundry"
)
SECRET_KEY = os.getenv("SECRET_KEY", "dev-secret-change-me")
TOKEN_TTL_HOURS = 24 * 14

# Все времена записей хранятся как «местное» время стиралки (без часового пояса).
TZ = ZoneInfo(os.getenv("APP_TIMEZONE", "Europe/Moscow"))

DAYS_BACK = 7  # сколько дней до сегодняшнего показываем
DAYS_FORWARD = 21  # сколько дней после сегодняшнего показываем и даём бронировать


def now_local() -> datetime:
    return datetime.now(TZ).replace(tzinfo=None, microsecond=0)


def window() -> tuple[date, date]:
    """Первый и последний (включительно) день отображаемого окна."""
    today = now_local().date()
    return today - timedelta(days=DAYS_BACK), today + timedelta(days=DAYS_FORWARD)


def window_dt() -> tuple[datetime, datetime]:
    """Окно как полуинтервал [lo, hi) по времени."""
    lo, hi = window()
    return datetime.combine(lo, time.min), datetime.combine(hi + timedelta(days=1), time.min)
