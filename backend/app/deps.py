from dataclasses import dataclass

from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from sqlalchemy.orm import Session

from .db import get_db
from .models import ROLE_ADMIN, ROLE_OWNER, STATUS_ACTIVE, Laundry, Membership, User
from .security import decode_token

bearer = HTTPBearer(auto_error=False)


def current_user(
    cred: HTTPAuthorizationCredentials | None = Depends(bearer),
    db: Session = Depends(get_db),
) -> User:
    user_id = decode_token(cred.credentials) if cred else None
    user = db.get(User, user_id) if user_id else None
    if user is None:
        raise HTTPException(401, "Требуется вход")
    return user


@dataclass
class Ctx:
    user: User
    laundry: Laundry
    membership: Membership | None

    @property
    def is_member(self) -> bool:
        return self.membership is not None and self.membership.status == STATUS_ACTIVE

    @property
    def is_admin(self) -> bool:
        return self.is_member and self.membership.role in (ROLE_OWNER, ROLE_ADMIN)

    @property
    def is_owner(self) -> bool:
        return self.is_member and self.membership.role == ROLE_OWNER


def laundry_ctx(
    code: str, user: User = Depends(current_user), db: Session = Depends(get_db)
) -> Ctx:
    laundry = db.scalar(select(Laundry).where(Laundry.code == code.strip().upper()))
    if laundry is None:
        raise HTTPException(404, "Стиралка не найдена")
    membership = db.scalar(
        select(Membership).where(
            Membership.laundry_id == laundry.id, Membership.user_id == user.id
        )
    )
    return Ctx(user=user, laundry=laundry, membership=membership)


def member_ctx(ctx: Ctx = Depends(laundry_ctx)) -> Ctx:
    if not ctx.is_member:
        raise HTTPException(403, "Вы не участник этой стиралки")
    return ctx


def admin_ctx(ctx: Ctx = Depends(laundry_ctx)) -> Ctx:
    if not ctx.is_admin:
        raise HTTPException(403, "Нужны права администратора")
    return ctx


def owner_ctx(ctx: Ctx = Depends(laundry_ctx)) -> Ctx:
    if not ctx.is_owner:
        raise HTTPException(403, "Доступно только создателю стиралки")
    return ctx


def lock_laundry(db: Session, laundry: Laundry) -> None:
    """Сериализует изменения записей одной стиралки в пределах транзакции."""
    db.execute(select(Laundry.id).where(Laundry.id == laundry.id).with_for_update())
