# Inventori V2.2 AI backend

This backend keeps the OpenAI API key off Android devices, performs photo analysis, and meters successful AI scans.

## Environment

Required:

- \`OPENAI_API_KEY\`
- \`INVENTORI_TOKEN_SECRET\` — generate a long random value, e.g. \`openssl rand -hex 32\`

Optional:

- \`INVENTORI_HOST=127.0.0.1\`
- \`INVENTORI_PORT=18766\`
- \`INVENTORI_DB_PATH=/var/lib/inventori/inventori-ai.sqlite3\`
- \`INVENTORI_AI_MODEL=gpt-6-luna\`
- \`INVENTORI_IMAGE_DETAIL=high\`
- \`INVENTORI_MONTHLY_SCAN_LIMIT=10\`
- \`INVENTORI_MOCK_OPENAI=1\` for backend testing without provider calls

## Run

\`\`\`bash
cd backend
export OPENAI_API_KEY='...'
export INVENTORI_TOKEN_SECRET='...'
python3 inventori_server.py
\`\`\`

The service binds to \`127.0.0.1:18766\` by default. Put Nginx/Caddy in front of it and expose it only over HTTPS.

Health endpoint:

\`\`\`bash
curl https://YOUR-DOMAIN/health
\`\`\`

## Android build

Pass the HTTPS backend URL at build time:

\`\`\`bash
./gradlew assembleDebug -PINVENTORI_AI_BACKEND_URL=https://inventory-api.example.com
\`\`\`

GitHub Actions also reads a repository variable named \`INVENTORI_AI_BACKEND_URL\`.

## Metering behavior

- Default free allowance: 10 successful AI scans per UTC calendar month.
- One submitted photo = at most one charged scan, even when it returns many items.
- Provider errors and zero-item detections are not charged.
- Images are relayed to the model API and are not written to the Inventori backend database.
- Request logs store token counts, estimated provider cost, item count, model, and charge status, but not image bytes.

The install-token mechanism protects the OpenAI key and supports MVP scan metering. Before paid billing/multi-user launch, replace anonymous install identity with authenticated user/account entitlements.
