# simulator

Synthetic charge traffic for CardFlow (Python 3.14, httpx). Synthetic data only.

- Creates N cards, then sends charges at a fixed rate with realistic merchants (real MCCs) and log-normal amounts
- Resends about 5% of requests with the **same Idempotency-Key** and checks they're replayed, not double-charged
- Prints a summary of approvals, declines by reason, and retry results; exits non-zero on any mismatch

## Run
```bash
make simulate                                  # via Docker against the compose stack
# or locally:
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m cardflow_sim --cards 20 --charges 500 --rate 20
.venv/bin/pytest
```
