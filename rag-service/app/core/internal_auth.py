import secrets

from fastapi import Header

from app.core.config import get_settings
from app.core.exceptions import ServiceUnavailableError, UnauthorizedError

# Function require_internal_api_key is a FastAPI dependency 
# that checks for the presence of a 
# valid internal API key in the request headers. 
# It retrieves the expected key from the application settings and compares it with the provided key using a secure comparison method. If the keys do not match or if the expected key is not configured, it raises an appropriate error (ServiceUnavailableError or UnauthorizedError) to prevent unauthorized access to internal API endpoints.
async def require_internal_api_key(
    x_rag_api_key: str | None = Header(default=None, alias="X-RAG-API-Key"),
) -> None:
    """Authenticate service-to-service calls without introducing RAG-local users."""

    expected_key = get_settings().rag_internal_api_key
    if not expected_key:
        raise ServiceUnavailableError(
            "Internal API authentication is not configured",
            error_code="INTERNAL_AUTH_NOT_CONFIGURED",
        )
    if x_rag_api_key is None or not secrets.compare_digest(x_rag_api_key, expected_key):
        raise UnauthorizedError(
            "Invalid internal service credential",
            error_code="INVALID_INTERNAL_API_KEY",
        )
