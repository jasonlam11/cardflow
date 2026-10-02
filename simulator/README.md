# simulator

Synthetic charge traffic for CardFlow (Python 3.14, httpx). Synthetic data only.

- Creates N cards, then sends charges at a fixed rate with realistic merchants (real MCCs) and log-normal amounts
- Resends about 5% of requests with the **same Idempotency-Key** and checks they're replayed, not double-charged
- Prints a summary of approvals, declines by reason, and retry results; exits non-zero on any mismatch

## Modes
| Command | What it does |
|---|---|
| `live` (default) | v1: random everyday traffic with ~5% client retries |
| `dataset` | Writes ~90 days of labeled history (~255k rows, ~1.5% fraud) for training the fraud model |
| `replay` | Sends held-out days through the real API with their original timestamps and reports precision/recall of what the system flagged |

**Fraud patterns** injected by `dataset`: velocity bursts, first-time high-risk categories, impossible travel (faster than a plane), and amount spikes (10–30× normal). Legitimate traffic includes trips, occasional big purchases, first-time categories and short legit bursts, so fraud isn't trivially separable.

## Run
```bash
make simulate                                  # via Docker against the compose stack
# or locally:
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m cardflow_sim --cards 20 --charges 500 --rate 20
.venv/bin/python -m cardflow_sim dataset --out data/transactions.csv.gz
.venv/bin/python -m cardflow_sim replay --file data/transactions.csv.gz --max-cards 200
.venv/bin/pytest
```
