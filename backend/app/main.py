import time
from contextlib import asynccontextmanager

from fastapi import FastAPI
from sqlalchemy.exc import OperationalError

from . import models  # noqa: F401 — регистрирует таблицы
from .db import Base, engine
from .routers import auth, bookings, excel, laundries


@asynccontextmanager
async def lifespan(app: FastAPI):
    for attempt in range(30):
        try:
            Base.metadata.create_all(engine)
            break
        except OperationalError:
            if attempt == 29:
                raise
            time.sleep(1)
    yield


app = FastAPI(title="Стиралка", lifespan=lifespan)
app.include_router(auth.router)
app.include_router(laundries.router)
app.include_router(bookings.router)
app.include_router(excel.router)


@app.get("/api/health")
def health():
    return {"ok": True}
