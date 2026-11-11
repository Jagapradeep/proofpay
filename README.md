# ProofPay

<img src="frontend/src/app/icon.svg" alt="ProofPay logo" width="64" height="64">

AI escrow that pays on proof. A client funds a job through PayPal (held, not charged), an AI
verifier checks the freelancer's deliverable against agreed criteria, and a deterministic policy
gate decides whether money moves. **The LLM proposes, code disposes.**

Built for the [PayPal AI Hackathon](https://paypalaihackathon.devpost.com/).

## How money moves

| Step | PayPal API |
|---|---|
| Client funds the job | `POST /v2/checkout/orders` (intent `AUTHORIZE`) → buyer approves → `POST /v2/checkout/orders/{id}/authorize` |
| Milestone approved by the gate | `POST /v2/payments/authorizations/{id}/capture` (partial; `final_capture` on the last) |
| Freelancer gets paid | `POST /v1/payments/payouts` → status via webhook or `GET /v1/payments/payouts/{id}` |
| Job cancelled | `POST /v2/payments/authorizations/{id}/void` |

Release states: `VERIFIED → CAPTURED → PAYOUT_PENDING → PAID | PAYOUT_FAILED`
(captures can pass through `CAPTURE_PENDING`). Webhooks trigger a re-check; a poller is the backup.

## How the AI works

| Step | What happens | Who decides |
|---|---|---|
| Criteria compiler | LangChain4j AI service turns the plain-English job into 2-4 testable criteria per milestone (structured JSON output). Client **and** freelancer accept them before funding. | LLM drafts, both people accept |
| Injection scan | Regex pre-pass over every fetched file: "ignore previous instructions", text addressed to an AI reviewer, payment directives, hidden HTML comments, zero-width characters. | Code |
| Verifier agent | Gets the README up front, reads the rest of the repo (pinned to one commit) in one `readFiles` call, scores each criterion with verbatim evidence quotes, then calls `proposeRelease`, which ends the run. File content is wrapped as untrusted data. | LLM proposes |
| Evidence check | Every quote must appear word for word in the file it names; a "passed" criterion without one is unproven and confidence is capped at 0.5. | Code |
| Policy gate | Same gate as below; injection flag = scanner OR agent. | Code disposes |

The tool is bound to one milestone by the server, so the model cannot aim a payment elsewhere,
and exceptions inside our tools are never shown to the model.

### Safety rules (enforced in Java, not by the model)
- Amount ≤ milestone amount and ≤ what's left on the authorization
- Milestone must be `SUBMITTED`, job `FUNDED`, authorization live and not expired, one release per milestone
- Verifier confidence ≥ 0.8 and no prompt-injection flag
- Payee is never the platform's own PayPal account
- Each release gets its own random `PayPal-Request-Id`s, saved before calling PayPal and reused only for retries
- After every capture, the capture and authorization are read back from PayPal before the state changes

## Run locally

Requirements: Java 21+, Node 22+, Docker.

```bash
cp .env.example .env          # PayPal sandbox app credentials + OPENAI_API_KEY
docker compose up -d          # Postgres 16
cd backend && ./mvnw spring-boot:run          # API on http://localhost:8080

cd frontend
cp .env.example .env.local    # BACKEND_URL, PROOFPAY_API_KEY (same as backend, or empty), PAYPAL_CLIENT_ID
npm ci && npm run dev         # web app on http://localhost:3000
```

### Demo walkthrough (web app)
1. Header: **Viewing as Client**. *Post a job* → *Use the demo job* → *Create job*.
2. *Generate criteria with AI*, then *Accept as client*; switch to **Freelancer** and *Accept as freelancer*.
3. Back as **Client**: click the PayPal button and log in with the sandbox **buyer** account. $100 is now *held*.
4. As **Freelancer**, milestone 1: click the *injected* demo repo → *Submit* → *Run AI verification* → blocked.
5. Resubmit with the *good* demo repo → verified → partial capture + payout. Repeat for milestone 2.
6. Open **Ledger** (AG Grid) to see captures and payouts; the job page shows the full audit trail.

Demo deliverables: [good](https://github.com/Jagapradeep/proofpay-demo-good) ·
[partial](https://github.com/Jagapradeep/proofpay-demo-partial) ·
[injected](https://github.com/Jagapradeep/proofpay-demo-injected) (the instructions hide in an HTML comment that GitHub does not render).

### API protection
When `PROOFPAY_API_KEY` is set, every `/api/**` call needs the `X-Api-Key` header (PayPal webhooks are exempt and
verified by signature). The browser never sees the key: it calls the Next.js server, which proxies to the API and
adds it. The AI endpoints are rate limited (20/hour per IP, 120/hour overall by default).

The key is any long random string, and the **same value** goes in `.env` (backend) and `frontend/.env.local` (web
app). Leave both empty on localhost (API open). On Render it is generated and shared between the services for you.

### AI cost
- Default model `gpt-5-mini` with `OPENAI_REASONING_EFFORT=minimal`: the agent takes 7 to 10 s (15 to 30 s for a
  whole verification including GitHub and PayPal), and is correct on all
  three demo repos. (`gpt-5-nano` was tested and is not reliable enough for the verifier.)
- **Answer cache** (`ai_cache` table): the same job text gives the same criteria, and the same repo commit with the
  same criteria and model gives the same verdict, without calling OpenAI again. Only the model's answer is reused:
  the injection scan, evidence check and policy gate run every time. "Regenerate with AI" bypasses the cache;
  `AI_CACHE_ENABLED=false` turns it off.
- Set a monthly budget on your OpenAI project as a hard ceiling.

### Tests
- `cd backend && ./mvnw test`: 57 unit tests (policy gate, state machine, PayPal client contracts, injection
  scanner, evidence checker, the real LangChain4j tool loop with a scripted model, API key filter, AI cache).
  No network, no OpenAI cost.
- `python scripts/api_regression.py http://localhost:8080`: 38 API checks against a running backend
  (validation, state guards, criteria locking, idempotent orders, gate blocks). Set `PROOFPAY_API_KEY` in the
  environment if the API needs it.

### Try the flow with curl

```bash
# 1. Create a job with two milestones
curl -X POST localhost:8080/api/jobs -H 'Content-Type: application/json' -d '{
  "title": "Todo API", "description": "Spring Boot todo REST API with tests",
  "clientEmail": "<sandbox personal buyer email>", "freelancerEmail": "<sandbox personal freelancer email>",
  "currency": "USD",
  "milestones": [{"title": "CRUD endpoints", "amount": 40}, {"title": "Tests + README", "amount": 60}]}'

# 2. AI compiles acceptance criteria; both sides accept (required before funding)
curl -X POST localhost:8080/api/jobs/<jobId>/criteria
curl -X POST localhost:8080/api/jobs/<jobId>/criteria/accept -H 'Content-Type: application/json' -d '{"party":"CLIENT"}'
curl -X POST localhost:8080/api/jobs/<jobId>/criteria/accept -H 'Content-Type: application/json' -d '{"party":"FREELANCER"}'

# 3. Create the PayPal order, open approveUrl and log in as the sandbox buyer
curl -X POST localhost:8080/api/jobs/<jobId>/order

# 4. After approving, authorize (money is now held)
curl -X POST localhost:8080/api/jobs/<jobId>/authorize

# 5. Freelancer submits; the verifier agent checks it and proposes a release to the policy gate
curl -X POST localhost:8080/api/milestones/<milestoneId>/submit -H 'Content-Type: application/json' \
  -d '{"deliverableUrl": "https://github.com/Jagapradeep/proofpay-demo-good"}'
curl -X POST localhost:8080/api/milestones/<milestoneId>/verify     # 15-30 s; returns the full report

# (Human-review stand-in for the agent: goes through the same gate)
curl -X POST localhost:8080/api/milestones/<milestoneId>/propose-release -H 'Content-Type: application/json' \
  -d '{"amount": 40, "confidence": 0.93, "injectionFlagged": false, "evidence": ["README documents all endpoints"]}'

# 6. Watch the ledger
curl localhost:8080/api/ledger
```

## Deploy to Render

`render.yaml` is a Blueprint for Postgres + the API (Docker) + the web app (Docker).
1. Render dashboard → **Blueprints → New Blueprint Instance** → pick this repo.
2. Fill the prompted values: PayPal sandbox `PAYPAL_CLIENT_ID` / `PAYPAL_CLIENT_SECRET` / `PAYPAL_PLATFORM_EMAIL`,
   `OPENAI_API_KEY`, and the two URLs (`APP_BASE_URL` = the web app URL, `BACKEND_URL` = the API URL; you can set
   them after the first deploy, when Render shows the URLs). `PROOFPAY_API_KEY` is generated and shared automatically.
3. Optional: add a PayPal webhook pointing at `https://<api>/api/webhooks/paypal` and set `PAYPAL_WEBHOOK_ID`
   (without it, a 30 s poller tracks captures and payouts).

Free instances sleep after 15 minutes idle; the first request then takes about a minute.

## Tech
Spring Boot 3.5, Java 21, PostgreSQL + Flyway, Spring `RestClient` for PayPal REST (cached OAuth token),
LangChain4j 1.22 with OpenAI (`gpt-5-mini`, minimal reasoning; AI services, `@Tool`, strict JSON schema output).
Frontend: Next.js 16 (App Router), React 19, Tailwind 4, PayPal JS SDK via `@paypal/react-paypal-js`
(`intent=authorize`), AG Grid Community 36 for the ledger. Hosting: Render (Docker).

## License
[MIT](LICENSE)
