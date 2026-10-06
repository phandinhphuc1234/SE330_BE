# Chẩn đoán baseline tiếng Việt: headings, ranking và context

Cập nhật 2026-10-06. **Đã chẩn đoán, chưa sửa chunker/ranking production.**
40 case-version pairs, replay đủ 120 baseline cases exact, 0 errors,
0 Gemini/provider calls, 0 LLM generation, 0 DB/Qdrant writes. Giữ HOLD v1.
Đây là development/regression data đã quan sát, không phải blind holdout mới.

## 1. Đã làm gì?

Thêm [diagnose_vietnamese_baseline.py](../../scripts/diagnose_vietnamese_baseline.py),
chỉ dùng PDF checksum-pinned và vectors thật đã có. Không chỉnh thuật toán:

1. Kiểm tra sealed dataset, owner receipt và completed cache-only baseline;
   từ chối reference thiếu review, lỗi, khác source/model/config hoặc có writes.
2. Parse/clean/chunk bằng đường production v1/v2 hiện tại; audit exact source spans.
3. Bắt buộc đủ document/query cache; missing/corrupt cache là lỗi, không provider fallback.
4. Chấm lại 20 câu × 2 chunkers × 3 modes và so sánh **toàn bộ version payload**,
   gồm metrics, citations, context IDs/characters và source/chapter audits, với report 28.
5. Trace 40 actual Hybrid requests, mỗi request chạy thêm một lần không trace;
   response phải giống hệt. Quan sát Dense, hai BM25 variants, RRF, reranker,
   threshold và final top-5. Nhãn evidence chỉ được gắn **sau** ranking.
6. Với từng anchor: xác định chunks giữ toàn bộ/một phần quote, cached cosine
   rank, BM25 rank/term contributions và seed/context-ID membership.
7. Đối chiếu 7 reviewed headings với detector thực tế; nhóm các section mismatches.
8. Kiểm tra lại seals/source/reference rồi ghi NEW private JSON/Markdown.

Private/ignored artifacts:

- `data/chunking-comparison/vietnamese-baseline-diagnostics-29.json/.md`: initial diagnostic run.
- `data/chunking-comparison/vietnamese-baseline-diagnostics-30.json/.md`: final run sau khi siết reference config/dataset guards.
- Reference: `data/chunking-comparison/vietnamese-source-cache-replay-28.json`.

Hai runs có `versions` payload khớp exact; không ghi đè artifacts cũ. Logs có
`db_async_engine_configured` vì common app imports khởi tạo app config và
SQLAlchemy engine. Đó **không phải DB connection/query**: runner inject local
candidate/vector/neighbor sources, không dùng DB sessions, không tạo Gemini client.
Embedding identity dùng public pinned settings, không lấy key từ `.env`, không
serialize runtime Settings/secrets và không thay `.env`.

## 2. Lỗi section đã xác định cụ thể

| Chỉ số | v1 | v2 |
| --- | ---: | ---: |
| Reviewed headings được detector nhận diện | 0/7 | 2/7 |
| Section label mismatches / audited chunks | 97/97 | 30/94 |
| Chunks trộn nhiều reviewed sections | 5 | 3 |
| Non-whitespace source coverage | 100% | 100% |

v1 không bóc Markdown `#`/`**` trước khi match chapter regex. Sáu reviewed
headings có Markdown wrapper. Riêng `Phần I...` ở dòng không rỗng thứ 7 và
`Phần II...` ở dòng thứ 5, đều nằm trong scan limit 8: ở đây không thể kết luận
chỉ cần tăng scan window. v1 không nhận được heading và các audited chunks có
`chapter_title=None`; mismatch=100% **không đồng nghĩa mất 100% source text**.

v2 bóc được Markdown và nhận đúng `Phần I...` / `Phần II...`, nhưng chỉ hỗ trợ
numbered chapter + một số unnumbered English labels. Các headings chưa nhận:

| Reviewed heading | Trang PDF | Dòng không rỗng | v2 |
| --- | ---: | ---: | --- |
| Giới thiệu | 9 | 3 | Không nhận |
| Các chính sách cho thời gian tới | 25 | 9 | Không nhận |
| Tham khảo | 28 | 1 | Không nhận |
| Chú giải | 29 | 2 | Không nhận |
| Với sự hỗ trợ của: | 32 | 1 | Không nhận |

v2 quét mọi dòng nên vị trí dòng 9 không giải quyết được thiếu heading pattern.
Khi không thấy boundary mới, active chapter được carry qua các trang liên tục:
**17 mismatched chunks** còn mang `Phần II...` trong phần chính sách/tham khảo/
chú giải; **13** mismatch khác có actual title `None` (intro, support và mixed
prefix/intro). Vì thiếu boundary, còn 3 chunks trộn sections theo reviewed oracle.
Oracle chỉ dùng để chẩn đoán, không được nạp vào chunker để làm đẹp benchmark.

## 3. Source có bị mất hoặc quote bị cắt không?

**23/23 anchors ở mỗi chunker** đều nằm trọn trong ít nhất một chunk. Không có
anchor nào trong bộ này buộc phải ghép hai chunks mới đủ quote. Cùng source
coverage=100%, đây là bằng chứng các case fail hiện tại chủ yếu là **ranking/
cutoff/context selection**, không phải evidence quote bị mất hoặc thiếu overlap.
Điều này không chứng minh mọi ebook/chunk boundary đều tốt; chỉ áp dụng bộ đang đo.

MRR relevance là >=50% ký tự của ít nhất một anchor; Recall@k đòi hỏi **đủ toàn
bộ anchor**, có thể tính union top-k chunks. Context retention còn chặt hơn:
anchor phải đủ trong một citation và text thực sự còn sau compression. Chỉ thấy
ID trong context không đủ để kết luận retention=1.

## 4. Những case cần hiểu

### A. `vi-poverty-rate`: evidence còn nguyên, đối thủ đổi khiến rank tụt

Evidence nằm ở PDF 9, 166 non-whitespace characters, trong chunk 952 characters:
v1 chunk index 20 / v2 index 18. Cả hai chứa đủ 100% anchor.

Đã kiểm tra riêng exact SHA256:

- Chunk text: `f45f6c09282bee708313638bd31e4c678993e8ab8d55e726f9affa32c5d5ddff`.
- Provider-facing embedding input: `dc99586f2cbd11dbee2e9c83a4f093e04e2dfe66df7445a02bf3888bbce9e061`.

| Đại lượng | v1 | v2 |
| --- | ---: | ---: |
| Evidence cosine | 0,7538165704 | 0,7538165704 |
| Dense rank | 3 | 4 |
| BM25 original/focused rank | 8/8 | 8/8 |
| RRF rank → reranker/final rank | 3 → 3 | 5 → 4 |
| Dense/Hybrid Recall@3 | 1 | 0 |
| Dense/Hybrid context retention | 1 | 0 |

v2 có distractor từ PDF 29 đạt cosine **0,7608170514**, cao hơn evidence đúng;
trong v1 top-5, chunk PDF 29 đạt **0,7375204326**. Candidate corpus/chunk metadata
đã đổi khi chuyển chunker, không phải evidence vector bị hỏng. Evidence hạng 4
vẫn trong returned top-5, nhưng context lấy **top-3 seeds**, nên nó không phải seed;
context của các seeds thực tế cũng không giữ anchor PDF 9.

Không suy luận riêng heading metadata hay page merge là nguyên nhân duy nhất
cho distractor tăng cosine: text/context prefix cùng thay đổi, chưa có causal
ablation. Không tự tăng k/window hoặc sửa weights sau khi xem câu này.

### B. `vi-high-income-goal`: RRF đưa lên hạng 3, reranker đẩy xuống 4

Cả v1/v2: Dense rank **4**, BM25 original **4**, focused variant **2**;
RRF rank **3** → reranker rank **4** → final rank **4**. Đây là top-3 loss tại
reranking, không phải evidence biến mất khỏi candidate pool.

Ở v1, evidence reranker score **0,814196**; distractor ở hạng 3 đạt **0,823642**.
Hai chunks có cùng lexical coverage **0,517241**. Evidence có RRF component
**0,387469** cao hơn distractor **0,384693**, nhưng cosine component thấp hơn
**0,375002 vs 0,387225**, nên tổng điểm bị vượt. Formula vẫn 0,4 RRF +0,5 cosine
+0,1 lexical, chưa đổi.

Recall@3=0, MRR@5=0,25, nhưng Dense/Hybrid **context retention=1** ở cả hai:
neighbor expansion phục hồi evidence. Không có answer/judge score để kết luận
LLM đã trả lời đúng. `firstTop3LossStage` của generic trace chỉ nhận một số losses
so với Dense→final; case này cần đọc `stageSummary/evidenceRankPath`, không coi
flag `None` là “không có mất hạng ở mọi stage”.

### C. `vi-not-poor-not-secure`: BM25 thực sự bổ trợ Dense

Evidence PDF 26: Dense rank **9 → 14** khi chuyển v1→v2, nhưng BM25 cả hai
variants rank **1**. Hybrid đi **Dense 9 → RRF 3 → final 2** với v1;
**Dense 14 → RRF 4 → final 3** với v2. Recall/context của Hybrid đều=1,
trong khi Dense-only đều=0. Đây là gain thật của lexical branch; v2 MRR vẫn
giảm 0,5→0,3333. Không nên bỏ BM25 chỉ vì có một số noisy lexical matches.

### D. `vi-digital-two-domains`: hai evidence, fusion làm mất một seed

Câu này cần anchors PDF 26 **và** 27. Với v1:

- PDF 26 evidence: Dense rank **3**, BM25 original **25**, focused **22**;
  ngoài BM25 candidate top-15, chỉ được Dense hỗ trợ. RRF rank **7** và reranker
  vẫn **7**, bị final top-5 loại. Đây là loss tại fusion và cutoff, không phải
  reranker đẩy 3→7 trong case này.
- PDF 27 evidence: Dense/RRF/final đều rank **2**, được BM25 hỗ trợ ở rank 7/6.
- Dense Recall@3=1, Hybrid Recall@3=0,5. Neighbor expansion vẫn phục hồi anchor
  bị mất, nên Hybrid context retention=1.

v2 cải thiện case này: hai evidence có Dense ranks **1/3**, BM25 original **2/8**,
Hybrid final ranks **2/3**, Recall@3=1 và context=1. Gain này không bù regression
`vi-poverty-rate` để tự promote v2.

### E. `vi-mtqg-investment`: semantic rank giảm, Hybrid bù lại

Dense evidence rank v1 **1** (cosine 0,730098) → v2 **2** (0,707567).
BM25 vẫn rank1; Hybrid final vẫn rank1 ở cả hai. Không phải mất evidence,
nhưng Dense MRR giảm 1→0,5. MRR và Recall phản ánh hai điều khác nhau.

## 5. Hướng tiếp theo, theo đúng nguyên nhân

Ưu tiên một **experiment/version v3 riêng cho heading/section detection**:

1. Nhận structural Markdown headings/unnumbered Vietnamese section headings
   một cách bảo thủ; không hardcode 7 titles/case IDs từ benchmark.
2. Test false positives: TOC, table/list, prose, quoted dialogue, fenced code,
   title blocks và heading giữa trang. Giữ source spans/coverage/citation invariants.
3. Giữ nguyên v1/v2 và sealed reports; đo trước heading/source/mixed-section
   audits bằng offline data. Sửa metadata không có nghĩa retrieval tự tốt hơn.
4. Embedding text builder có chapter/section/page prefix: metadata mới sẽ tạo
   input/cache identity mới. Muốn chấm Dense/Hybrid v3 cần preflight cache/budget
   và **quyền provider riêng**, không tái dùng vector cũ sai text identity.
5. Replay cả English corpus cũ và Vietnamese regression set, sau đó thêm fresh
   source/query labels review trước scoring. Chỉ xem promotion khi đủ gates.

Reranker/multi-evidence fusion là experiment riêng sau đó nếu còn lỗi; không
vừa sửa boundaries vừa tune weights để không biết thay đổi nào tạo gain/loss.
Chưa cần semantic chunking, GraphRAG, LLM rewriting hoặc tăng overlap mù.

## 6. Chạy và verification

Từ `rag-service`; source PDF, real cache và reference report 28 cần tồn tại tại
ignored folder. Không commit PDFs, vectors, parsed text hoặc raw private reports.

~~~powershell
.\.venv\Scripts\python.exe -m scripts.diagnose_vietnamese_baseline --output data/chunking-comparison/my-new-diagnostics.json
~~~

Không có cờ gọi Gemini hoặc generation. Output JSON/Markdown phải chưa tồn tại;
missing reference/cache/checksum/review/drift làm fail, không ghi passing report.

[27 tests mới](../../tests/unit/test_vietnamese_baseline_diagnostics.py) kiểm tra
reference identity/config/provenance guards, actual heading detector behavior,
partial/full anchor attribution, zero-score BM25 ranks, cache fail-closed,
replay drift, no-overwrite và raw-error redaction. Unit vectors là fake fixtures
trong temporary tests, không được đưa vào benchmark cache thật.

- Narrow related suites: **88 passed**, 29,40s.
- Final full RAG suite: **777 passed, 10 existing Pydantic warnings**, 53,94s.
- Final real diagnostic run: **40 pairs, 120 exact baseline replay cases,
  0 errors/provider calls**, traced/untraced response invariance 40/40.
- 171 local documentation links hợp lệ; 12 scoped text files sạch trailing
  whitespace. Source/manifest/receipt/first-23/replay-24/failed-25 hashes giữ nguyên;
  reference-28 hash khớp diagnostic report. Artifacts vẫn ignored/private.
- Chưa chạy Spring/Next build, deployment, production load/latency hay LLM
  answer faithfulness/abstention evaluation. Chưa promote/reindex hoặc commit/push.

Flow/purpose: baseline cache thật → unchanged replay → source/heading/rank/context
diagnostics → phân biệt data loss với ranking/cutoff losses → hướng experiment
riêng. Mục đích là sửa đúng tầng dựa trên bằng chứng, không đoán hoặc tune trên
known-case scores rồi claim production quality.
