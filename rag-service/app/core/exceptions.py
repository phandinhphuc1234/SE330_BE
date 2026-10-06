from time import perf_counter
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.logger import get_logger
from app.schemas.common import ErrorResponse

logger = get_logger(__name__)

# Đây là module định nghĩa các lớp ngoại lệ tùy chỉnh cho ứng dụng, bao gồm các lỗi như 
# NotFoundError, UnauthorizedError, ForbiddenError, ValidationError, Ingestion
class RagBaseException(Exception):
    message = "Unexpected application error"
    error_code = "RAG_ERROR"
    http_status = 500

    def __init__(self, message: str | None = None, error_code: str | None = None) -> None:
        self.message = message or self.message
        self.error_code = error_code or self.error_code
        # Keep Exception.args populated so str(exc) is useful in jobs/logs.
        super().__init__(self.message)


class NotFoundError(RagBaseException):
    error_code = "NOT_FOUND"
    http_status = 404


class UnauthorizedError(RagBaseException):
    error_code = "UNAUTHORIZED"
    http_status = 401


class ForbiddenError(RagBaseException):
    error_code = "FORBIDDEN"
    http_status = 403


class ValidationError(RagBaseException):
    error_code = "VALIDATION_ERROR"
    http_status = 422


class IngestionError(RagBaseException):
    error_code = "INGESTION_ERROR"
    http_status = 500


class ServiceUnavailableError(RagBaseException):
    error_code = "SERVICE_UNAVAILABLE"
    http_status = 503


class LLMError(RagBaseException):
    error_code = "LLM_ERROR"
    http_status = 502

# Hàm này được sử dụng để đăng ký các trình xử lý ngoại lệ tùy 
# chỉnh cho ứng dụng FastAPI. Nó liên kết các loại 
# ngoại lệ cụ thể với các hàm xử lý tương ứng, 
# đảm bảo rằng khi một ngoại lệ xảy ra,
#  nó sẽ được xử lý một cách nhất quán và trả về phản hồi lỗi phù hợp cho client.
def register_exception_handlers(app: FastAPI) -> None:
    app.add_exception_handler(RagBaseException, rag_exception_handler)
    app.add_exception_handler(StarletteHTTPException, http_exception_handler)
    app.add_exception_handler(RequestValidationError, validation_exception_handler)
    app.add_exception_handler(Exception, unhandled_exception_handler)

# Đây là các hàm xử lý ngoại lệ cụ thể cho từng loại lỗi.
# Chúng thực hiện việc ghi log chi tiết về lỗi, bao gồm thông tin về yêu cầu 
# và lỗi, và trả về phản hồi JSON với mã lỗi, thông điệp lỗi và các
async def rag_exception_handler(request: Request, exc: RagBaseException) -> JSONResponse:
    log_fields = _base_log_fields(request, exc.http_status, exc.error_code)
    if exc.http_status >= 500:
        logger.error("rag_exception", message=exc.message, **log_fields, exc_info=True)
    else:
        logger.warning("rag_exception", message=exc.message, **log_fields)

    return _json_error_response(
        request,
        status_code=exc.http_status,
        error_code=exc.error_code,
        message=exc.message,
    )

# Hàm này xử lý các lỗi HTTP, 
# ghi log chi tiết về lỗi và 
# trả về phản hồi JSON với mã lỗi, 
# thông điệp lỗi và các chi tiết liên quan. 
# Nếu lỗi là lỗi máy chủ (status code >= 500), nó sẽ được ghi lại ở mức độ lỗi (error), ngược lại sẽ được ghi lại ở mức độ cảnh báo (warning).
async def http_exception_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
    message = exc.detail if isinstance(exc.detail, str) else "HTTP error"
    error_code = f"HTTP_{exc.status_code}"
    log_fields = _base_log_fields(request, exc.status_code, error_code)
    if exc.status_code >= 500:
        logger.error("http_exception", message=message, **log_fields, exc_info=True)
    else:
        logger.warning("http_exception", message=message, **log_fields)
    return _json_error_response(
        request,
        status_code=exc.status_code,
        error_code=error_code,
        message=message,
        headers=exc.headers,
    )

# Mã lỗi mặc định cho các lỗi HTTP không phải là lỗi máy chủ (status code < 500).
async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    details = _sanitize_validation_errors(exc.errors())
    logger.warning(
        "request_validation_error",
        **_base_log_fields(request, 422, "REQUEST_VALIDATION_ERROR"),
        validation_error_count=len(details),
    )
    return _json_error_response(
        request,
        status_code=422,
        error_code="REQUEST_VALIDATION_ERROR",
        message="Request validation failed",
        details=details,
    )

# Hàm này xử lý tất cả các ngoại lệ không được xử lý khác, 
# ghi log chi tiết về lỗi và trả về phản hồi JSON với mã lỗi "INTERNAL_SERVER_ERROR" và thông điệp lỗi chung. 
# Đây là một lớp bảo vệ cuối cùng để đảm bảo rằng mọi lỗi không mong muốn đều được ghi lại và trả về cho client một phản hồi lỗi có cấu trúc.
async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    logger.error(
        "unhandled_exception",
        message="Unexpected application error",
        **_base_log_fields(request, 500, "INTERNAL_SERVER_ERROR"),
        exc_info=True,
    )
    return _json_error_response(
        request,
        status_code=500,
        error_code="INTERNAL_SERVER_ERROR",
        message="Unexpected application error",
    )

# Hàm này được sử dụng để tạo phản hồi lỗi JSON có cấu trúc 
# cho các lỗi xảy ra trong ứng dụng.
def _json_error_response(
    request: Request,
    *,
    status_code: int,
    error_code: str,
    message: str,
    details: dict[str, Any] | list[dict[str, Any]] | None = None,
    headers: dict[str, str] | None = None,
) -> JSONResponse:
    response_headers = {**(headers or {}), **_observability_headers(request)}
    payload = ErrorResponse(
        error_code=error_code,
        message=message,
        request_id=getattr(request.state, "request_id", None),
        details=details,
    )
    return JSONResponse(
        status_code=status_code,
        content=payload.model_dump(exclude_none=True),
        headers=response_headers,
    )

# Hàm này thu thập các header liên quan đến quan sát (observability) từ yêu cầu,
# bao gồm request_id và thời gian xử lý nếu có, để thêm vào phản hồi lỗi. 
# Điều này giúp cải thiện khả năng theo dõi và gỡ lỗi khi xảy ra lỗi, 
# bằng cách cung cấp thông tin chi tiết về yêu cầu và thời gian xử lý trong phản hồi
def _observability_headers(request: Request) -> dict[str, str]:
    headers: dict[str, str] = {}
    request_id = getattr(request.state, "request_id", None)
    if request_id:
        headers["X-Request-ID"] = request_id

    process_started_at = getattr(request.state, "process_started_at", None)
    if process_started_at is not None:
        headers["X-Process-Time"] = f"{perf_counter() - process_started_at:.6f}"
    return headers

# Hàm này tạo một dictionary cơ bản chứa các trường thông tin chung về lỗi,
def _base_log_fields(request: Request, status_code: int, error_code: str) -> dict[str, Any]:
    return {
        "request_id": getattr(request.state, "request_id", None),
        "method": request.method,
        "path": request.url.path,
        "status_code": status_code,
        "error_code": error_code,
    }

# Hàm này được sử dụng để làm sạch và chuẩn hóa các lỗi xác thực (validation errors)
def _sanitize_validation_errors(errors: list[dict[str, Any]]) -> list[dict[str, Any]]:
    sanitized: list[dict[str, Any]] = []
    for error in errors:
        sanitized.append(
            {
                "loc": [str(part) for part in error.get("loc", [])],
                "message": error.get("msg", "Invalid input"),
                "type": error.get("type", "validation_error"),
            }
        )
    return sanitized
