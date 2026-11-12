"""Экспорт записей в .xlsx и импорт из .xlsx с ручным разбором конфликтов.

Таблица выглядит так же, как расписание на сайте:
    первая строка  — даты (столбец = один день),
    первый столбец — время начала часа ("09:00"; можно и "09:00–10:00"),
    ячейка         — кто записан: логин или имя участника; пусто — свободно.
Если имени нет среди участников, запись создаётся на «гостя» с этим именем.
Если на сайте запись есть, а ячейка в таблице пустая, импорт предлагает админу
удалить запись с сайта (галочкой); без галочки запись остаётся.
"""
import io
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from fastapi.responses import Response
from openpyxl import Workbook, load_workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter
from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from ..config import now_local, window, window_dt
from ..db import get_db
from ..deps import Ctx, admin_ctx, lock_laundry, member_ctx
from ..logic import SLOT_CAPACITY, Item, booking_item, find_conflicts
from ..models import STATUS_ACTIVE, Booking, Laundry, Membership, User
from ..schemas import ImportApplyIn, ImportConflict, ImportOut, ImportResult, ImportRow
from .bookings import window_bookings

router = APIRouter(prefix="/api/laundries/{code}", tags=["excel"])

XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
MAX_FILE_BYTES = 5 * 1024 * 1024
MAX_SHEET_ROWS = 500
MAX_SHEET_COLS = 400
MAX_ROWS = 5000
TIME_HEADER = "Время"


# ---------- разбор значений ----------

def parse_date(value) -> date | None:
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    text = str(value or "").strip()
    for fmt in ("%Y-%m-%d", "%d.%m.%Y", "%d/%m/%Y", "%d.%m.%y", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(text, fmt).date()
        except ValueError:
            pass
    return None


def parse_time(value) -> tuple[int, int] | None:
    """Возвращает (часы, минуты); допускается 24:00 как конец суток."""
    if isinstance(value, datetime):
        return value.hour, value.minute
    if isinstance(value, time):
        return value.hour, value.minute
    if isinstance(value, timedelta):
        minutes = round(value.total_seconds() / 60)
        h, m = divmod(minutes, 60)
    elif isinstance(value, bool):
        return None
    elif isinstance(value, (int, float)):
        if 0 <= value < 1:  # доля суток, как Excel хранит время
            h, m = divmod(round(value * 24 * 60), 60)
        elif float(value).is_integer():
            h, m = int(value), 0
        else:
            return None
    else:
        match = re.fullmatch(r"(\d{1,2})(?:[:.](\d{2}))?(?::\d{2})?", str(value or "").strip())
        if not match:
            return None
        h, m = int(match.group(1)), int(match.group(2) or 0)
    if (0 <= h <= 23 and 0 <= m <= 59) or (h == 24 and m == 0):
        return h, m
    return None


def cell_date(value) -> str:
    d = parse_date(value)
    return d.isoformat() if d else str(value or "").strip()


def cell_time(value) -> str:
    t = parse_time(value)
    return f"{t[0]:02d}:{t[1]:02d}" if t else str(value or "").strip()


def header_date(value, today: date) -> date | None:
    """Дата из заголовка столбца: настоящая дата Excel, "13.11.2026" или "пт 13.11"."""
    d = parse_date(value)
    if d is not None:
        return d
    match = re.search(r"(\d{1,2})[./](\d{1,2})(?:[./](\d{4}|\d{2}))?(?!\d)", str(value or ""))
    if not match:
        return None
    day, month, year = int(match.group(1)), int(match.group(2)), match.group(3)
    if year:
        years = [int(year) + (2000 if len(year) == 2 else 0)]
    else:  # год не указан — берём ближайшую к сегодняшнему дню дату
        years = [today.year - 1, today.year, today.year + 1]
    candidates = []
    for y in years:
        try:
            candidates.append(date(y, month, day))
        except ValueError:
            pass
    return min(candidates, key=lambda c: abs((c - today).days)) if candidates else None


def slot_times(value) -> tuple[str, str] | None:
    """Начало и конец слота из первого столбца: "09:00", "9", "09:00–10:30"."""
    if isinstance(value, (datetime, time, timedelta, int, float)) and not isinstance(value, bool):
        start, end = parse_time(value), None
    else:
        found = re.findall(r"\d{1,2}(?:[:.]\d{2})?", str(value or ""))
        start = parse_time(found[0]) if found else None
        end = parse_time(found[1]) if len(found) > 1 else None
    if start is None or start[0] == 24:
        return None
    if end is None:  # по умолчанию слот длится час
        end = (start[0] + 1, start[1]) if start[0] < 23 else (24, 0)
    return f"{start[0]:02d}:{start[1]:02d}", f"{end[0]:02d}:{end[1]:02d}"


def active_members(db: Session, laundry_id: int) -> list[User]:
    return list(
        db.scalars(
            select(User)
            .join(Membership, Membership.user_id == User.id)
            .where(Membership.laundry_id == laundry_id, Membership.status == STATUS_ACTIVE)
        ).all()
    )


# ---------- экспорт ----------

@router.get("/export")
def export_xlsx(ctx: Ctx = Depends(member_ctx), db: Session = Depends(get_db)):
    lo, hi = window()
    today = now_local().date()
    days = [lo + timedelta(days=i) for i in range((hi - lo).days + 1)]

    # В ячейку пишем имя, как на сайте; если имя не уникально — логин,
    # чтобы при обратном импорте участник определялся однозначно.
    members = active_members(db, ctx.laundry.id)
    name_counts = Counter(u.display_name.strip().lower() for u in members)
    usernames = {u.username for u in members}

    def label(b: Booking) -> str:
        if b.user is None:
            return b.guest_name or ""
        key = b.user.display_name.strip().lower()
        clashes = key in usernames and key != b.user.username
        return b.user.username if name_counts.get(key, 0) > 1 or clashes else b.user.display_name

    grid: dict[tuple[date, int], list[str]] = defaultdict(list)
    for b in window_bookings(db, ctx.laundry.id):
        t = b.start_at.replace(minute=0, second=0, microsecond=0)
        while t < b.end_at:
            grid[(t.date(), t.hour)].append(label(b))
            t += timedelta(hours=1)

    wb = Workbook()
    ws = wb.active
    ws.title = "Расписание"
    ws.append([TIME_HEADER, *days])
    for hour in range(24):
        ws.append([f"{hour:02d}:00", *[", ".join(grid[(d, hour)]) or None for d in days]])

    center = Alignment(horizontal="center", vertical="center")
    for cell in ws[1]:
        cell.font = Font(bold=True)
        cell.alignment = center
        if cell.column > 1:
            cell.number_format = "DD.MM.YYYY"
            if days[cell.column - 2] == today:
                cell.fill = PatternFill("solid", fgColor="C7C7F5")
    for row in ws.iter_rows(min_row=2, max_col=1):
        row[0].font = Font(bold=True)
        row[0].alignment = center
    ws.column_dimensions["A"].width = 9
    for index in range(2, len(days) + 2):
        ws.column_dimensions[get_column_letter(index)].width = 14
    ws.freeze_panes = "B2"

    buffer = io.BytesIO()
    wb.save(buffer)
    filename = f"laundry-{ctx.laundry.code}.xlsx"
    return Response(
        buffer.getvalue(),
        media_type=XLSX_MIME,
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


# ---------- импорт ----------

@dataclass
class Sheet:
    rows: list[ImportRow]
    ignored: int  # ячейки, пропущенные из-за даты вне отображаемого окна
    dates: set[date]  # дни (столбцы), которые есть в таблице
    slots: list[tuple[int, int]]  # интервалы времени (строки) таблицы, в минутах от полуночи


def minutes(text: str) -> int | None:
    t = parse_time(text)
    return t[0] * 60 + t[1] if t else None


def read_rows(content: bytes) -> Sheet:
    """Разворачивает сетку «дни × часы» в список записей."""
    try:
        wb = load_workbook(io.BytesIO(content), data_only=True, read_only=True)
    except Exception:
        raise HTTPException(400, "Не удалось прочитать файл — нужен формат .xlsx")
    ws = wb.worksheets[0]
    sheet = [
        list(values)
        for values in ws.iter_rows(values_only=True, max_row=MAX_SHEET_ROWS, max_col=MAX_SHEET_COLS)
    ]
    today = now_local().date()
    lo, hi = window()

    def filled(value) -> bool:
        return value is not None and str(value).strip() != ""

    # строка с датами — первая из верхних строк, где справа от первого столбца есть дата
    header_index = next(
        (
            i
            for i, values in enumerate(sheet[:10])
            if any(header_date(v, today) for v in values[1:] if filled(v))
        ),
        None,
    )
    if header_index is None:
        raise HTTPException(
            400,
            "Не найдена строка с датами. Нужна таблица как на сайте: в первой строке даты "
            "(столбец = день), в первом столбце время, в ячейках — кто записан",
        )
    columns: dict[int, date | str] = {}
    for j, value in enumerate(sheet[header_index]):
        if j >= 1 and filled(value):
            columns[j] = header_date(value, today) or str(value).strip()

    rows: list[ImportRow] = []
    ignored = 0
    slots: list[tuple[int, int]] = []
    for values in sheet[header_index + 1 :]:
        label = values[0] if values else None
        times = slot_times(label)
        if times:  # строка таблицы покрывает это время, даже если её ячейки пусты
            slots.append((minutes(times[0]) or 0, minutes(times[1]) or 24 * 60))
        cells = [(j, values[j]) for j in columns if j < len(values) and filled(values[j])]
        if not cells:
            continue
        start, end = times if times else (str(label or "").strip(), "")
        for j, value in cells:
            day = columns[j]
            if isinstance(day, date) and not (lo <= day <= hi):
                ignored += 1
                continue
            for name in re.split(r"[,;\n]+", str(value)):
                if not name.strip():
                    continue
                if len(rows) >= MAX_ROWS:
                    raise HTTPException(400, f"Слишком много записей (максимум {MAX_ROWS})")
                rows.append(
                    ImportRow(
                        date=day.isoformat() if isinstance(day, date) else day,
                        start=start,
                        end=end,
                        member=name.strip(),
                    )
                )
    rows.sort(key=lambda r: (r.date, r.start))
    dates = {d for d in columns.values() if isinstance(d, date) and lo <= d <= hi}
    return Sheet(rows=rows, ignored=ignored, dates=dates, slots=slots)


def find_missing(db: Session, laundry_id: int, sheet: Sheet) -> list[ImportConflict]:
    """Записи сайта, чьё время в таблице есть, но все ячейки на это время пустые."""
    occupied: dict[str, list[tuple[int, int]]] = defaultdict(list)
    for row in sheet.rows:
        start, end = minutes(row.start), minutes(row.end)
        if start is not None and end is not None:
            occupied[row.date].append((start, end or 24 * 60))
    slots = sorted(sheet.slots)

    missing = []
    for b in window_bookings(db, laundry_id):
        day = b.start_at.date()
        if day not in sheet.dates:
            continue
        start = b.start_at.hour * 60 + b.start_at.minute
        end = 24 * 60 if b.end_at.date() > day else b.end_at.hour * 60 + b.end_at.minute
        covered_to = start  # докуда строки таблицы непрерывно покрывают запись
        for slot_start, slot_end in slots:
            if slot_start > covered_to:
                break
            covered_to = max(covered_to, slot_end)
        if covered_to < end:
            continue  # такого времени в таблице нет — это не «пустая ячейка»
        if any(s < end and e > start for s, e in occupied[day.isoformat()]):
            continue  # ячейка заполнена: совпадение или конфликт, разбирается в строках
        missing.append(
            ImportConflict(
                kind="existing", booking_id=b.id, label=b.person_name, start=b.start_at, end=b.end_at
            )
        )
    return missing


@dataclass
class Parsed:
    start: datetime
    end: datetime
    user: User | None
    guest_name: str | None
    note: str
    target: Booking | None = None

    @property
    def person(self) -> tuple:
        if self.user is not None:
            return ("u", self.user.id)
        return ("g", (self.guest_name or "").lower())

    @property
    def name(self) -> str:
        return self.user.display_name if self.user else (self.guest_name or "")


@dataclass
class Plan:
    create: list[Parsed]
    update: list[Parsed]
    delete: list[Booking]


def evaluate(
    db: Session, laundry: Laundry, rows: list[ImportRow], delete_ids: list[int]
) -> tuple[list[ImportResult], Plan]:
    lo_date, hi_date = window()
    lo_dt, _ = window_dt()
    bookings = db.scalars(
        select(Booking)
        .where(Booking.laundry_id == laundry.id, Booking.end_at > lo_dt)
        .options(selectinload(Booking.user))
    ).all()
    by_id = {b.id: b for b in bookings}
    to_delete = {i for i in delete_ids if i in by_id}

    members = active_members(db, laundry.id)
    by_username = {u.username: u for u in members}
    by_display: dict[str, list[User]] = {}
    for u in members:
        by_display.setdefault(u.display_name.strip().lower(), []).append(u)

    def parse_row(row: ImportRow) -> Parsed | str:
        day = parse_date(row.date)
        if day is None:
            return "Не удалось распознать дату"
        start_t, end_t = parse_time(row.start), parse_time(row.end)
        if start_t is None or start_t[0] == 24:
            return "Не удалось распознать время начала"
        if end_t is None:
            return "Не удалось распознать время конца"
        start = datetime.combine(day, time(*start_t))
        if end_t in ((24, 0), (0, 0)):  # до полуночи
            end = datetime.combine(day + timedelta(days=1), time.min)
        else:
            end = datetime.combine(day, time(*end_t))
        if end <= start:
            return "Конец должен быть позже начала"
        if not (lo_date <= day <= hi_date):
            return f"Дата вне окна {lo_date:%d.%m.%Y}–{hi_date:%d.%m.%Y}"
        member = row.member.strip()
        if not member:
            return "Не указан участник"
        if len(member) > 100:
            return "Слишком длинное имя участника"
        user = by_username.get(member.lower())
        if user is None:
            same_name = by_display.get(member.lower(), [])
            user = same_name[0] if len(same_name) == 1 else None
        return Parsed(
            start=start,
            end=end,
            user=user,
            guest_name=None if user else member,
            note=row.note.strip()[:200],
        )

    results: list[ImportResult | None] = [None] * len(rows)
    parsed: list[Parsed | None] = [None] * len(rows)
    moved: set[int] = set()  # записи, чьё место определяется строкой файла

    for i, row in enumerate(rows):
        if row.skip:
            results[i] = ImportResult(status="skipped", message="Пропущена")
            continue
        p = parse_row(row)
        if isinstance(p, str):
            results[i] = ImportResult(status="error", message=p)
            continue
        if row.booking_id in by_id and row.booking_id not in to_delete:
            if row.booking_id in moved:
                results[i] = ImportResult(
                    status="error", message=f"ID {row.booking_id} встречается в файле дважды"
                )
                continue
            p.target = by_id[row.booking_id]
            moved.add(row.booking_id)
        parsed[i] = p

    pool: list[Item] = [
        booking_item(b) for b in bookings if b.id not in to_delete and b.id not in moved
    ]
    plan = Plan(create=[], update=[], delete=[by_id[i] for i in to_delete])

    for i, p in enumerate(parsed):
        if p is None:
            continue
        base = dict(resolved_name=p.name, is_guest=p.user is None)
        t = p.target
        if t is not None and (t.person_key, t.start_at, t.end_at) == (p.person, p.start, p.end):
            if t.note != p.note:
                plan.update.append(p)
                results[i] = ImportResult(status="update", message="Изменится комментарий", **base)
            else:
                results[i] = ImportResult(status="unchanged", message="Без изменений", **base)
            pool.append(Item(("b", t.id), p.person, p.start, p.end, p.name))
            continue
        # запись уже есть, если время целиком покрыто записью того же человека
        if t is None and any(
            x.key[0] == "b" and x.person == p.person and x.start <= p.start and x.end >= p.end
            for x in pool
        ):
            results[i] = ImportResult(status="unchanged", message="Такая запись уже есть", **base)
            continue

        conflicts = find_conflicts(pool, p.person, p.start, p.end, SLOT_CAPACITY)
        if conflicts:
            results[i] = ImportResult(
                status="conflict",
                message="Пересекается с другими записями",
                conflicts=[
                    ImportConflict(
                        kind="existing" if c.key[0] == "b" else "row",
                        booking_id=c.key[1] if c.key[0] == "b" else None,
                        index=c.key[1] if c.key[0] == "r" else None,
                        label=c.label,
                        start=c.start,
                        end=c.end,
                    )
                    for c in conflicts
                ],
                **base,
            )
            continue

        pool.append(Item(("r", i), p.person, p.start, p.end, p.name))
        if t is not None:
            plan.update.append(p)
            results[i] = ImportResult(status="update", message="Запись будет изменена", **base)
        else:
            plan.create.append(p)
            results[i] = ImportResult(status="new", message="Будет добавлена", **base)

    return [r for r in results if r is not None], plan


@router.post("/import/parse", response_model=ImportOut)
async def import_parse(
    file: UploadFile = File(...), ctx: Ctx = Depends(admin_ctx), db: Session = Depends(get_db)
):
    content = await file.read(MAX_FILE_BYTES + 1)
    if len(content) > MAX_FILE_BYTES:
        raise HTTPException(400, "Файл слишком большой (максимум 5 МБ)")
    sheet = read_rows(content)
    results, _ = evaluate(db, ctx.laundry, sheet.rows, [])
    return ImportOut(
        rows=sheet.rows,
        results=results,
        ignored=sheet.ignored,
        missing=find_missing(db, ctx.laundry.id, sheet),
    )


@router.post("/import/apply", response_model=ImportOut)
def import_apply(
    data: ImportApplyIn, ctx: Ctx = Depends(admin_ctx), db: Session = Depends(get_db)
):
    """dry_run=true — только перепроверка; иначе применяет, если не осталось конфликтов."""
    if not data.dry_run:
        lock_laundry(db, ctx.laundry)
    results, plan = evaluate(db, ctx.laundry, data.rows, data.delete_booking_ids)
    out = ImportOut(rows=data.rows, results=results)
    if data.dry_run or any(r.status in ("conflict", "error") for r in results):
        db.rollback()
        return out

    for booking in plan.delete:
        db.delete(booking)
    for p in plan.update:
        p.target.user_id = p.user.id if p.user else None
        p.target.guest_name = p.guest_name
        p.target.start_at = p.start
        p.target.end_at = p.end
        p.target.note = p.note
    for p in plan.create:
        db.add(
            Booking(
                laundry_id=ctx.laundry.id,
                user_id=p.user.id if p.user else None,
                guest_name=p.guest_name,
                start_at=p.start,
                end_at=p.end,
                note=p.note,
            )
        )
    db.commit()
    out.applied = True
    out.created, out.updated, out.deleted = len(plan.create), len(plan.update), len(plan.delete)
    return out
