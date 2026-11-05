"""Single error format: {"error": {"code", "message"}}."""

from __future__ import annotations

import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import OperationalError
from starlette.exceptions import HTTPException as StarletteHTTPException

log = logging.getLogger(__name__)


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def unauthorized(message: str = "Missing, invalid or expired token") -> ApiError:
    return ApiError(401, "UNAUTHORIZED", message)


def _body(code: str, message: str) -> dict:
    return {"error": {"code": code, "message": message}}


def install(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def _api_error(_: Request, exc: ApiError):
        headers = {"WWW-Authenticate": "Bearer"} if exc.status == 401 else None
        return JSONResponse(_body(exc.code, exc.message), status_code=exc.status, headers=headers)

    @app.exception_handler(RequestValidationError)
    async def _validation(_: Request, exc: RequestValidationError):
        first = exc.errors()[0] if exc.errors() else {}
        where = ".".join(str(p) for p in first.get("loc", []) if p != "body")
        return JSONResponse(_body("VALIDATION_ERROR", f"{where}: {first.get('msg', 'invalid request')}"), status_code=422)

    @app.exception_handler(StarletteHTTPException)
    async def _http(_: Request, exc: StarletteHTTPException):
        code = {404: "NOT_FOUND", 405: "METHOD_NOT_ALLOWED"}.get(exc.status_code, "HTTP_ERROR")
        return JSONResponse(_body(code, str(exc.detail)), status_code=exc.status_code)

    @app.exception_handler(OperationalError)
    async def _db(_: Request, exc: OperationalError):
        log.exception("database error")
        return JSONResponse(_body("DB_UNAVAILABLE", "The database is not available"), status_code=503)

    @app.exception_handler(Exception)
    async def _unexpected(_: Request, exc: Exception):
        log.exception("unhandled error")
        return JSONResponse(_body("INTERNAL_ERROR", "Unexpected error"), status_code=500)
