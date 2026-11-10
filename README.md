# ProofPay

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

### Safety rules (enforced in Java, not by the model)
- Amount ≤ milestone amount and ≤ what's left on the authorization
- Milestone must be `SUBMITTED`, job `FUNDED`, authorization live and not expired, one release per milestone
- Verifier confidence ≥ 0.8 and no prompt-injection flag
- Payee is never the platform's own PayPal account
- Each release gets its own random `PayPal-Request-Id`s, saved before calling PayPal and reused only for retries
- After every capture, the capture and authorization are read back from PayPal before the state changes

## Run locally

Requirements: Java 21+, Docker.

```bash
cp .env.example .env          # fill in your PayPal sandbox app credentials
docker compose up -d          # Postgres 16
cd backend && ./mvnw spring-boot:run
```

API on http://localhost:8080 (health: `/actuator/health`).

### Try the flow with curl

```bash
# 1. Create a job with two milestones
curl -X POST localhost:8080/api/jobs -H 'Content-Type: application/json' -d '{
  "title": "Todo API", "description": "Spring Boot todo REST API with tests",
  "clientEmail": "<sandbox personal buyer email>", "freelancerEmail": "<sandbox personal freelancer email>",
  "currency": "USD",
  "milestones": [{"title": "CRUD endpoints", "amount": 40}, {"title": "Tests + README", "amount": 60}]}'

# 2. Create the PayPal order, open approveUrl and log in as the sandbox buyer
curl -X POST localhost:8080/api/jobs/<jobId>/order

# 3. After approving, authorize (money is now held)
curl -X POST localhost:8080/api/jobs/<jobId>/authorize

# 4. Freelancer submits, then a release is proposed (the AI verifier will call this)
curl -X POST localhost:8080/api/milestones/<milestoneId>/submit -H 'Content-Type: application/json' \
  -d '{"deliverableUrl": "https://github.com/<user>/<repo>"}'
curl -X POST localhost:8080/api/milestones/<milestoneId>/propose-release -H 'Content-Type: application/json' \
  -d '{"amount": 40, "confidence": 0.93, "injectionFlagged": false, "evidence": ["README documents all endpoints"]}'

# 5. Watch the ledger
curl localhost:8080/api/ledger
```

## Tech
Spring Boot 3.5, Java 21, PostgreSQL + Flyway, Spring `RestClient` for PayPal REST (cached OAuth token).
Coming next: LangChain4j criteria compiler and verifier agent, Next.js + PayPal JS SDK + AG Grid ledger, Render deploy.

## License
[MIT](LICENSE)
