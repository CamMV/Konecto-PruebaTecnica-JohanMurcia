from fastapi import APIRouter
from sqlalchemy import select

from app.api.deps import DbDep, UserDep
from app.api.errors import ApiError
from app.api.schemas import ErrorResponse, LoginRequest, LoginResponse, UserOut
from app.auth.security import create_access_token, verify_password
from app.db.models import AppUser

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/login", response_model=LoginResponse, responses={401: {"model": ErrorResponse}})
def login(body: LoginRequest, db: DbDep) -> LoginResponse:
    user = db.scalar(select(AppUser).where(AppUser.email == body.email))
    if user is None or not verify_password(body.password, user.password_hash):
        raise ApiError(401, "INVALID_CREDENTIALS", "Wrong email or password")
    token, expires_in = create_access_token(user.id)
    return LoginResponse(access_token=token, expires_in=expires_in, user=UserOut.model_validate(user))


@router.get("/me", response_model=UserOut, responses={401: {"model": ErrorResponse}})
def me(user: UserDep) -> UserOut:
    return UserOut.model_validate(user)
