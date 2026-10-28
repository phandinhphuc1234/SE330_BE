# Private production PDF delivery - 2026-10-28

Status: implementation and local tests complete; CI/deployment/browser acceptance pending.

## Root cause and delivery contract

The existing SeaweedFS presigner used a loopback public endpoint. A browser
request to `127.0.0.1:8333` targets the reader's own computer, not the VPS.
The fix does not expose SeaweedFS, make its bucket public, or put JWT/session
tokens in query parameters.

`GET /api/ebooks/{bookId}/reader/content` keeps its existing fields and adds
`requiresAuthorization`. For SeaweedFS it returns the relative fixed route
`/api/ebooks/{bookId}/reader/file` with `requiresAuthorization=true`. Legacy
Cloudinary delivery retains a signed URL and false. Frontend must only attach
credentials to the locally constructed fixed API route, not arbitrary URLs in
an API response. Deploy the compatible frontend before the backend change.

Every file GET/HEAD checks the JWT, MEMBER role, reading-session owner/book,
session expiry and live loan. Storage key/bucket must match the authorized ebook.
The backend streams private S3 bytes, including one bounded byte range, with
known length, `application/pdf`, inline disposition and private `no-store`.
HEAD opens no body stream; malformed/multiple/unsatisfiable ranges return 416.
If-Range conservatively falls back to a full response. No storage errors,
private endpoint, bucket/key or signed storage URL is exposed to the client.

S3 HEAD and GET use a bounded call timeout; GET pins the HEAD ETag with If-Match
and validates response length. The HTTP converter owns and closes the stream.
No PDF is buffered completely in backend memory. A PDF already downloaded to a
browser cannot be revoked retroactively; subsequent file requests reauthorize.

## Local evidence

- Focused Java tests: 44 passed (controller/security chain, session, content/range,
  storage), no failures/errors/skips.
- Full `mvn verify`: **360 tests**, no failures/errors/skips, BUILD SUCCESS.
  PostgreSQL/Redis Testcontainers ran. Local Surefire warned about a late JVM
  shutdown; this is recorded, not presented as a clean shutdown measurement.
- Frontend transport tests cover no credential forwarding, same-book fixed path,
  no-store/redirect rejection, bounded 401 retry and legacy Cloudinary delivery.
- Public smoke now checks anonymous/missing/cross-book/closed-session PDF denial,
  range, HEAD, invalid range, corpus checksum and citation page bounds. Its
  automated tests do not constitute a live production smoke.

## Deployment and acceptance gates

The owner approved separate PRs, push, merge/deployment after CI passes and
frontend dependency patches after audit identified existing vulnerabilities.
Keep unrelated local edits and PRs #39/#40 separate. No new secrets/env fields,
new public ports, bucket-policy changes or PDF re-upload are required.

- [ ] PR CI and CodeQL/Sonar pass for the exact backend revision.
- [ ] Frontend reader tests/lint/build and high/critical audit gate pass.
- [ ] Retain old backend/RAG immutable digests and Vercel deployment for rollback.
- [ ] Merge/deploy only the approved reader changes, with image scans passing.
- [ ] MEMBER opens the unchanged PostgreSQL PDF; verify 22 pages and checksum.
- [ ] Positive file/range/HEAD and authenticated negative smoke pass in production.
- [ ] Browser Ask/citation/abstain and frozen held-out evaluation run separately;
  preserve quota errors and never rename lexical answer coverage as correctness.
