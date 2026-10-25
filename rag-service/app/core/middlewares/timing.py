from time import perf_counter

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.routing import Match

from app.core.logger import get_logger
from app.core.metrics import record_http_request

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
            duration_seconds = perf_counter() - start
            record_http_request(
                method=request.method,
                route=_route_template(request),
                status="exception",
                duration_seconds=duration_seconds,
            )
            logger.error(
                "request_failed",
                **request_fields,
                duration_ms=round(duration_seconds * 1000, 2),
                exc_info=True,
            )
            raise

        process_time = perf_counter() - start
        record_http_request(
            method=request.method,
            route=_route_template(request),
            status=str(response.status_code),
            duration_seconds=process_time,
        )
        response.headers["X-Process-Time"] = f"{process_time:.6f}"
        logger.info(
            "request_finished",
            **request_fields,
            status_code=response.status_code,
            duration_ms=round(process_time * 1000, 2),
        )
        return response


def _route_template(request: Request) -> str:
    route = request.scope.get("route")
    path = getattr(route, "path", None)
    # BaseHTTPMiddleware can execute outside the child router's mutated scope.
    # Match against registered routes so IDs never become metric labels.
    for registered_route in request.app.routes:
        match, _ = registered_route.matches(request.scope)
        if match is Match.FULL:
            template = getattr(registered_route, "path", None)
            if isinstance(template, str) and template:
                return template
            # FastAPI 0.141 keeps included routers lazy. Their effective route
            # contexts expose the fully-prefixed path without materializing
            # user IDs into the label.
            contexts = getattr(registered_route, "effective_route_contexts", None)
            if callable(contexts):
                for context in contexts():
                    path_regex = getattr(context, "path_regex", None)
                    methods = getattr(context, "methods", set())
                    if (
                        path_regex is not None
                        and path_regex.match(request.url.path)
                        and (not methods or request.method in methods)
                    ):
                        context_path = getattr(context, "path_format", None) or getattr(context, "path", None)
                        if isinstance(context_path, str) and context_path:
                            return context_path
    return path if isinstance(path, str) and path else "unmatched"
