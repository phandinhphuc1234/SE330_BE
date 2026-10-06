# Bản vá dependency RAG — 2026-10-06

## Phạm vi và nguyên nhân

Run Backend CI/CD `37474831282` tại commit `5872d1b` dừng trước deploy:
Trivy phát hiện 62 lỗi có bản vá (58 HIGH, 4 CRITICAL) trong image RAG.
Đây là số finding, không phải 62 thư viện: một thư viện có thể có nhiều CVE,
và thư viện vendored có thể bị phát hiện riêng.

Không đổi thuật toán retrieval, chunking v1, model Gemini, API contract,
database hoặc dữ liệu ebook. Không tắt quality/security gate.

## Thay đổi

Các phiên bản dưới đây là phiên bản được resolve trong `rag-service/poetry.lock`:

| Dependency | Phiên bản mới |
| --- | --- |
| FastAPI | 0.141.1 |
| Starlette | 1.7.0 |
| aiohttp | 3.14.4 |
| anyio | 4.15.1 |
| cryptography | 50.0.2 |
| fsspec | 2026.7.0 |
| lxml | 6.1.3 |
| LangSmith | 0.8.18 |
| nltk | 3.10.3 |
| Pillow | 12.3.0 |
| pyasn1 | 0.6.4 |
| pypdf | 6.19.0 |
| setuptools | 84.0.0 |
| urllib3 | 2.8.0 |

- Đặt security floor trong nhóm `main` của `pyproject.toml`, để image cài
  `--only main` vẫn nhận các bản vá; lockfile chốt phiên bản và hash artifact.
- Giữ FastAPI trước dòng 0.142 để không đưa thay đổi OpenTelemetry vào bản vá
  này. FastAPI và Starlette phải được kiểm tra lại cùng nhau bằng regression test.
- Nâng `datasets` trong nhóm `eval` lên 5.1.0: ràng buộc cũ giới hạn `fsspec`
  của lockfile dùng chung ở 2025.3.0. Nhóm `eval` không được cài trong image runtime.
- Bỏ `pip` của Python base image và bản pip được seed trong virtualenv ở stage runtime.
  Production không cài package lúc chạy; các thư viện vendored của công cụ build
  (bao gồm jaraco.context/wheel/urllib3) không cần tồn tại trong stage này.
  Pip của virtualenv 26.2.1 vẫn bundle urllib3 2.7.0 dù ứng dụng đã dùng 2.8.0;
  security floor của lockfile không nâng được bản vendored này. Builder vẫn có
  pip/Poetry để tạo virtualenv. Không xóa package ứng dụng.
- Quét lại với vulnerability database mới phát hiện thêm LangSmith cần >=0.8.18.
  Nâng riêng dòng 0.8.x; không bật tracing. Bỏ setuptools/wheel toàn cục của base
  image (build-only) vì chúng bundle jaraco.context/wheel cũ. Setuptools 84.0.0
  của ứng dụng vẫn giữ nguyên trong virtualenv, bao gồm các bản vendored đã vá.

## Cách kiểm chứng

1. `poetry check --lock`: khai báo và lockfile đồng bộ.
2. Python 3.11: chạy toàn bộ pytest với dependency `main,dev` mới trong môi trường
   không chứa `.env`/provider key. Kiểm tra web, parser, cleaning, chunking,
   retrieval, citation/abstention và evaluation bằng fixture/mocks.
3. Build **runtime image** từ `infra/docker/Dockerfile.api`; smoke test import,
   health endpoint và quyền user không-root. Quét image thực tế, không chỉ lockfile.
4. Trivy giữ `severity=HIGH,CRITICAL`, `ignore-unfixed=true`, `exit-code=1`,
   giống pipeline hiện tại. Kết quả 0 ở gate này không có nghĩa là image không còn
   lỗi LOW/MEDIUM hoặc lỗi chưa có bản vá.
5. PR phải qua Java/Python tests, CodeQL và Sonar. Sau merge, CI build/push image
   theo digest, scan lại rồi mới deploy qua workflow có backup/migration/health check.

Test offline không chứng minh production đã có tài liệu/vector. Khi RAG được
khởi tạo trên VPS, vẫn phải upload/index PDF demo và kiểm tra câu trả lời có citation.

## Nguồn

- [Pipeline bị chặn](https://github.com/phandinhphuc1234/SE330_BE/actions/runs/37474831282).
- [FastAPI release notes](https://fastapi.tiangolo.com/release-notes/).
- [Starlette release notes](https://www.starlette.io/release-notes/).
- [LangSmith security advisory](https://github.com/langchain-ai/langsmith-sdk/security/advisories/GHSA-f4xh-w4cj-qxq8).

Flow: dependency floors → lockfile → tests → runtime image → Trivy → PR merge
→ immutable images → deploy Azure → smoke test backend/frontend.
