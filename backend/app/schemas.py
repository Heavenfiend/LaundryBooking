from datetime import datetime

from pydantic import BaseModel, Field, field_validator


class RegisterIn(BaseModel):
    username: str = Field(min_length=3, max_length=32, pattern=r"^[A-Za-z0-9_.-]+$")
    display_name: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=6, max_length=128)


class LoginIn(BaseModel):
    username: str
    password: str


class UserOut(BaseModel):
    id: int
    username: str
    display_name: str


class AuthOut(BaseModel):
    token: str
    user: UserOut


class LaundryIn(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    description: str = Field(default="", max_length=1000)
    capacity: int = Field(default=30, ge=1, le=1000)

    # обрезаем пробелы до проверки длины, чтобы название из одних пробелов не проходило
    @field_validator("name", "description", mode="before")
    @classmethod
    def strip(cls, v):
        return v.strip() if isinstance(v, str) else v


class LaundryOut(BaseModel):
    code: str
    name: str
    description: str
    capacity: int
    member_count: int
    my_role: str | None
    my_status: str | None


class MemberOut(BaseModel):
    user_id: int
    username: str
    display_name: str
    role: str
    status: str


class RoleIn(BaseModel):
    role: str = Field(pattern=r"^(admin|member)$")


class BookingIn(BaseModel):
    start: datetime
    end: datetime
    note: str = Field(default="", max_length=200)


class BookingOut(BaseModel):
    id: int
    user_id: int | None
    name: str
    start: datetime
    end: datetime
    note: str
    mine: bool


class ScheduleOut(BaseModel):
    date_from: str
    date_to: str
    now: datetime
    bookings: list[BookingOut]


class ImportRow(BaseModel):
    booking_id: int | None = None
    date: str = ""
    start: str = ""
    end: str = ""
    member: str = ""
    note: str = ""
    skip: bool = False


class ImportConflict(BaseModel):
    kind: str  # "existing" — запись на сайте, "row" — другая строка файла
    booking_id: int | None = None
    index: int | None = None
    label: str
    start: datetime
    end: datetime


class ImportResult(BaseModel):
    status: str  # new | update | unchanged | conflict | error | skipped
    message: str = ""
    resolved_name: str = ""
    is_guest: bool = False
    conflicts: list[ImportConflict] = []


class ImportApplyIn(BaseModel):
    rows: list[ImportRow] = Field(max_length=5000)
    delete_booking_ids: list[int] = []
    dry_run: bool = True


class ImportOut(BaseModel):
    rows: list[ImportRow]
    results: list[ImportResult]
    applied: bool = False
    ignored: int = 0  # ячейки с датой вне отображаемого окна
    # записи сайта, для которых ячейка в таблице пуста (кандидаты на удаление)
    missing: list[ImportConflict] = []
    created: int = 0
    updated: int = 0
    deleted: int = 0
