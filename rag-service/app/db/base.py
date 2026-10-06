from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass

# Hàm này được sử dụng để nhập tất cả các mô hình SQLAlchemy 
# từ các module khác nhau trong ứng dụng,
def import_all_models() -> None:
    # Alembic calls this to populate Base.metadata without creating runtime import cycles.
    from app.documents import models as document_models  # noqa: F401
    from app.ingestion import models as ingestion_models  # noqa: F401
    from app.retrieval import graph_models  # noqa: F401
