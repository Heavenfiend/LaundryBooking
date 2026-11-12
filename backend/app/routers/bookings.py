from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from ..config import DAYS_FORWARD, now_local, window, window_dt
from ..db import get_db
from ..deps import Ctx, lock_laundry, member_ctx
from ..logic import SLOT_CAPACITY, booking_item, find_conflicts
from ..models import Booking
from ..schemas import BookingIn, BookingOut, ScheduleOut

router = APIRouter(prefix="/api/laundries/{code}/bookings", tags=["bookings"])


def window_bookings(db: Session, laundry_id: int) -> list[Booking]:
    lo, hi = window_dt()
    return list(
        db.scalars(
            select(Booking)
            .where(Booking.laundry_id == laundry_id, Booking.end_at > lo, Booking.start_at < hi)
            .options(selectinload(Booking.user))
            .order_by(Booking.start_at, Booking.id)
        ).all()
    )


def booking_out(b: Booking, user_id: int) -> BookingOut:
    return BookingOut(
        id=b.id,
        user_id=b.user_id,
        name=b.person_name,
        start=b.start_at,
        end=b.end_at,
        note=b.note,
        mine=b.user_id == user_id,
    )


@router.get("", response_model=ScheduleOut)
def schedule(ctx: Ctx = Depends(member_ctx), db: Session = Depends(get_db)):
    lo, hi = window()
    return ScheduleOut(
        date_from=lo.isoformat(),
        date_to=hi.isoformat(),
        now=now_local(),
        bookings=[booking_out(b, ctx.user.id) for b in window_bookings(db, ctx.laundry.id)],
    )


@router.post("", response_model=BookingOut)
def create_booking(data: BookingIn, ctx: Ctx = Depends(member_ctx), db: Session = Depends(get_db)):
    start = data.start.replace(tzinfo=None, second=0, microsecond=0)
    end = data.end.replace(tzinfo=None, second=0, microsecond=0)
    now = now_local()
    if end <= start:
        raise HTTPException(400, "Конец должен быть позже начала")
    if end - start > timedelta(hours=24):
        raise HTTPException(400, "Слишком длинная запись")
    if end <= now:
        raise HTTPException(400, "Нельзя записаться на прошедшее время")
    if start.date() > now.date() + timedelta(days=DAYS_FORWARD):
        raise HTTPException(400, f"Записываться можно не дальше чем на {DAYS_FORWARD} дн. вперёд")

    lock_laundry(db, ctx.laundry)
    pool = [
        booking_item(b)
        for b in db.scalars(
            select(Booking)
            .where(
                Booking.laundry_id == ctx.laundry.id,
                Booking.start_at < end,
                Booking.end_at > start,
            )
            .options(selectinload(Booking.user))
        ).all()
    ]
    person = ("u", ctx.user.id)
    conflicts = find_conflicts(pool, person, start, end, SLOT_CAPACITY)
    if any(c.person == person for c in conflicts):
        raise HTTPException(409, "У вас уже есть запись на это время")
    if conflicts:
        raise HTTPException(409, "Это время уже занято")

    booking = Booking(
        laundry_id=ctx.laundry.id,
        user_id=ctx.user.id,
        start_at=start,
        end_at=end,
        note=data.note.strip(),
    )
    db.add(booking)
    db.commit()
    db.refresh(booking)
    return booking_out(booking, ctx.user.id)


@router.delete("/{booking_id}")
def delete_booking(booking_id: int, ctx: Ctx = Depends(member_ctx), db: Session = Depends(get_db)):
    booking = db.get(Booking, booking_id)
    if booking is None or booking.laundry_id != ctx.laundry.id:
        raise HTTPException(404, "Запись не найдена")
    if not ctx.is_admin:
        if booking.user_id != ctx.user.id:
            raise HTTPException(403, "Чужую запись может удалить только администратор")
        if booking.end_at <= now_local():
            raise HTTPException(400, "Прошедшую запись удалить нельзя")
    db.delete(booking)
    db.commit()
    return {"ok": True}
