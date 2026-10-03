from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.core.config import get_settings
from app.core.logger import get_logger

settings = get_settings()
logger = get_logger(__name__)

# Celery tasks currently bridge async code with `asyncio.run()`. Each task run
# owns a fresh event loop, while asyncpg connections are bound to the event loop
# that created them. A normal SQLAlchemy async connection pool can therefore
# reuse a connection from an old loop and fail with:
#
#   got Future attached to a different loop
#
# NullPool makes every session open/close its own asyncpg connection instead of
# reusing loop-bound connections across Celery task runs. This is safer for the
# current worker architecture and still fine for the Library RAG MVP workload.
engine = create_async_engine(
    settings.postgres_url,
    pool_pre_ping=True,
    poolclass=NullPool,
)
async_session_factory = async_sessionmaker(engine, expire_on_commit=False)

logger.info(
    "db_async_engine_configured",
    postgres_url_driver=settings.postgres_url.split("://", 1)[0],
    pool_class="NullPool",
    pool_pre_ping=True,
)
