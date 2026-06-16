import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const BOOK_ID = __ENV.BOOK_ID || '3105';

export const options = {
  stages: [
    { duration: '1m', target: 500 },  // Khởi động tăng dần lên 500 VUs
    { duration: '1m', target: 1500 }, // Tăng tiếp lên 1500 VUs
    { duration: '1m', target: 3000 }, // Đẩy mạnh lên 3000 VUs
    { duration: '2m', target: 3000 }, // Giữ tải đỉnh ở mức 3000 VUs trong 2 phút để test độ bền
    { duration: '1m', target: 0 },    // Hạ tải dần về 0
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1500'],
    checks: ['rate>0.95'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/api/books/${BOOK_ID}`, {
    tags: {
      api: 'book-detail',
      scenario: 'hot-book-stress-3000', // Cập nhật tên scenario thành 3000
    },
  });

  check(res, {
    'status is 200': (r) => r.status === 200,
    'response time < 1500ms': (r) => r.timings.duration < 1500,
  });

  sleep(1);
}