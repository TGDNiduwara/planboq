# Deploying the PlanBOQ server on Oracle Cloud ARM

Target: the Oracle Always Free Ampere A1 instance (4 OCPU / 24 GB) — far more
than this service needs. Ubuntu 22.04/24.04 assumed.

## 1. Install Docker (once)

```bash
sudo apt update && sudo apt -y install docker.io docker-compose-v2
sudo usermod -aG docker $USER   # log out and back in
```

## 2. Copy the server folder to the instance

```bash
scp -r server/ ubuntu@YOUR_INSTANCE_IP:~/planboq-server
```

## 3. Configure the API key

```bash
ssh ubuntu@YOUR_INSTANCE_IP
cd ~/planboq-server
echo "PLANBOQ_API_KEY=$(openssl rand -hex 32)" > .env
chmod 600 .env
cat .env    # copy this key into the app: Settings -> Processing server
```

## 4. Build and run

```bash
docker compose up -d --build
curl http://127.0.0.1:8000/api/v1/health
```

The compose file binds to `127.0.0.1` only; TLS termination happens in step 5.

## 5. HTTPS with Caddy (recommended)

A public domain pointed at the instance is required (Oracle free tier does not
issue IPs with domains; use your own DNS, e.g. `plan.yourdomain.com`).

```bash
sudo apt -y install caddy
sudo tee /etc/caddy/Caddyfile > /dev/null <<'EOF'
plan.yourdomain.com {
    reverse_proxy 127.0.0.1:8000
}
EOF
sudo systemctl reload caddy
```

Caddy obtains and renews the TLS certificate automatically.

## 6. Oracle cloud firewall

- In the Oracle Console: Instance -> Subnet -> Security List -> add ingress rule
  TCP 443 from 0.0.0.0/0 (and 80 for ACME challenges).
- On the host: `sudo ufw allow 443/tcp && sudo ufw allow 80/tcp && sudo ufw enable`
  (ufw is usually inactive by default on Ubuntu OCI images — check `sudo ufw status`).

## 7. Point the app at it

App -> Settings -> Processing server:
- URL: `https://plan.yourdomain.com`
- API key: the value from `.env`

The app uses the server only for photo line detection and always falls back to
on-device processing if unreachable.

## Smoke test from a laptop

```bash
KEY=$(ssh ubuntu@YOUR_INSTANCE_IP 'grep PLANBOQ_API_KEY ~/planboq-server/.env | cut -d= -f2')
curl -X POST https://plan.yourdomain.com/api/v1/process/image \
     -H "X-API-Key: $KEY" -F "file=@some_plan.png"
curl -X POST https://plan.yourdomain.com/api/v1/export/dxf \
     -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
     -d '{"name":"t","segments":[{"x1":0,"y1":0,"x2":1000,"y2":0}]}'
```

## Cloud sync endpoints (v4)

The server also provides device-to-device project backup:

- `POST /api/v1/sync/push` — upload project bundles (plan JSON, BOQ JSON,
  review image base64)
- `GET /api/v1/sync/list?sync_id=...` — project metadata list
- `GET /api/v1/sync/project/{id}?sync_id=...` — one bundle
- `DELETE /api/v1/sync/project/{id}?sync_id=...`

All require `X-API-Key`. Sync data is stored under
`PLANBOQ_SYNC_DIR` (default `./sync_data`), in a directory named by the SHA-256
of the user's sync code — the code itself is never stored. Add a persistent
volume in `docker-compose.yml`:

```yaml
services:
  planboq-api:
    volumes:
      - planboq_sync:/data/sync
    environment:
      - PLANBOQ_SYNC_DIR=/data/sync
volumes:
  planboq_sync:
```

**Redeploy required**: pull the updated `server/` folder and run
`docker compose up -d --build`. Without this, the app's cloud sync calls return
404 while plan/BOQ processing keeps working.

## Notes

- 2 uvicorn workers are enough; the ARM box could serve dozens.
- Plan images are processed in memory and discarded; only cloud-sync bundles
  are stored (under the hashed sync-code directory).
- Update: `docker compose up -d --build` again after copying new files.
