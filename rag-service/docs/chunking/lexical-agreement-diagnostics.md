# Lexical agreement — khi BM25 giúp và khi gây nhiễu

Cập nhật: 2026-10-06. Follow-up của
[fixed policy comparison](hybrid-policy-comparison.md). **Scoring production chưa đổi.**

## 1. Bước này giải quyết vấn đề gì?

Experiment trước chưa chọn được policy: ưu tiên cosine cứu Douglass nhưng làm
Magi mất evidence. Bước này đọc **từng token thực sự khớp**, điểm BM25, coverage
và đường đi của evidence để hiểu tradeoff, không thử thêm weights sau khi xem
đáp án. Phân tích tất cả 40 questions × 2 chunkers = **80 cases**, không chỉ bốn
câu được chọn để minh họa. Hai English PDFs cũ; bộ queries Việt đã được quan sát
nên hiện dùng làm regression set, không còn là blind test.

## 2. Đã implement

- [Corpus profile](../../app/evaluation/retrieval_diagnostics.py): tái dùng
  production BM25 scorer để lấy positive ranks; giải thích TF/DF/IDF/contribution
  cho **mọi chunk**, cả score=0/rank=null. Kiểm tra tổng term contributions khớp
  scorer với tolerance 1e-12. Diagnostic cũ giữ output positive-rank semantics.
- [Lexical diagnostic](../../app/evaluation/lexical_agreement_diagnostics.py):
  kiểm tra ranks/raw scores của từng BM25 rewrite và Dense trong actual trace,
  rồi mới annotate source anchors. Không có classifier, routing, filtering hoặc
  weights mới. Features được tính trước labels; labels chỉ chọn/đánh dấu rows.
- [Runner](../../scripts/diagnose_lexical_agreement.py): checksum/source/manifest/
  reference/cache guards; replay baseline hybrid metrics/context/citations ở mọi
  case; actual pipeline có/không trace phải trả response giống nhau. Chỉ đọc cache
  vectors thật; thiếu thì dừng, không gọi Gemini ngầm. JSON/MD chỉ tạo mới.
- [40 unit tests](../../tests/unit/test_lexical_agreement_diagnostics.py): scorer
  parity, zero rows, query frequency, DF=0, title/body ambiguity, label/input
  invariance, branch-score/order drift, synthetic end-to-end runner, baseline
  guards, no cache fallback, no clobber và không export key/raw source text.

### Features có ý nghĩa gì?

| Field | Cách tính / cách đọc |
| --- | --- |
| `queryTokenCoverage` | Số distinct query tokens có TF>0 / tổng distinct tokens của branch |
| `idfWeightedQueryCoverage` | Tổng IDF của matched distinct tokens / tổng IDF mọi distinct query token |
| `corpusMatchableTokenFraction` | Số distinct query tokens có DF>0 / tổng distinct query tokens |
| `matchedTitleTerms` | Matched tokens cũng thuộc token set của scoped book title |
| `matchedNonTitleTerms` | Matched tokens không thuộc title token set; **không tự động là từ hữu ích** |
| `titleContributionFraction` | Phần raw BM25 score do title-set tokens đóng góp |
| `bm25CorpusRank` | Rank trên toàn corpus scope; null nếu BM25 không phát ra positive result |
| `stageRanks` | Ranks thực tế trong Dense/BM25 top-15, RRF, reranker, final top-5; null = không ở stage |

Focused branch là variant cuối cùng của **QueryRewriter hiện có**, không thêm
translator/stemmer hay rewrite dựa trên expected answer. Có một variant thì
original chính là focused branch. BM25 giữ query-term frequency; coverage dùng
distinct tokens để từ lặp không làm sai tỷ lệ. Reranker runtime vẫn dùng lexical
coverage của original query, **không** dùng các IDF/title features mới này.

DF=0 vẫn ở denominator của IDF coverage: cho thấy exact spelling không match
corpus, không chứng minh query kém hoặc Dense không hiểu. Title-set overlap không
cho biết token xuất hiện ở title/header hay body. Không inject title vào BM25;
không strip title words khỏi chunks. Các tỷ lệ này không phải semantic confidence
và raw BM25 scores không so ngang hai queries/corpora một cách máy móc.

## 3. Kết quả toàn bộ cases

Run `lexical-agreement-19`: **80 diagnostics**, baseline hybrid replay 80 cases
khớp mọi case fields/cleaned page hashes của reports 15/16, errorCount=0,
provider calls=0. Mỗi diagnostic còn chạy traced/normal response equality.
TopK=5, candidateK=15, RRF k=60, Dense/keyword=0.70/0.30, reranker=0.40/0.50/0.10;
context seeds/window/budget=3/1/12000 characters, tất cả giữ baseline.

Replay `lexical-agreement-replay-20`: toàn bộ report bằng run 19 sau khi bỏ
`generatedAt`, gồm 80 cases, term features, stage ranks, evidence metrics,
source/cache/reference identities và segment summaries. Không provider calls.
Final full RAG suite: **623 passed, 10 warnings**, 14.89s; tăng 40 tests so với
bước trước. Warnings là Pydantic alias warnings cũ. Reviewed v1 snapshot 11
cases vẫn khớp. Private reports JSON/MD ở ignored data folder; không commit.

| Query set | Book | Chunker | Hybrid Recall@3 tăng / giảm / bằng Dense | Focused BM25 top1 chỉ match title-set tokens | Không có focused BM25 result |
| --- | --- | --- | --- | ---: | ---: |
| English | Magi | v1 | 1 / 0 / 9 | 0 | 0 |
| English | Magi | v2 | 1 / 0 / 9 | 0 | 0 |
| English | Douglass | v1 | 0 / 2 / 8 | 0 | 0 |
| English | Douglass | v2 | 0 / 2 / 8 | 0 | 0 |
| Việt | Magi | v1 | 0 / 0 / 10 | 0 | 1 |
| Việt | Magi | v2 | 0 / 0 / 10 | 0 | 1 |
| Việt | Douglass | v1 | 0 / 1 / 9 | 3 | 0 |
| Việt | Douglass | v2 | 0 / 2 / 8 | 3 | 0 |

Tổng **2 tăng, 7 giảm, 71 bằng**; hai gains là cùng case `sold-watch` ở v1/v2,
không phải hai câu hỏi mới độc lập. Đây là delta của **toàn hybrid pipeline**
so với Dense, không phải chứng minh mỗi thay đổi chỉ do BM25 gây ra. Recall là
source-anchor recall, không phải tỷ lệ câu trả lời LLM đúng.

## 4. Bốn ví dụ cụ thể, v2

### 4.1 Jim bán đồng hồ — lexical agreement hữu ích

`sold-watch`: focused tokens `jim get money to buy della's combs`. Evidence p9:

- Dense corpus rank **7**, cosine **0.679037**.
- BM25 rank **1** ở cả original/focused; focused raw score **7.238174**.
- Match **6/7 tokens**, IDF coverage **69.04%**, title contribution **0%**.
- `get` và `money`: mỗi token TF=1, DF=1/9, mỗi token đóng góp **27.20%**.
  `buy` đóng góp 15.05%, `combs` 19.87%; không chỉ match tên nhân vật.
- RRF rank **6** → reranker/final rank **2**; full anchor được giữ ở top-3.
  Dense rank-1 p8 không chứa anchor: match 3/7 tokens, IDF coverage **18.98%**.

BM25/hybrid có giá trị trong case này. Không vì lỗi Douglass mà bỏ hoàn toàn
lexical retrieval. Token `della's` có DF=0, nhưng evidence vẫn được BM25 tìm đúng;
đó là tokenizer possessive limitation, không phải lý do sửa labels.

### 4.2 Hai sự hy sinh — nhiều evidence và cutoff vẫn quan trọng

`two-sacrifices` cần hai anchors, không chỉ một relevant chunk:

| Evidence | Dense rank | Focused BM25 rank / score | IDF coverage | RRF → reranker |
| --- | ---: | --- | ---: | --- |
| Della bán tóc, p7 | 3 | 2 / 4.750695 | 25.30% | 2 → 1 |
| Jim bán đồng hồ, p9 | 6 | 1 / 5.598632 | 41.33% | 4 → 6 |

Hai rows đều match 6/10 focused tokens. `sold` đóng góp 49.97% score p7; ở p9,
`each` đóng góp 35.16% và `sold` 25.69%. Baseline top-3 recall chỉ **0.5**.
IDF coverage cao hơn **không đảm bảo** evidence được giữ qua reranker/cutoff.
Đừng nói case này đã giải quyết đủ hai anchors. Policy fusion-Dense-85 trước đó
làm first relevant rank 1→2 nhưng recall vẫn 0.5; đó là MRR loss, không mất thêm
anchor. Features mới chưa chữa multi-evidence selection/context.

### 4.3 Tuổi Douglass — name/title-only agreement gây nhiễu

`vi-age-estimate`: focused branch có 12 distinct tokens, chỉ `frederick` và
`douglass` có DF>0 (**2/12 = 16.67%**): DF=65/128 và 71/128.

- BM25 top1 p1 raw score **2.623115**, title contribution **100%**;
  match 2/12 tokens, IDF coverage chỉ **2.23%**; không có evidence anchor.
- Hybrid top1 p4 cũng chỉ match hai tên trên; BM25 rank2, raw **2.533139**,
  cosine **0.709495**. Đây không phải match ý nghĩa “ước tính bao nhiêu tuổi”.
- Evidence thật p19–20: cosine **0.754841**, Dense rank **1**,
  **BM25 score=0/rank=null** ở cả branches → RRF/reranker rank **6**,
  không còn trong final top5.

Tên riêng giúp định vị tác phẩm nhưng không đủ tìm sự kiện khi request đã scoped
theo ebook. Báo cáo giữ row score=0 để failure này không biến mất khỏi diagnostic.

### 4.4 Giáo viên của Douglass — bỏ title tokens là chưa đủ

`vi-regular-teacher`: 16 distinct focused tokens, chỉ `master`, `hugh`, `douglass`
có DF>0 (**3/16 = 18.75%**), lần lượt DF=58, 12, 71 trên 128 chunks.

| Row | Dense rank / cosine | Focused BM25 rank / score | Matched tokens | IDF coverage |
| --- | --- | --- | --- | ---: |
| BM25 top1 p101–102, không anchor | 11 / 0.689218 | 1 / 5.075510 | master, hugh | 4.12% |
| Hybrid top1 p66–67, không anchor | 5 / 0.701091 | 15 / 1.896925 | master, douglass | 1.82% |
| Evidence p50–51 | 1 / 0.740656 | 36 / 1.298176 | master, douglass | 1.82% |

BM25 top1 p101–102 có **0%** title contribution, nhưng vẫn không trả lời câu hỏi;
`hugh` chiếm 74.69% điểm. Vì vậy “bỏ từ trùng title” không bắt được failure này.
Evidence p50–51 và hybrid top1 match cùng token set: coverage và IDF coverage
**bằng nhau**; khác TF/BM25 rank. Coverage riêng lẻ không phân biệt đúng/sai.
Evidence không ở BM25 top15, nên không nhận keyword RRF contribution; Dense rank1
tụt RRF/reranker rank7, không nằm trong final top5. Không nhầm rank36 với score0.

## 5. Counterexamples và kết luận

- Douglass queries `vi-age-estimate`, `vi-cold-bag`, `vi-mistress-experience`
  đều có title-only BM25 top1 không chứa anchor. Tuy nhiên chỉ age v2 giảm
  hybrid Recall@3; hai cases còn lại giữ Recall=1. **Title-only không tự động
  đồng nghĩa hybrid thất bại.**
- Magi Việt `vi-reaction` BM25 top1 match `jim/della`, IDF coverage chỉ **1.81%**
  nhưng chứa đúng full anchor. Một ngưỡng IDF toàn cục có thể loại kết quả hữu ích.
  `vi-magi-tradition` không có BM25 result nhưng hybrid Recall@3=1.
- English/Douglass cũng có losses dù top1 không title-only. Không route toàn bộ
  tiếng Việt sang Dense hoặc đổi weights chỉ theo language/book/case IDs.

Kết luận: có cơ sở nghiên cứu **lexical-strength-aware hybrid**, nhưng chưa đủ
để chốt feature/threshold thành production policy. IDF coverage, title overlap
và matchable fraction chỉ là signals; không phải relevance oracle. Runtime vẫn
baseline và default chunker v1; chưa implement adaptive gating trong bước này.

## 6. Chạy lại và giới hạn

Từ `rag-service`:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.diagnose_lexical_agreement --output data/chunking-comparison/my-lexical-diagnostic.json
~~~

Mặc định đọc hai manifests + reference reports 15/16; có `--english-reference`,
`--vi-reference`, `--pdf-dir`. Cần đúng PDFs/checksums và document/query cache.
Thiếu hoặc embedding identity sai thì fail closed, không hỏi key/giả lập vector.

Private JSON chứa query tokens/DF/IDF và source IDs/ranks, **không full question,
source chunks, source quotes hoặc raw Settings/secrets**. Không tự publish report
hay nối diagnostic vào API. Không chạy DB/Qdrant writes, generation/judge,
reindex, deploy hoặc benchmark latency production.

## 7. Bước tiếp theo

Follow-up [adaptive experiment v2](adaptive-hybrid-experiment-v2.md) đã thực hiện
item 1 dưới đây với sealed formulas/gates và 560 cases. Macro gains không đủ
vì vẫn có Magi MRR/anchor regressions; HOLD, chuyển sang source/query mới và
human review. Không tiếp tục mở rộng grid trên bộ known cases.

1. Chốt hypothesis + một vài coarse alternatives cho **experiment v2** trước
   khi chạy: liệu điều chỉnh lexical contribution theo agreement tổng thể của
   request có giữ được gains Magi và giảm losses Douglass không? Giữ baseline
   control và tách RRF/reranker effects. Chưa chốt công thức/ngưỡng từ bốn ví dụ.
2. Giữ no-regression gates theo segment/case, context budget, source labels và
   công khai counterexamples; không sửa manifest v1 hoặc tune theo known answers.
3. Có candidate thì freeze và dùng fresh questions/native Vietnamese PDF có
   quyền sử dụng + human review. Chỉ review xong mới cân nhắc runtime adoption.

Flow/purpose: validate manifests/reference/source/cache → replay baseline → trace
pipeline thật → kiểm tra từng term/rank → gắn labels sau scoring → xem toàn 80
cases và counterexamples → định hướng experiment mới. Mục đích là hiểu vì sao
hybrid có cả gains/losses, không thay production để lấy score đẹp.
