"""API regression for ProofPay: every guard and endpoint that needs no PayPal buyer login.

Usage:  python scripts/api_regression.py [BASE_URL]          (default http://localhost:8080)
        PROOFPAY_API_KEY=...  python scripts/api_regression.py   when the backend requires a key
        DEMO_CLIENT_EMAIL / DEMO_FREELANCER_EMAIL: sandbox personal accounts (any two distinct emails work)

Creates one DRAFT job and one unapproved PayPal order (nothing is held or charged). Needs OPENAI_API_KEY on
the backend for the criteria step. Exit code 0 only if every check passes.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

# Windows consoles default to cp1252; criteria text can contain characters like "↔".
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")
API_KEY = os.environ.get("PROOFPAY_API_KEY", "")
BUYER = os.environ.get("DEMO_CLIENT_EMAIL", "client@personal.example.com")
FREELANCER = os.environ.get("DEMO_FREELANCER_EMAIL", "freelancer@personal.example.com")
results = []


def call(method, path, body=None, timeout=200):
    data = None if body is None else json.dumps(body).encode()
    headers = {"Content-Type": "application/json"}
    if API_KEY:
        headers["X-Api-Key"] = API_KEY
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f"  -> {detail}"))


def job_body(**over):
    b = {"title": "Regression job", "description": "Python CLI temperature converter for C, F and K that rejects "
         "values below absolute zero, with pytest tests and a README with usage examples.",
         "clientEmail": BUYER, "freelancerEmail": FREELANCER, "currency": "USD",
         "milestones": [{"title": "Library and CLI", "amount": 40}, {"title": "Tests and docs", "amount": 60}]}
    b.update(over)
    return b


s, b = call("GET", "/actuator/health")
check("health UP", s == 200 and b["status"] == "UP", b)
if API_KEY:
    req = urllib.request.Request(BASE + "/api/jobs")
    try:
        urllib.request.urlopen(req, timeout=10)
        check("no API key -> 401", False, "request without key was accepted")
    except urllib.error.HTTPError as e:
        check("no API key -> 401", e.code == 401, e.code)

# --- input validation
s, b = call("POST", "/api/jobs", job_body(clientEmail="not-an-email"))
check("create: bad email -> 400", s == 400, (s, b))
s, b = call("POST", "/api/jobs", job_body(freelancerEmail=BUYER))
check("create: client == freelancer -> 400", s == 400, (s, b))
s, b = call("POST", "/api/jobs", job_body(milestones=[]))
check("create: no milestones -> 400", s == 400, (s, b))
s, b = call("POST", "/api/jobs", job_body(milestones=[{"title": "x", "amount": 0.5}]))
check("create: amount < 1 -> 400", s == 400, (s, b))
s, b = call("GET", "/api/jobs/00000000-0000-0000-0000-000000000000")
check("unknown job -> 404", s == 404, (s, b))

# --- happy path up to funding
s, job = call("POST", "/api/jobs", job_body())
check("create job -> 201, DRAFT, total 100", s == 201 and job["status"] == "DRAFT" and job["totalAmount"] == 100, (s, job))
jid = job["id"]
m1, m2 = job["milestones"][0]["id"], job["milestones"][1]["id"]

s, b = call("POST", f"/api/jobs/{jid}/criteria/accept", {"party": "CLIENT"})
check("accept before compile -> 409", s == 409, (s, b))
s, b = call("POST", f"/api/jobs/{jid}/criteria/accept", {"party": "BOSS"})
check("accept with unknown party -> 400", s == 400, (s, b))
s, b = call("POST", f"/api/jobs/{jid}/order")
check("order before acceptance -> 409", s == 409 and "accept" in b["message"], (s, b))

t = time.time()
s, job = call("POST", f"/api/jobs/{jid}/criteria")
crit = [json.loads(m["criteriaJson"]) if m["criteriaJson"] else [] for m in job.get("milestones", [])] if s == 200 else []
check(f"compile criteria -> 200, both milestones ({time.time() - t:.0f}s)",
      s == 200 and len(crit) == 2 and all(2 <= len(c) <= 6 for c in crit), (s, job))
if crit:
    ids = [c["id"] for c in crit[0]] + [c["id"] for c in crit[1]]
    check("criteria ids renumbered by server", ids[0] == "M1-C1" and any(i.startswith("M2-") for i in ids), ids)
    banned = ["temperature.py", "c_to_f", "cli.py"]
    text = json.dumps(crit).lower()
    check("criteria do not invent file/function names", not any(x in text for x in banned),
          [x for x in banned if x in text])
    for i, c in enumerate(crit, 1):
        for x in c:
            print(f"     M{i} {x['id']}: {x['description'][:110]}")

s, b = call("POST", f"/api/jobs/{jid}/criteria/accept", {"party": "CLIENT"})
check("client accepts -> 200", s == 200 and b["clientAcceptedCriteriaAt"], (s, b))
s, b = call("POST", f"/api/jobs/{jid}/order")
check("order with only client acceptance -> 409", s == 409, (s, b))
s, b = call("POST", f"/api/jobs/{jid}/criteria/accept", {"party": "FREELANCER"})
check("freelancer accepts -> 200", s == 200 and b["freelancerAcceptedCriteriaAt"], (s, b))

s, o1 = call("POST", f"/api/jobs/{jid}/order")
check("order -> 200 PAYER_ACTION_REQUIRED + approveUrl",
      s == 200 and o1["status"] == "PAYER_ACTION_REQUIRED" and "sandbox.paypal.com" in o1["approveUrl"], (s, o1))
s, o2 = call("POST", f"/api/jobs/{jid}/order")
check("order again -> same PayPal order (idempotent)", s == 200 and o2["orderId"] == o1["orderId"], (o1, o2))
s, b = call("GET", f"/api/jobs/{jid}")
check("job now AWAITING_FUNDING", b["status"] == "AWAITING_FUNDING", b["status"])

# --- locks and state guards before the buyer approves
s, b = call("POST", f"/api/jobs/{jid}/criteria")
check("recompile after order -> 409 (criteria locked)", s == 409, (s, b))
s, b = call("POST", f"/api/jobs/{jid}/criteria/accept", {"party": "CLIENT"})
check("re-accept after order -> 409", s == 409, (s, b))
s, b = call("POST", f"/api/jobs/{jid}/authorize")
check("authorize before buyer approval -> 409", s == 409 and "approved" in b["message"], (s, b))
s, b = call("POST", f"/api/milestones/{m1}/submit", {"deliverableUrl": "https://github.com/Jagapradeep/proofpay-demo-good"})
check("submit on unfunded job -> 409", s == 409, (s, b))
s, b = call("POST", f"/api/milestones/{m1}/submit", {"deliverableUrl": "https://gitlab.com/x/y"})
check("submit non-GitHub URL -> 400", s == 400, (s, b))
s, b = call("POST", f"/api/milestones/{m1}/verify")
check("verify on unfunded job -> 409", s == 409, (s, b))
s, b = call("POST", f"/api/milestones/{m1}/propose-release",
            {"amount": 40, "confidence": 0.95, "injectionFlagged": False, "evidence": []})
check("manual propose on unfunded job -> gate blocks", s == 200 and b["approved"] is False and b["reasons"], (s, b))
s, b = call("POST", f"/api/milestones/{m1}/propose-release",
            {"amount": 40, "confidence": 1.5, "injectionFlagged": False, "evidence": []})
check("propose with confidence > 1 -> 400", s == 400, (s, b))
s, b = call("POST", f"/api/jobs/{jid}/void")
check("void before authorization -> 409", s == 409, (s, b))

# --- the completed live job from Nov 10 stays locked
s, jobs = call("GET", "/api/jobs")
done = [j for j in jobs if j["title"] == "Temperature converter CLI" and j["status"] == "COMPLETED"]
if not done:
    print("     (no completed live job in this database: skipping the post-payment checks)")
else:
    check("completed live job has auth CAPTURED", done[-1]["funding"]["status"] == "CAPTURED", done[-1]["funding"])
    pm = done[-1]["milestones"][0]["id"]
    s, b = call("POST", f"/api/milestones/{pm}/verify")
    check("verify a PAID milestone -> 409", s == 409, (s, b))
    s, b = call("POST", f"/api/milestones/{pm}/propose-release",
                {"amount": 40, "confidence": 0.99, "injectionFlagged": False, "evidence": []})
    check("second release for a PAID milestone -> gate blocks", b["approved"] is False
          and any("already has a release" in r for r in b["reasons"]), b)
    s, b = call("POST", f"/api/jobs/{done[-1]['id']}/void")
    check("void a fully captured job -> rejected", s in (409, 502), (s, b))
    rep = json.loads(done[-1]["milestones"][0]["verificationJson"] or "{}")
    check("verification report stored on milestone", rep.get("outcome") == "RELEASED", rep.get("outcome"))

# --- read endpoints
s, ledger = call("GET", "/api/ledger")
check("ledger -> rows with PayPal ids", s == 200 and all(r["paypalCaptureId"] for r in ledger if r["status"] == "PAID"), s)
s, audit = call("GET", f"/api/jobs/{jid}/audit")
types = [e["type"] for e in audit]
check("job audit trail in order", types[:1] == ["JOB_CREATED"] and "CRITERIA_COMPILED" in types
      and types.count("CRITERIA_ACCEPTED") == 2 and "ORDER_CREATED" in types, types)
s, b = call("GET", "/api/audit")
check("global audit -> 200", s == 200 and len(b) > 0, s)

# --- webhook without a configured webhook id must not be trusted
s, b = call("POST", "/api/webhooks/paypal", {"id": "WH-FAKE", "event_type": "PAYMENT.CAPTURE.COMPLETED",
                                             "resource": {"id": "FAKE"}})
print(f"     webhook (no PAYPAL_WEBHOOK_ID): HTTP {s} {b}")
check("fake webhook does not error the server", s < 500, (s, b))

ok = sum(1 for _, p in results if p)
print(f"\n{ok}/{len(results)} checks passed. Regression job id: {jid}")
sys.exit(0 if ok == len(results) else 1)
