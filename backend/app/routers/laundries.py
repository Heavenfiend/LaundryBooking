import secrets

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from ..config import now_local
from ..db import get_db
from ..deps import Ctx, admin_ctx, current_user, laundry_ctx, lock_laundry, member_ctx, owner_ctx
from ..models import (
    ROLE_ADMIN,
    ROLE_MEMBER,
    ROLE_OWNER,
    STATUS_ACTIVE,
    STATUS_PENDING,
    Booking,
    Laundry,
    Membership,
    User,
)
from ..schemas import LaundryIn, LaundryOut, MemberOut, RoleIn

router = APIRouter(prefix="/api/laundries", tags=["laundries"])

CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # без похожих символов 0/O, 1/I


def active_count(db: Session, laundry_id: int) -> int:
    return db.scalar(
        select(func.count())
        .select_from(Membership)
        .where(Membership.laundry_id == laundry_id, Membership.status == STATUS_ACTIVE)
    ) or 0


def laundry_out(db: Session, laundry: Laundry, membership: Membership | None) -> LaundryOut:
    count = active_count(db, laundry.id)
    return LaundryOut(
        code=laundry.code,
        name=laundry.name,
        description=laundry.description,
        capacity=laundry.capacity,
        member_count=count,
        my_role=membership.role if membership else None,
        my_status=membership.status if membership else None,
    )


def get_target(db: Session, ctx: Ctx, user_id: int) -> Membership:
    target = db.scalar(
        select(Membership).where(
            Membership.laundry_id == ctx.laundry.id, Membership.user_id == user_id
        )
    )
    if target is None:
        raise HTTPException(404, "Участник не найден")
    return target


def drop_future_bookings(db: Session, laundry_id: int, user_id: int) -> None:
    db.execute(
        delete(Booking).where(
            Booking.laundry_id == laundry_id,
            Booking.user_id == user_id,
            Booking.start_at >= now_local(),
        )
    )


@router.get("", response_model=list[LaundryOut])
def my_laundries(user: User = Depends(current_user), db: Session = Depends(get_db)):
    memberships = db.scalars(
        select(Membership).where(Membership.user_id == user.id).order_by(Membership.id)
    ).all()
    return [laundry_out(db, m.laundry, m) for m in memberships]


@router.post("", response_model=LaundryOut)
def create_laundry(
    data: LaundryIn, user: User = Depends(current_user), db: Session = Depends(get_db)
):
    for _ in range(20):
        code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(6))
        if db.scalar(select(Laundry.id).where(Laundry.code == code)) is None:
            break
    else:
        raise HTTPException(500, "Не удалось сгенерировать ID")
    laundry = Laundry(
        code=code, name=data.name, description=data.description, capacity=data.capacity
    )
    db.add(laundry)
    db.flush()
    membership = Membership(
        laundry_id=laundry.id, user_id=user.id, role=ROLE_OWNER, status=STATUS_ACTIVE
    )
    db.add(membership)
    db.commit()
    return laundry_out(db, laundry, membership)


@router.get("/{code}", response_model=LaundryOut)
def get_laundry(ctx: Ctx = Depends(laundry_ctx), db: Session = Depends(get_db)):
    return laundry_out(db, ctx.laundry, ctx.membership)


@router.patch("/{code}", response_model=LaundryOut)
def update_laundry(
    data: LaundryIn, ctx: Ctx = Depends(admin_ctx), db: Session = Depends(get_db)
):
    if data.capacity < active_count(db, ctx.laundry.id):
        raise HTTPException(400, "Лимит не может быть меньше текущего числа участников")
    ctx.laundry.name = data.name
    ctx.laundry.description = data.description
    ctx.laundry.capacity = data.capacity
    db.commit()
    return laundry_out(db, ctx.laundry, ctx.membership)


@router.delete("/{code}")
def delete_laundry(ctx: Ctx = Depends(owner_ctx), db: Session = Depends(get_db)):
    db.execute(delete(Laundry).where(Laundry.id == ctx.laundry.id))
    db.commit()
    return {"ok": True}


@router.post("/{code}/join", response_model=LaundryOut)
def join(ctx: Ctx = Depends(laundry_ctx), db: Session = Depends(get_db)):
    if ctx.membership is None:
        ctx.membership = Membership(
            laundry_id=ctx.laundry.id,
            user_id=ctx.user.id,
            role=ROLE_MEMBER,
            status=STATUS_PENDING,
        )
        db.add(ctx.membership)
        db.commit()
    return laundry_out(db, ctx.laundry, ctx.membership)


@router.post("/{code}/leave")
def leave(ctx: Ctx = Depends(laundry_ctx), db: Session = Depends(get_db)):
    """Выйти из стиралки или отозвать свою заявку."""
    if ctx.membership is None:
        raise HTTPException(404, "Вы не состоите в этой стиралке")
    if ctx.membership.role == ROLE_OWNER:
        raise HTTPException(400, "Создатель не может выйти — можно только удалить стиралку")
    drop_future_bookings(db, ctx.laundry.id, ctx.user.id)
    db.delete(ctx.membership)
    db.commit()
    return {"ok": True}


@router.get("/{code}/members", response_model=list[MemberOut])
def members(ctx: Ctx = Depends(member_ctx), db: Session = Depends(get_db)):
    query = select(Membership).where(Membership.laundry_id == ctx.laundry.id)
    if not ctx.is_admin:
        query = query.where(Membership.status == STATUS_ACTIVE)
    order = {ROLE_OWNER: 0, ROLE_ADMIN: 1, ROLE_MEMBER: 2}
    rows = sorted(
        db.scalars(query).all(),
        key=lambda m: (order.get(m.role, 9), m.user.display_name.lower()),
    )
    return [
        MemberOut(
            user_id=m.user_id,
            username=m.user.username,
            display_name=m.user.display_name,
            role=m.role,
            status=m.status,
        )
        for m in rows
    ]


@router.post("/{code}/members/{user_id}/approve")
def approve(user_id: int, ctx: Ctx = Depends(admin_ctx), db: Session = Depends(get_db)):
    target = get_target(db, ctx, user_id)
    if target.status != STATUS_ACTIVE:
        lock_laundry(db, ctx.laundry)  # чтобы два одобрения разом не превысили лимит
        if active_count(db, ctx.laundry.id) >= ctx.laundry.capacity:
            raise HTTPException(
                409,
                f"Стиралка заполнена (лимит {ctx.laundry.capacity} уч.) — увеличьте лимит в настройках",
            )
    target.status = STATUS_ACTIVE
    db.commit()
    return {"ok": True}


@router.delete("/{code}/members/{user_id}")
def remove_member(user_id: int, ctx: Ctx = Depends(admin_ctx), db: Session = Depends(get_db)):
    """Отклонить заявку или удалить участника."""
    target = get_target(db, ctx, user_id)
    if target.role == ROLE_OWNER:
        raise HTTPException(400, "Нельзя удалить создателя")
    if target.role == ROLE_ADMIN and not ctx.is_owner:
        raise HTTPException(403, "Администратора может удалить только создатель")
    drop_future_bookings(db, ctx.laundry.id, user_id)
    db.delete(target)
    db.commit()
    return {"ok": True}


@router.put("/{code}/members/{user_id}/role")
def set_role(
    user_id: int, data: RoleIn, ctx: Ctx = Depends(owner_ctx), db: Session = Depends(get_db)
):
    target = get_target(db, ctx, user_id)
    if target.role == ROLE_OWNER:
        raise HTTPException(400, "Роль создателя изменить нельзя")
    if target.status != STATUS_ACTIVE:
        raise HTTPException(400, "Сначала одобрите заявку")
    target.role = data.role
    db.commit()
    return {"ok": True}
