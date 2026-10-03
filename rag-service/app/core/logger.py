import logging
import sys
from typing import Any

import structlog

from app.core.config import get_settings

# Đây là module cấu hình logging cho ứng dụng, sử dụng thư viện structlog để cung cấp logging có cấu trúc và hỗ trợ contextvars.
# Nó định nghĩa một hàm configure_logging để thiết lập cấu hình logging dựa trên các cài đặt từ get_settings, 
# và một hàm get_logger để lấy logger có cấu trúc cho các module khác trong ứng dụng.
def configure_logging() -> None:
    settings = get_settings()
    log_level = getattr(logging, settings.log_level.upper(), logging.INFO)
    render_json = settings.log_json if settings.log_json is not None else settings.app_env == "production"

    processors = [
        structlog.contextvars.merge_contextvars,
        structlog.stdlib.add_logger_name,
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.stdlib.add_log_level,
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
        structlog.processors.UnicodeDecoder(),
    ]

    if render_json:
        processors.append(structlog.processors.JSONRenderer())
    else:
        processors.append(structlog.dev.ConsoleRenderer())

    logging.basicConfig(format="%(message)s", stream=sys.stdout, level=log_level, force=True)
    structlog.configure(
        processors=processors,
        logger_factory=structlog.stdlib.LoggerFactory(),
        wrapper_class=structlog.stdlib.BoundLogger,
        cache_logger_on_first_use=True,
    )


def get_logger(name: str | None = None) -> Any:
    if name is None:
        return structlog.get_logger()
    return structlog.get_logger(name)
