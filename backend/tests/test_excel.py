"""Экспорт и импорт .xlsx в виде сетки «дни × часы»."""
from datetime import datetime

from helpers import (
    add_member,
    apply_import,
    book,
    bookings,
    column_of,
    day,
    export,
    grid,
    row_of,
    statuses,
    upload,
)

# ---------- экспорт ----------


def test_export_is_a_grid_like_the_site(owner, code, member):
    book(owner, code, day(1), 9)
    book(member, code, day(2), 23)
    ws = export(member, code).active  # экспорт доступен обычному участнику

    assert ws.max_row == 25  # заголовок + 24 часа
    assert ws.max_column == 30  # время + 29 дней
    assert ws.cell(1, 1).value == "Время"
    assert ws.cell(1, 2).value.date() == day(-7)
    assert ws.cell(1, 30).value.date() == day(21)
    assert [ws.cell(r, 1).value for r in (2, 11, 25)] == ["00:00", "09:00", "23:00"]

    assert ws.cell(row_of(9), column_of(ws, day(1))).value == "Аня"
    assert ws.cell(row_of(23), column_of(ws, day(2))).value == "Боря"
    filled = [c.value for row in ws.iter_rows(min_row=2, min_col=2) for c in row if c.value]
    assert sorted(filled) == ["Аня", "Боря"]


def test_export_fills_every_hour_of_a_long_booking(owner, code):
    book(owner, code, day(1), 9, hours=3)
    ws = export(owner, code).active
    column = column_of(ws, day(1))
    assert [ws.cell(row_of(h), column).value for h in (8, 9, 10, 11, 12)] == [None, "Аня", "Аня", "Аня", None]


def test_export_uses_login_when_display_names_clash(client, owner, code):
    first = add_member(client, owner, code, "Иван")
    second = add_member(client, owner, code, "Иван")
    book(first, code, day(1), 9)
    book(second, code, day(1), 10)
    ws = export(owner, code).active
    column = column_of(ws, day(1))
    assert ws.cell(row_of(9), column).value == first.username
    assert ws.cell(row_of(10), column).value == second.username

    body = upload(owner, code, export(owner, code)).json()
    assert statuses(body) == ["unchanged", "unchanged"]  # логины однозначно находят участников


# ---------- импорт: разбор ----------


def test_reimport_of_untouched_export_changes_nothing(owner, code, member):
    book(owner, code, day(1), 9, hours=2)
    book(member, code, day(2), 23)
    response = upload(owner, code, export(owner, code))
    assert response.status_code == 200
    body = response.json()

    assert statuses(body) == ["unchanged"] * 3  # три заполненные ячейки
    assert body["missing"] == []
    assert body["ignored"] == 0
    assert {"date": str(day(2)), "start": "23:00", "end": "24:00"}.items() <= body["rows"][-1].items()

    result = apply_import(owner, code, body["rows"]).json()
    assert (result["applied"], result["created"], result["updated"], result["deleted"]) == (True, 0, 0, 0)
    assert len(bookings(owner, code)) == 2


def test_new_cells_become_bookings_for_members_and_guests(owner, code, member):
    table = grid(
        ["Время", day(1)],
        ["09:00", member.username],  # участник по логину
        ["10:00", "аня"],  # участник по имени, регистр не важен
        ["11:00", "Гость Петя"],  # такого участника нет
    )
    body = upload(owner, code, table).json()
    assert statuses(body) == ["new", "new", "new"]
    assert [(r["resolved_name"], r["is_guest"]) for r in body["results"]] == [
        ("Боря", False),
        ("Аня", False),
        ("Гость Петя", True),
    ]

    result = apply_import(owner, code, body["rows"]).json()
    assert (result["applied"], result["created"]) == (True, 3)
    assert [(b["name"], b["start"][11:16], b["user_id"]) for b in bookings(owner, code)] == [
        ("Боря", "09:00", member.id),
        ("Аня", "10:00", owner.id),
        ("Гость Петя", "11:00", None),
    ]


def test_several_names_in_one_cell_and_ranges_in_time_column(owner, code, member):
    table = grid(["", day(1)], ["10:00–11:30", "Аня"], ["12", "Боря"])
    body = upload(owner, code, table).json()
    assert [(r["start"], r["end"], r["member"]) for r in body["rows"]] == [
        ("10:00", "11:30", "Аня"),
        ("12:00", "13:00", "Боря"),
    ]
    assert statuses(body) == ["new", "new"]


def test_header_dates_as_text_and_columns_outside_window(owner, code):
    table = grid(
        ["Время", day(2).strftime("%d.%m"), "пт " + day(60).strftime("%d.%m.%Y"), "не дата"],
        ["10:00", "Аня", "кто-то", None],
        ["11:00", None, None, "кто-то"],
    )
    body = upload(owner, code, table).json()
    assert body["ignored"] == 1  # столбец через 60 дней — вне окна
    assert [(r["date"], r["start"]) for r in body["rows"]] == [
        (str(day(2)), "10:00"),  # дата без года — берётся ближайшая
        ("не дата", "11:00"),
    ]
    assert statuses(body) == ["new", "error"]


def test_date_header_may_be_below_the_first_row(owner, code):
    table = grid(["Расписание стиралки"], [None, "пт"], ["Время", day(1)], ["09:00", "Аня"])
    body = upload(owner, code, table).json()
    assert [(r["date"], r["start"]) for r in body["rows"]] == [(str(day(1)), "09:00")]


def test_bad_time_label_is_reported_as_error(owner, code):
    body = upload(owner, code, grid(["Время", day(1)], ["утром", "Аня"])).json()
    assert statuses(body) == ["error"]
    assert body["rows"][0]["start"] == "утром"

    # админ исправляет время прямо в строке и перепроверяет
    body["rows"][0].update(start="09:00", end="10:00")
    assert statuses(apply_import(owner, code, body["rows"], dry_run=True).json()) == ["new"]


def test_invalid_files_are_rejected(owner, code):
    assert upload(owner, code, b"this is not an xlsx file").status_code == 400
    assert upload(owner, code, grid(["a", "b"], ["c", "d"])).status_code == 400  # нет строки с датами


def test_only_admins_can_import(owner, code, member):
    table = grid(["Время", day(1)], ["09:00", "Аня"])
    assert upload(member, code, table).status_code == 403
    assert apply_import(member, code, []).status_code == 403


# ---------- импорт: конфликты ----------


def test_conflict_blocks_import_until_resolved(owner, code, member):
    existing = book(owner, code, day(1), 9)
    table = grid(["Время", day(1)], ["09:00", "Боря"], ["12:00", "Боря"])
    body = upload(owner, code, table).json()

    assert statuses(body) == ["conflict", "new"]
    conflict = body["results"][0]["conflicts"][0]
    assert (conflict["kind"], conflict["booking_id"], conflict["label"]) == ("existing", existing["id"], "Аня")
    assert body["missing"] == []  # занятая ячейка — конфликт, а не «пусто»

    # пока конфликт не решён, не применяется вообще ничего
    result = apply_import(owner, code, body["rows"]).json()
    assert result["applied"] is False
    assert [b["name"] for b in bookings(owner, code)] == ["Аня"]


def test_conflict_resolved_by_skipping_the_row(owner, code, member):
    book(owner, code, day(1), 9)
    body = upload(owner, code, grid(["Время", day(1)], ["09:00", "Боря"], ["12:00", "Боря"])).json()
    body["rows"][0]["skip"] = True

    result = apply_import(owner, code, body["rows"]).json()
    assert statuses(result) == ["skipped", "new"]
    assert (result["applied"], result["created"], result["deleted"]) == (True, 1, 0)
    assert [(b["name"], b["start"][11:16]) for b in bookings(owner, code)] == [
        ("Аня", "09:00"),
        ("Боря", "12:00"),
    ]


def test_conflict_resolved_by_replacing_site_booking(owner, code, member):
    existing = book(owner, code, day(1), 9)
    body = upload(owner, code, grid(["Время", day(1)], ["09:00", "Боря"])).json()

    preview = apply_import(owner, code, body["rows"], delete=[existing["id"]], dry_run=True).json()
    assert statuses(preview) == ["new"]
    assert preview["applied"] is False  # dry_run только перепроверяет
    assert [b["name"] for b in bookings(owner, code)] == ["Аня"]

    result = apply_import(owner, code, body["rows"], delete=[existing["id"]]).json()
    assert (result["applied"], result["created"], result["deleted"]) == (True, 1, 1)
    assert [b["name"] for b in bookings(owner, code)] == ["Боря"]


def test_conflict_resolved_by_editing_the_row(owner, code, member):
    book(owner, code, day(1), 9)
    body = upload(owner, code, grid(["Время", day(1)], ["09:00", "Боря"])).json()
    body["rows"][0].update(start="10:00", end="11:00")

    result = apply_import(owner, code, body["rows"]).json()
    assert (result["applied"], result["created"]) == (True, 1)
    assert [(b["name"], b["start"][11:16]) for b in bookings(owner, code)] == [
        ("Аня", "09:00"),
        ("Боря", "10:00"),
    ]


def test_rows_of_the_same_file_can_conflict_with_each_other(owner, code, member):
    body = upload(owner, code, grid(["Время", day(1)], ["09:00", "Аня, Боря"])).json()
    assert statuses(body) == ["new", "conflict"]
    conflict = body["results"][1]["conflicts"][0]
    assert (conflict["kind"], conflict["index"]) == ("row", 0)


# ---------- импорт: пустые ячейки ----------


def test_empty_cell_offers_deletion_but_keeps_booking_by_default(owner, code):
    kept = book(owner, code, day(1), 9)
    cleared = book(owner, code, day(1), 10)
    table = export(owner, code)
    table.active.cell(row_of(10), column_of(table.active, day(1))).value = None

    body = upload(owner, code, table).json()
    assert statuses(body) == ["unchanged"]
    assert [(m["booking_id"], m["label"], m["start"]) for m in body["missing"]] == [
        (cleared["id"], "Аня", f"{day(1)}T10:00:00")
    ]

    # без галочки запись остаётся
    result = apply_import(owner, code, body["rows"]).json()
    assert (result["applied"], result["deleted"]) == (True, 0)
    assert len(bookings(owner, code)) == 2

    # с галочкой — удаляется
    result = apply_import(owner, code, body["rows"], delete=[cleared["id"]]).json()
    assert (result["applied"], result["deleted"]) == (True, 1)
    assert [b["id"] for b in bookings(owner, code)] == [kept["id"]]


def test_times_and_days_absent_from_table_are_not_treated_as_empty(owner, code):
    in_table = book(owner, code, day(1), 9)
    book(owner, code, day(1), 15)  # такой строки в таблице нет
    book(owner, code, day(2), 9)  # такого столбца в таблице нет
    body = upload(owner, code, grid(["Время", day(1)], ["09:00", None], ["10:00", None])).json()

    assert body["rows"] == []
    assert [m["booking_id"] for m in body["missing"]] == [in_table["id"]]


def test_long_booking_is_missing_only_if_all_its_cells_are_empty(owner, code):
    booking = book(owner, code, day(1), 9, hours=2)
    header = ["Время", day(1)]

    partly_empty = upload(owner, code, grid(header, ["09:00", "Аня"], ["10:00", None])).json()
    assert partly_empty["missing"] == []

    fully_empty = upload(owner, code, grid(header, ["09:00", None], ["10:00", None])).json()
    assert [m["booking_id"] for m in fully_empty["missing"]] == [booking["id"]]


def test_deleting_ids_of_another_laundry_is_ignored(client, owner, code):
    foreign_owner = add_member(client, owner, code)  # просто ещё один пользователь
    foreign_code = foreign_owner.post(
        "/laundries", json={"name": "Чужая", "description": "", "capacity": 3}
    ).json()["code"]
    foreign = book(foreign_owner, foreign_code, day(1), 9)

    result = apply_import(owner, code, [], delete=[foreign["id"]]).json()
    assert (result["applied"], result["deleted"]) == (True, 0)
    assert len(bookings(foreign_owner, foreign_code)) == 1


def test_header_dates_survive_excel_datetime_cells(owner, code):
    """Excel отдаёт дату как datetime и время как time — оба варианта читаются."""
    start = datetime.combine(day(1), datetime.min.time())
    table = grid(["Время", start], [start.replace(hour=9).time(), "Аня"])
    body = upload(owner, code, table).json()
    assert [(r["date"], r["start"], r["end"]) for r in body["rows"]] == [(str(day(1)), "09:00", "10:00")]
