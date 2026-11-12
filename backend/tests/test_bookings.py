from datetime import timedelta

from app.config import now_local
from helpers import book, bookings, day, register, slot


def test_schedule_window_is_week_back_and_three_weeks_forward(owner, code):
    body = owner.get(f"/laundries/{code}/bookings").json()
    assert body["date_from"] == day(-7).isoformat()
    assert body["date_to"] == day(21).isoformat()
    assert body["bookings"] == []


def test_member_books_a_slot(owner, code, member):
    created = book(member, code, day(1), 10)
    assert created["name"] == "Боря"
    assert created["mine"] is True
    assert created["start"] == f"{day(1)}T10:00:00"
    assert created["end"] == f"{day(1)}T11:00:00"

    seen_by_owner = bookings(owner, code)
    assert len(seen_by_owner) == 1
    assert seen_by_owner[0]["mine"] is False


def test_taken_slot_cannot_be_booked_again(owner, code, member):
    book(owner, code, day(1), 10)
    assert member.post(f"/laundries/{code}/bookings", json=slot(day(1), 10)).status_code == 409
    assert owner.post(f"/laundries/{code}/bookings", json=slot(day(1), 10)).status_code == 409


def test_overlapping_interval_is_rejected(owner, code, member):
    book(owner, code, day(1), 10, hours=2)  # 10:00–12:00
    assert member.post(f"/laundries/{code}/bookings", json=slot(day(1), 11)).status_code == 409
    assert member.post(f"/laundries/{code}/bookings", json=slot(day(1), 9, hours=2)).status_code == 409


def test_adjacent_slots_do_not_conflict(owner, code, member):
    book(owner, code, day(1), 10)
    book(member, code, day(1), 11)
    book(member, code, day(1), 9)
    assert len(bookings(owner, code)) == 3


def test_last_hour_of_day_ends_at_midnight(owner, code):
    created = book(owner, code, day(1), 23)
    assert created["end"] == f"{day(2)}T00:00:00"


def test_cannot_book_past_time(owner, code):
    now = now_local()
    body = {
        "start": (now - timedelta(hours=2)).isoformat(),
        "end": (now - timedelta(hours=1)).isoformat(),
    }
    assert owner.post(f"/laundries/{code}/bookings", json=body).status_code == 400


def test_booking_horizon_is_21_days(owner, code):
    assert owner.post(f"/laundries/{code}/bookings", json=slot(day(21), 10)).status_code == 200
    assert owner.post(f"/laundries/{code}/bookings", json=slot(day(22), 10)).status_code == 400


def test_end_must_be_after_start(owner, code):
    body = slot(day(1), 10)
    body["end"] = body["start"]
    assert owner.post(f"/laundries/{code}/bookings", json=body).status_code == 400


def test_member_cancels_own_booking(owner, code, member):
    created = book(member, code, day(1), 10)
    assert member.delete(f"/laundries/{code}/bookings/{created['id']}").status_code == 200
    assert bookings(owner, code) == []
    # слот снова свободен
    assert owner.post(f"/laundries/{code}/bookings", json=slot(day(1), 10)).status_code == 200


def test_member_cannot_delete_someone_elses_booking(owner, code, member):
    created = book(owner, code, day(1), 10)
    assert member.delete(f"/laundries/{code}/bookings/{created['id']}").status_code == 403
    assert len(bookings(owner, code)) == 1


def test_admin_deletes_any_booking(owner, code, member):
    created = book(member, code, day(1), 10)
    assert owner.delete(f"/laundries/{code}/bookings/{created['id']}").status_code == 200
    assert bookings(owner, code) == []


def test_booking_of_another_laundry_is_not_found(client, owner, code):
    created = book(owner, code, day(1), 10)
    stranger = register(client)
    other_code = stranger.post(
        "/laundries", json={"name": "Чужая", "description": "", "capacity": 3}
    ).json()["code"]
    assert stranger.delete(f"/laundries/{other_code}/bookings/{created['id']}").status_code == 404
    assert stranger.delete(f"/laundries/{other_code}/bookings/999999999").status_code == 404
    assert len(bookings(owner, code)) == 1


def test_outsiders_have_no_access_to_schedule(client, code):
    outsider = register(client)
    assert outsider.get(f"/laundries/{code}/bookings").status_code == 403
    assert outsider.post(f"/laundries/{code}/bookings", json=slot(day(1), 10)).status_code == 403
    assert outsider.get(f"/laundries/{code}/export").status_code == 403
    assert client.get(f"/api/laundries/{code}/bookings").status_code == 401
