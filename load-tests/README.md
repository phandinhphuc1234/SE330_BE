# Load-test guide

`book-cache-test.js` is the quick, repeatable baseline for the public book-detail
endpoint. It accepts the target and a known existing book ID from environment
variables; it does not create or mutate data.

```powershell
k6 run --vus 20 --duration 20s `
  -e BASE_URL=http://127.0.0.1:18090 `
  -e BOOK_ID=3105 `
  load-tests/book-cache-test.js
```

The threshold is fewer than 1% failed requests and p95 below 300 ms. Run this
against a warmed local/demo stack only; record the service port, book ID, k6
version, duration and machine context with the result. `book-detail-baseline.js`
and `book-detail-stress.js` remain staged tests for a dedicated non-production
environment; do not run the 3,000-VU stress scenario against a shared demo DB.
