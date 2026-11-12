from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Index, Integer, String, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .db import Base

ROLE_OWNER = "owner"
ROLE_ADMIN = "admin"
ROLE_MEMBER = "member"
STATUS_PENDING = "pending"
STATUS_ACTIVE = "active"


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(32), unique=True, index=True)
    display_name: Mapped[str] = mapped_column(String(64))
    password_hash: Mapped[str] = mapped_column(String(128))
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())


class Laundry(Base):
    __tablename__ = "laundries"

    id: Mapped[int] = mapped_column(primary_key=True)
    code: Mapped[str] = mapped_column(String(12), unique=True, index=True)
    name: Mapped[str] = mapped_column(String(100))
    description: Mapped[str] = mapped_column(String(1000), default="")
    # максимальное число участников стиралки
    capacity: Mapped[int] = mapped_column(Integer, default=30)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())


class Membership(Base):
    __tablename__ = "memberships"
    __table_args__ = (UniqueConstraint("laundry_id", "user_id"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    laundry_id: Mapped[int] = mapped_column(ForeignKey("laundries.id", ondelete="CASCADE"), index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    role: Mapped[str] = mapped_column(String(10), default=ROLE_MEMBER)
    status: Mapped[str] = mapped_column(String(10), default=STATUS_PENDING)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())

    user: Mapped[User] = relationship()
    laundry: Mapped[Laundry] = relationship()


class Booking(Base):
    __tablename__ = "bookings"
    __table_args__ = (Index("ix_bookings_laundry_start", "laundry_id", "start_at"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    laundry_id: Mapped[int] = mapped_column(ForeignKey("laundries.id", ondelete="CASCADE"))
    # user_id пуст для записей, импортированных из Excel на человека без аккаунта
    user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"), nullable=True)
    guest_name: Mapped[str | None] = mapped_column(String(100), nullable=True)
    start_at: Mapped[datetime] = mapped_column(DateTime)
    end_at: Mapped[datetime] = mapped_column(DateTime)
    note: Mapped[str] = mapped_column(String(200), default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())

    user: Mapped[User | None] = relationship()

    @property
    def person_name(self) -> str:
        if self.user is not None:
            return self.user.display_name
        return self.guest_name or "—"

    @property
    def person_key(self) -> tuple:
        if self.user_id is not None:
            return ("u", self.user_id)
        return ("g", (self.guest_name or "").strip().lower())
