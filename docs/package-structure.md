# Backend package structure

The Spring Boot backend is organized by business feature under `com.vn`:

```text
com.vn
├── auth
├── book
├── ebook
├── loan
├── member
├── notification
├── payment
├── rag
└── shared
```

`QuanLyThuVienApplication` stays directly in `com.vn` so Spring can scan every
feature package below it.

## Package ownership

| Package | Responsibility |
| --- | --- |
| `auth` | Authentication, JWT, password management, email verification and security configuration |
| `book` | Authors, categories, physical books, copies, reviews, cover images and catalog imports |
| `ebook` | Ebook metadata, lending, reading sessions and object storage |
| `loan` | Physical circulation, holds, fines, renewals and scheduled circulation jobs |
| `member` | Member profiles and staff-managed account status |
| `notification` | Notification queue, delivery workers, email provider integration and webhooks |
| `payment` | Payment transactions, provider adapters, callbacks and receipts |
| `rag` | Spring-side RAG client configuration and ebook ingestion integration |
| `shared` | Cross-cutting API contracts, exceptions, logging, idempotency, common configuration and shared storage abstractions |

## Internal feature layout

Each feature keeps its existing technical roles and DTO boundaries. A feature
may contain the following subpackages when applicable:

```text
<feature>
├── config
├── controller
│   └── docs
├── dto
│   ├── request
│   └── response
├── entity
├── enums
├── mapper
├── repository
├── scheduler
├── service
│   └── impl
└── storage
```

Not every feature must contain every subpackage. Existing specialized folders,
such as importer, provider, webhook, or projection packages, remain nested under
the feature that owns the behavior.

## Dependency rules

- Keep business code in the package of the feature that owns it.
- Preserve separate request and response DTOs; controllers must not expose JPA
  entities as API contracts.
- Keep mapping logic in mapper classes instead of controllers.
- Use `shared` only for code reused across features or infrastructure concerns;
  feature-specific business rules do not belong there.
- Cross-feature dependencies should target public services or explicit DTOs,
  not another feature's controller.
- The Python RAG application remains an independently deployable service in
  `rag-service`; `com.vn.rag` contains only the Spring integration boundary.
