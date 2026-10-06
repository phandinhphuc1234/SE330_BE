from fastapi import APIRouter, Depends

from app.api.internal.routes_answers import router as answers_router
from app.api.internal.routes_ingestions import router as ingestions_router
from app.api.internal.routes_retrieval import router as retrieval_router
from app.core.internal_auth import require_internal_api_key


internal_router = APIRouter(dependencies=[Depends(require_internal_api_key)])
internal_router.include_router(ingestions_router, prefix="/ingestions", tags=["internal-ingestions"])
internal_router.include_router(retrieval_router, prefix="/retrieval", tags=["internal-retrieval"])
internal_router.include_router(answers_router, prefix="/answers", tags=["internal-answers"])
