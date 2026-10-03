from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

# Định nghĩa một lớp Settings sử dụng Pydantic để quản lý cấu hình ứng dụng,
#  bao gồm các cài đặt liên quan đến môi trường, 
# logging, cơ sở dữ liệu, embedding, LLM,
# storage và các tham số chunking. 
# Lớp này cũng cung cấp các thuộc tính để lấy URL broker và 
# result backend cho Celery một cách hiệu quả.
class Settings(BaseSettings):
    # Lây câu hình từ file .env và thiết lập mã hóa UTF-8 cho file này. Điều này cho phép ứng dụng dễ dàng quản lý các biến môi trường và đảm bảo rằng chúng được đọc đúng cách, đặc biệt là khi chứa các ký tự đặc biệt hoặc không phải ASCII.
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    app_env: str = "development"
    debug: bool = Field(default=True, validation_alias="APP_DEBUG")
    log_level: str = "INFO"
    log_json: bool | None = None
    rag_internal_api_key: str = ""
    enable_api_docs: bool = False
    # Cấu hình cơ sở dữ liệu, Redis, Celery, Qdrant, embedding, LLM, storage và các tham số chunking.
    # Các giá trị mặc định được cung cấp để dễ dàng phát triển và thử nghiệm,
    #  nhưng nên được tùy chỉnh cho môi trường sản xuất.
    postgres_url: str = "postgresql+asyncpg://user:password@localhost:5432/rag_db"
    postgres_url_sync: str = "postgresql://user:password@localhost:5432/rag_db"
    redis_url: str = "redis://localhost:6379/0"
    celery_broker_url: str | None = None
    celery_result_backend: str | None = None

    qdrant_url: str = "http://localhost:6333"
    qdrant_api_key: str | None = None
    qdrant_collection_name: str = "rag_chunks"

    # Embedding defaults follow the current Library RAG MVP decision:
    # Gemini creates vectors, Qdrant stores/searches them, and the worker keeps
    # control of job status, artifacts, retries and metadata.
    embedding_provider: str = "gemini"
    embedding_model: str = "gemini-embedding-2"
    embedding_dim: int = 3072
    embedding_fallback_model: str = "gemini-embedding-001"
    embedding_version: str = "gemini-embedding-2-3072-v1"
    embedding_text_policy: str = "gemini_search_title_text_v1"
    # Each item in a Gemini batch is wrapped in its own Content object, so every
    # chunk still receives an independent vector while using far fewer HTTP
    # requests than the original one-request-per-chunk MVP.
    embedding_batch_size: int = Field(default=8, ge=1, le=100)
    embedding_max_retries: int = Field(default=5, ge=0, le=10)
    embedding_retry_base_delay_seconds: float = Field(default=2.0, ge=0.0, le=60.0)
    embedding_retry_max_delay_seconds: float = Field(default=60.0, ge=1.0, le=600.0)
    embedding_retry_jitter_seconds: float = Field(default=0.5, ge=0.0, le=10.0)
    embedding_distance_metric: str = "Cosine"
    # Cấu hình LLM, bao gồm nhà cung cấp, API key, model, 
    # nhiệt độ và số token tối đa.
    llm_provider: str = "openai"
    openai_api_key: str | None = None
    llm_model: str = "gpt-4o-mini"
    llm_temperature: float = 0.1
    llm_max_tokens: int = 2048
    gemini_api_key: str | None = None
    # Cấu hình lưu trữ, bao gồm loại backend, 
    # đường dẫn lưu trữ cục bộ và các tham số 
    # chunking như kích thước chunk, overlap,
    #  số chunk tối đa cho mỗi tài liệu, kích thước tải 
    # lên tối đa và các phần mở rộng tệp được phép cho quá trình ingestion.
    storage_backend: str = "s3"
    storage_local_path: str = "./data/uploads"
    object_storage_endpoint: str = "http://localhost:8333"
    object_storage_region: str = "us-east-1"
    object_storage_access_key: str = "admin"
    object_storage_secret_key: str = "secret"
    library_ebook_bucket: str = "library-private"
    library_temp_bucket: str = "library-temp"
    rag_artifact_bucket: str = "rag-artifacts"
    # Deprecated compatibility alias for deployments that still provide this setting.
    # Source ebooks are owned by the Library system and live in library-private.
    rag_source_bucket: str = "library-private"

    chunk_size: int = 512
    chunk_overlap: int = 64
    max_chunks_per_document: int = 1000
    max_pdf_pages: int = 3000
    pdf_text_sample_pages: int = 5
    pdf_min_avg_text_chars: int = 50
    max_upload_size_mb: int = 25
    ingestion_allowed_extensions: list[str] = Field(default_factory=lambda: [".pdf"])
    pdf_parser: str = "pymupdf4llm"
    pdf_cleaning_version: str = "pdf-clean-v1.0.0"
    pdf_clean_normalize_unicode: bool = True
    pdf_clean_collapse_spaces: bool = True
    pdf_clean_max_blank_lines: int = 2
    pdf_clean_header_footer_detection_enabled: bool = True
    pdf_clean_header_footer_top_lines: int = 3
    pdf_clean_header_footer_bottom_lines: int = 3
    pdf_clean_header_footer_min_repeat_ratio: float = 0.4
    pdf_clean_header_footer_removal_enabled: bool = False
    # Cấu hình cho quá trình truy vấn, 
    # bao gồm số lượng kết quả trả về 
    # từ vector search và keyword search,
    retrieval_top_k: int = 10
    reranker_top_k: int = 3
    hybrid_vector_weight: float = 0.7
    hybrid_keyword_weight: float = 0.3
    # Cấu hình cho quá trình chunking, bao gồm kích thước chunk mặc định,
    @property
    def effective_celery_broker_url(self) -> str:
        return self.celery_broker_url or self.redis_url
    # URL result backend hiệu quả cho Celery, 
    # sử dụng giá trị được chỉ định nếu có, 
    # hoặc fallback về Redis URL nếu không có cấu hình riêng cho result backend. Điều này giúp đảm bảo rằng Celery có một backend để lưu trữ kết quả của các tác vụ bất kể cấu hình cụ thể nào được cung cấp.
    @property
    def effective_celery_result_backend(self) -> str:
        return self.celery_result_backend or self.redis_url


@lru_cache
def get_settings() -> Settings:
    return Settings()
