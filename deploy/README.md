# Inventori V2.2 production deployment

Recommended topology:

Android APK → HTTPS → Nginx → 127.0.0.1:18766 → Inventori Python backend → OpenAI API

## 1. DNS

Create a subdomain such as `inventori-api.yourdomain.com` pointing to the VPS.

## 2. Install files

On Ubuntu/Debian:

```bash
sudo useradd --system --home /opt/inventori-ai --shell /usr/sbin/nologin inventori || true
sudo mkdir -p /opt/inventori-ai /var/lib/inventori
sudo chown inventori:inventori /opt/inventori-ai /var/lib/inventori
```

Copy `backend/inventori_server.py` to:

`/opt/inventori-ai/inventori_server.py`

Copy `deploy/inventori-ai.service` to:

`/etc/systemd/system/inventori-ai.service`

Create `/etc/inventori-ai.env` from `deploy/inventori-ai.env.example`.

Protect it:

```bash
sudo chmod 600 /etc/inventori-ai.env
```

## 3. Start backend

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now inventori-ai
curl http://127.0.0.1:18766/health
```

Expected: JSON with `"ok":true`.

## 4. Nginx + HTTPS

Copy `deploy/nginx-inventori-api.conf` to `/etc/nginx/sites-available/inventori-api`, replace the example domain, then:

```bash
sudo ln -s /etc/nginx/sites-available/inventori-api /etc/nginx/sites-enabled/inventori-api
sudo nginx -t
sudo systemctl reload nginx
sudo certbot --nginx -d inventori-api.yourdomain.com
```

Confirm:

```bash
curl https://inventori-api.yourdomain.com/health
```

## 5. Build Android with backend URL

Locally:

```bash
./gradlew assembleDebug -PINVENTORI_AI_BACKEND_URL=https://inventori-api.yourdomain.com
```

For GitHub Actions, create repository variable:

`INVENTORI_AI_BACKEND_URL=https://inventori-api.yourdomain.com`

Then trigger the Android debug APK workflow.

## Security

- Never put `OPENAI_API_KEY` in Android, GitHub source, or a public issue.
- Keep `/etc/inventori-ai.env` readable only by root.
- Backend listens on loopback only.
- Nginx is the only public listener.
- Use HTTPS before enabling the Android backend URL.
