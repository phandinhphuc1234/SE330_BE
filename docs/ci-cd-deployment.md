# CI/CD production trên một VPS

Pipeline này chỉ có một môi trường chạy thật: `production`. Pull request dùng để
kiểm tra source; mọi commit được đưa vào `main` sẽ được đóng gói thành đúng một
Docker image bất biến và deploy lên VPS khi biến repository `CD_ENABLED=true`.

## Luồng tự động

```text
pull request -> Maven verify + JaCoCo + CodeQL
       merge main
            -> build Docker image
            -> push GHCR bằng tag commit và digest
            -> tạo SBOM/provenance
            -> quét HIGH/CRITICAL bằng Trivy
            -> SSH vào VPS
            -> kiểm tra Docker và runtime env
            -> kiểm tra Flyway V22
            -> pg_dump PostgreSQL
            -> pull và thay library-service
            -> Docker healthcheck
            -> public healthcheck, nếu đã cấu hình URL
            -> thành công hoặc rollback application image
```

Không có bước build source trên VPS. Image được deploy luôn có dạng:

```text
ghcr.io/phandinhphuc1234/se330-be@sha256:<digest>
```

Pipeline không tự rollback schema. Migration mới phải tương thích ngược với
application version trước; khôi phục database là thao tác có chủ đích từ backup.

## Thành phần trong repository

- `.github/workflows/backend-ci.yml`: CI, build image và deploy tự động.
- `.github/workflows/vps-bootstrap.yml`: cài Docker CE và Compose v2 thủ công.
- `.github/workflows/vps-provision-runtime.yml`: sinh runtime secret trực tiếp trên VPS.
- `.github/workflows/vps-preflight.yml`: kiểm tra SSH/VPS/database thủ công.
- `.github/workflows/rollback-production.yml`: deploy lại một image digest cũ.
- `deploy/compose.production.yaml`: stack PostgreSQL, Redis và backend production.
- `deploy/scripts/preflight.sh`: kiểm tra VPS mà không in secret.
- `deploy/scripts/deploy.sh`: backup, deploy, healthcheck và rollback application.
- `deploy/nginx`: template reverse proxy HTTPS.

## GitHub Secrets

Các secret sau phải nằm tại **Settings -> Secrets and variables -> Actions**:

```text
VPS_HOST
VPS_PORT
VPS_USER
VPS_SSH_PRIVATE_KEY
VPS_KNOWN_HOSTS
```

`VPS_SSH_PRIVATE_KEY` phải chứa toàn bộ PEM. `VPS_KNOWN_HOSTS` phải được lấy từ
kênh đáng tin cậy và workflow luôn dùng `StrictHostKeyChecking=yes`.

Runtime secret như PostgreSQL, Redis, JWT, email và payment không nằm trong
GitHub. Chúng chỉ nằm trên VPS tại:

```text
$HOME/.config/quanlythuvien/backend.env
```

## GitHub Variables

```text
CD_ENABLED=false
DEPLOY_PLATFORM=linux/amd64
PUBLIC_HEALTH_URL=https://api.library.example.com/actuator/health
```

`PUBLIC_HEALTH_URL` có thể để trống trước khi có domain. Chỉ đổi
`CD_ENABLED=true` sau khi workflow preflight đã thành công.

## Chuẩn bị VPS lần đầu

VPS cần Ubuntu/Debian, `flock`, tối thiểu 2 GiB dung lượng trống và một deploy
user dùng SSH key. Docker group có quyền gần tương đương root, vì vậy chỉ dùng
tài khoản chuyên dụng và tắt SSH password.

Nếu máy chưa có Docker, chạy workflow thủ công sau trước:

```text
Bootstrap production VPS -> Run workflow
```

Workflow chỉ hỗ trợ Ubuntu/Debian, yêu cầu deploy user có passwordless `sudo`,
cài Docker CE + Compose v2 từ apt repository chính thức, bật service khi boot và
thêm deploy user vào group `docker`. Nếu phát hiện container package xung đột,
workflow dừng để người vận hành xem xét thay vì tự gỡ package.

Sau khi Docker đã được cài, chạy workflow:

```text
Provision production runtime -> Run workflow
```

Với giai đoạn chưa có domain/HTTPS, chọn:

```text
api_scheme=http
frontend_origin=http://localhost:3000
confirmation=PROVISION
```

Workflow đồng bộ deployment assets, sau đó chạy `provision-runtime.sh` trên
VPS. Script sinh `POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `JWT_SECRET`, secret
phiên đọc ebook và object-storage credential bằng CSPRNG của OpenSSL. File được
ghi nguyên tử với mode `600`; giá trị secret không xuất hiện trong Actions log.

Provisioning là idempotent: secret hợp lệ đã tồn tại được giữ nguyên, chỉ giá
trị trống hoặc chứa `CHANGE_ME` mới được sinh. Vì vậy chạy lại workflow không
đổi mật khẩu PostgreSQL ngoài ý muốn. Các URL public/CORS được cập nhật theo
input mỗi lần chạy; khi đã cài HTTPS, chạy lại với `api_scheme=https`.

Email là tích hợp tùy chọn. Khi chưa có `RESEND_API_KEY`, provisioning đặt
`MANAGEMENT_HEALTH_MAIL_ENABLED=false` để SMTP không làm container bị đánh dấu
unhealthy. Sau khi cấu hình email thật, có thể đổi biến này thành `true` để đưa
SMTP vào healthcheck production.

Sau đó vào GitHub Actions và chạy:

```text
VPS deployment preflight -> Run workflow
```

Workflow preflight sẽ:

1. Xác nhận SSH host key và đăng nhập bằng private key.
2. Đồng bộ thư mục `deploy` tới `$HOME/apps/quanlythuvien`.
3. Kiểm tra `$HOME/.config/quanlythuvien/backend.env` đã được provision.
4. Khởi động PostgreSQL/Redis và kiểm tra lịch sử Flyway V22.

Nếu không dùng workflow provisioning, vẫn có thể sửa thủ công trên VPS:

```bash
nano "$HOME/.config/quanlythuvien/backend.env"
chmod 600 "$HOME/.config/quanlythuvien/backend.env"
```

Không được để lại `CHANGE_ME`, `example.com`, localhost callback hoặc mật khẩu
demo. Nếu không dùng VNPAY/RAG, giữ `VNPAY_ENABLED=false` và `RAG_ENABLED=false`.

## Bootstrap database một lần

Migration V22 là data-fix lịch sử và không chạy trên database rỗng. Trước khi
bật CD, phải restore snapshot đã được làm sạch và chứa
`flyway_schema_history` thành công qua V22.

Khởi động riêng PostgreSQL và Redis:

```bash
export RUNTIME_ENV_FILE="$HOME/.config/quanlythuvien/backend.env"
export APP_IMAGE='ghcr.io/phandinhphuc1234/se330-be:preflight'

docker compose --project-name quanlythuvien \
  --env-file "$RUNTIME_ENV_FILE" \
  -f "$HOME/apps/quanlythuvien/compose.production.yaml" \
  up -d --wait postgres redis
```

Chỉ với database production mới, chưa có dữ liệu cần giữ, restore dump đã upload:

```bash
docker compose --project-name quanlythuvien \
  --env-file "$RUNTIME_ENV_FILE" \
  -f "$HOME/apps/quanlythuvien/compose.production.yaml" \
  exec -T postgres sh -ceu \
  'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --no-privileges' \
  < "$HOME/library-sanitized.dump"
```

Sau đó chạy lại `VPS deployment preflight`. Workflow chỉ xanh khi database có
lịch sử V22 hợp lệ.

## Bật deploy tự động

Sau khi preflight xanh, đổi repository variable:

```text
CD_ENABLED=true
```

Từ thời điểm này, merge vào `main` sẽ tự deploy. Các deployment dùng concurrency
group chung và không hủy một lần deploy đang chạy dở.

## Backup và trạng thái

Trước mỗi lần thay application, script tạo PostgreSQL custom dump và checksum:

```text
$HOME/backups/quanlythuvien/library-<UTC timestamp>.dump
$HOME/backups/quanlythuvien/library-<UTC timestamp>.dump.sha256
```

Image và commit đang chạy được ghi tại:

```text
$HOME/apps/quanlythuvien/state/current-image
$HOME/apps/quanlythuvien/state/current-commit
```

Phải sao chép backup sang storage nằm ngoài VPS và áp dụng retention riêng.

## Rollback

Vào workflow `Roll back production image`, nhập image đầy đủ dạng digest và gõ
`ROLLBACK`. Workflow vẫn backup database trước, sau đó deploy image được chọn và
chờ healthcheck.

Rollback này không hạ version Flyway. Nếu migration không tương thích ngược,
phải dừng deploy và thực hiện kế hoạch khôi phục database riêng.

## Nginx và firewall

Khi chưa có domain, chạy workflow `Provision Nginx HTTP entry point`, nhập
`PROVISION_NGINX`. Workflow cài Nginx, dùng IP VPS làm `server_name`, public
`http://<VPS_IP>/healthz` và giữ backend ở `127.0.0.1:8080`. Swagger cùng các
endpoint Actuator còn lại bị chặn tại Nginx.

HTTP chỉ dùng để xác nhận routing ban đầu; không gửi JWT, mật khẩu hoặc dữ liệu
thật qua kết nối này. Webroot `/var/www/certbot` đã được tạo sẵn để chuyển sang
HTTPS bằng domain hoặc short-lived IP certificate sau đó.

Khi đã có domain, sao chép virtual-host template và proxy snippet trong
`deploy/nginx`, đổi domain/certificate rồi kiểm tra:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

Chỉ public TCP `80/443` và cổng SSH đã chọn. Backend bind
`127.0.0.1:8080`; PostgreSQL và Redis không publish host port. Nginx ghi đè
`X-Forwarded-For` để backend không tin IP do client tự cung cấp.

Nếu Nginx trả health thành công ngay trên VPS nhưng request từ Internet timeout,
hãy mở inbound TCP `80` (và `443` khi bật HTTPS) trong firewall/security group
của nhà cung cấp cloud. UFW trên VPS và Azure Network Security Group là hai lớp
khác nhau; mở UFW không tự thay đổi rule của Azure.

## Kiểm tra sau deploy

```bash
docker compose --project-name quanlythuvien \
  --env-file "$HOME/.config/quanlythuvien/backend.env" \
  -f "$HOME/apps/quanlythuvien/compose.production.yaml" ps

curl --fail https://api.library.example.com/healthz
```

Không dùng `docker compose down -v` trên VPS vì lệnh đó xóa volume database.
