# 17. CI/CD And DevOps

CI/CD cho RAG không chỉ chạy unit test rồi deploy. Vì RAG quality phụ thuộc vào chunking, retrieval, prompt, model và data, pipeline cần có **RAG regression test** để tránh deploy một thay đổi làm câu trả lời tệ đi.

Use case xuyên suốt:

> Internal Knowledge Base RAG cho công ty, gồm HR policies, technical documentation, product documents, FAQ, meeting notes, support tickets và internal wiki.

Trong project hiện tại, chương này map vào:

- `tests/`
- `app/evaluation/run_eval.py`
- `compose.yml`
- `compose.observability.yml`
- `infra/docker/`
- `pyproject.toml`
- Alembic migrations

## 1. CI/CD Pipeline Tổng Quan

Pipeline production nên có:

```text
Lint
  ↓
Unit test
  ↓
Integration test
  ↓
RAG evaluation test
  ↓
Build Docker image
  ↓
Security scan
  ↓
Push image
  ↓
Deploy staging
  ↓
Run smoke test
  ↓
Deploy production
  ↓
Rollback if needed
```

## 2. Vì Sao RAG Cần CI/CD Riêng?

Trong backend thường, tests kiểm tra logic deterministic.

RAG có thêm phần probabilistic:

- LLM output không hoàn toàn deterministic
- retrieval phụ thuộc embeddings/index
- prompt đổi làm answer đổi
- chunking đổi làm source IDs đổi
- model provider đổi hành vi

Vì vậy CI/CD cần kiểm tra:

- code đúng
- pipeline chạy được
- retrieval metrics không giảm
- hallucination không tăng
- latency/cost không vượt ngưỡng

## 3. Lint

Lint bắt lỗi style/basic bug.

Tools có thể dùng:

- Ruff
- Black
- mypy/pyright

Pipeline:

```text
poetry run ruff check .
poetry run ruff format --check .
```

Project hiện tại chưa thêm Ruff, nhưng có thể thêm sau.

## 4. Unit Test

Unit tests cho:

- chunkers
- text cleaner
- RRF fusion
- prompt builder
- config parsing
- security helpers
- repository methods với test DB nếu cần

Project hiện có:

- `tests/unit/test_chunkers.py`
- `tests/unit/test_retrieval.py`
- `tests/unit/test_prompt_builder.py`

CI:

```text
poetry run pytest tests/unit
```

## 5. Integration Test

Integration tests kiểm tra nhiều component cùng chạy:

- upload document -> ingestion job
- chunk -> embed mock -> index mock
- query -> retrieve -> generate mock answer
- DB transaction
- API endpoints

CI có thể dùng Docker services:

- PostgreSQL
- Redis
- Qdrant

Nếu quá nặng, chạy nightly hoặc pre-release.

## 6. RAG Evaluation Test

Đây là phần khác biệt.

Run:

```text
poetry run python -m app.evaluation.run_eval --dataset app/evaluation/datasets/golden.json
```

Metrics:

- Recall@5
- MRR
- faithfulness
- citation accuracy
- hallucination rate
- latency
- cost

### Gate rules

Không deploy nếu:

```text
Recall@5 giảm > 3%
MRR giảm > 5%
Faithfulness giảm > 5%
Citation accuracy < 80%
Hallucination rate tăng > 2%
Latency p95 tăng > 30%
```

## 7. Build Docker Image

Build one application image and run it with different commands for API and worker.

Project hiện có:

- `infra/docker/Dockerfile.api`

CI:

```text
docker build -f infra/docker/Dockerfile.api -t rag-app:${GIT_SHA} .
```

## 8. Security Scan

Scan:

- Python dependencies
- Docker image vulnerabilities
- secrets
- IaC configs

Tools:

- pip-audit
- Trivy
- Gitleaks
- Bandit

Pipeline should fail on critical vulnerabilities unless explicitly waived.

## 9. Push Image

Push to registry:

```text
ghcr.io/company/rag-api:{sha}
ghcr.io/company/rag-worker:{sha}
```

Also tag:

```text
staging
production
v1.2.3
```

## 10. Deploy Staging

Staging should be close to production:

- same services
- test secrets
- smaller data
- golden dataset available
- monitoring enabled

Deploy staging:

```text
run migrations
deploy API/worker
run smoke tests
run eval subset
```

## 11. Smoke Test

Smoke tests:

- `/api/v1/health`
- DB reachable
- Redis reachable
- Qdrant reachable
- Gemini/OpenAI smoke test maybe optional
- simple retrieval/generation test with seeded doc

Example:

```text
Upload sample HR doc
Wait ingestion completed
Ask "How many annual leave days?"
Assert answer contains expected citation
```

## 12. Deploy Production

Production deploy should:

- run DB migrations safely
- deploy API
- deploy workers
- verify health
- monitor errors/latency
- keep rollback image

### Important

If retrieval pipeline changes require re-index, do not deploy code blindly. Coordinate:

```text
build new vector collection
run eval
switch active collection
deploy code
```

## 13. Rollback

Rollback plan depends on change type.

### Code-only change

Rollback image.

### Prompt change

Switch prompt version config.

### Embedding model change

Switch active vector collection back.

### DB migration

Harder. Use backward-compatible migrations.

### Chunking strategy change

May need switch index version back.

## 14. GitHub Actions Concept

```yaml
name: ci

on:
  pull_request:
  push:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16
      redis:
        image: redis:7-alpine
      qdrant:
        image: qdrant/qdrant:latest

    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: "3.12"

      - name: Install Poetry
        run: pipx install poetry

      - name: Install dependencies
        run: poetry install

      - name: Run unit tests
        run: poetry run pytest tests/unit

      - name: Run integration tests
        run: poetry run pytest tests/integration

      - name: Run RAG eval subset
        run: poetry run python -m app.evaluation.run_eval --dataset app/evaluation/datasets/sample_eval_set.json

      - name: Build Docker image
        run: docker build -f infra/docker/Dockerfile.api -t rag-app:${{ github.sha }} .
```

Đây là concept, cần hoàn thiện env/secrets/ports khi triển khai thật.

## 15. Không Deploy Nếu Evaluation Giảm

Đây là rule production rất quan trọng.

Ví dụ PR đổi chunking strategy:

```text
Before:
Recall@5 = 0.86
Faithfulness = 0.89

After:
Recall@5 = 0.72
Faithfulness = 0.81
```

CI phải fail.

Không nên deploy vì "code test pass". RAG quality đã tụt.

## 16. Không Deploy Nếu Hallucination Rate Tăng

Nếu hallucination rate tăng:

```text
Before: 4%
After: 9%
```

Fail deployment.

Nguyên nhân có thể:

- prompt đổi
- retrieval noisy
- compression bỏ mất context
- model đổi

## 17. Không Deploy Nếu Retrieval Recall Giảm

Retrieval recall giảm thường là early warning.

Nếu expected chunks không còn trong top-k, generation sẽ khó đúng.

Fail nếu:

```text
Recall@5 giảm quá threshold
MRR giảm mạnh
```

## 18. Environment Promotion

Pipeline:

```text
dev
  ↓
staging
  ↓
production
```

Config differs:

- model
- vector collection
- DB
- secrets
- rate limits

But code/image should be same.

## 19. DevOps Runbooks

Runbooks cần có:

- deploy API
- deploy worker
- run migration
- rollback
- restore backup
- re-index collection
- rotate secrets
- handle vector DB down
- handle queue backlog

## 20. Production Checklist

- CI runs unit tests.
- CI runs integration tests.
- CI runs RAG eval subset.
- CI compares metrics against baseline.
- Docker images built reproducibly.
- Security scan exists.
- Secrets not in repo.
- Migrations are part of deploy plan.
- Staging deploy before production.
- Smoke tests after deploy.
- Rollback plan exists.
- Prompt/model/index versions tracked.
- Eval report archived per build.
- Deployment blocked on quality regression.

## 21. Tóm Tắt Chương

CI/CD cho RAG phải kiểm tra cả code quality và answer quality. Một deploy tốt không chỉ là server chạy, mà là:

```text
server chạy
retrieval không tụt
hallucination không tăng
citations còn đúng
latency/cost trong budget
```

Chương tiếp theo sẽ đi sâu vào testing strategy: unit, integration, parser, chunking, embedding, retrieval, reranking, prompt, load, security và prompt injection tests.
