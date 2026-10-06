from time import perf_counter

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request

from app.core.logger import get_logger

logger = get_logger(__name__)

# Middleware này được thiết kế để đo thời gian xử lý của mỗi yêu cầu đến.
class TimingMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        start = perf_counter()
        request.state.process_started_at = start
        request_fields = {
            "method": request.method,
            "path": request.url.path,
            "request_id": getattr(request.state, "request_id", None),
            "client_host": request.client.host if request.client else None,
        }
        logger.info("request_started", **request_fields)

        try:
            response = await call_next(request)
        except Exception:
            logger.error(
                "request_failed",
                **request_fields,
                duration_ms=_duration_ms(start),
                exc_info=True,
            )
            raise

        process_time = perf_counter() - start
        response.headers["X-Process-Time"] = f"{process_time:.6f}"
        logger.info(
            "request_finished",
            **request_fields,
            status_code=response.status_code,
            duration_ms=round(process_time * 1000, 2),
        )
        return response


def _duration_ms(start: float) -> float:
    return round((perf_counter() - start) * 1000, 2)
