from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.internal import internal_router
from app.api.v1 import api_router
from app.core.config import get_settings
from app.core.exceptions import register_exception_handlers
from app.core.logger import configure_logging
from app.core.middlewares.request_id import RequestIdMiddleware
from app.core.middlewares.timing import TimingMiddleware


@asynccontextmanager
async def lifespan(app: FastAPI):
    yield


def create_app() -> FastAPI:
    configure_logging()
    settings = get_settings()
    app = FastAPI(
        title="Professional RAG Platform",
        lifespan=lifespan,
        docs_url="/docs" if settings.enable_api_docs else None,
        redoc_url="/redoc" if settings.enable_api_docs else None,
        openapi_url="/openapi.json" if settings.enable_api_docs else None,
    )

    app.add_middleware(TimingMiddleware)
    app.add_middleware(RequestIdMiddleware)

    register_exception_handlers(app)
    app.include_router(api_router, prefix="/api/v1")
    app.include_router(internal_router, prefix="/internal")

    return app


app = create_app()
