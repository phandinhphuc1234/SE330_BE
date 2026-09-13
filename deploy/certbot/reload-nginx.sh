#!/usr/bin/env bash
# Mục đích: Certbot gọi hook này sau khi gia hạn chứng chỉ để Nginx nạp certificate
# mới mà không cần dừng dịch vụ.

# Bước 1: dừng ngay nếu kiểm tra hoặc reload Nginx thất bại.
set -Eeuo pipefail

# Bước 2: chỉ reload khi toàn bộ Nginx configuration vẫn hợp lệ.
/usr/sbin/nginx -t
/bin/systemctl reload nginx

# FLOW TÓM TẮT:
# Certbot gia hạn certificate -> nginx -t -> graceful reload Nginx.
#
# VẤN ĐỀ GIẢI QUYẾT:
# certificate trên đĩa được thay mới không tự làm worker Nginx đang chạy đọc lại;
# hook này nạp certificate mới mà không làm gián đoạn các request hiện tại.
