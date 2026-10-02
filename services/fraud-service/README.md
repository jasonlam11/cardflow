# fraud-service

Scores card authorizations for fraud (Python 3.14, FastAPI, XGBoost, PostgreSQL). Returns a score, a band (LOW / REVIEW / HIGH) and up to 3 reason codes from SHAP values.

## API
| Method | Path | Description |
|---|---|---|
| POST | `/score` | `requestId`, `cardId`, `amountMinor`, `currency`, `mcc`, `merchantId`, optional `channel`, `merchantLocation`, `occurredAt` |
| GET | `/health` | Liveness + DB check |
| GET | `/model` | Model version, thresholds, test metrics |
| GET | `/docs` | Interactive API docs |

## Layout
```
fraud/features.py    the ONLY feature implementation (training and serving)
fraud/model.py       loads model/model.json (XGBoost JSON, never pickle) + thresholds
fraud/reasons.py     feature -> reason code mapping
fraud/store.py       per-card history (Postgres; in-memory for tests)
fraud/api.py         FastAPI app
training/train.py    time-split training, thresholds, metrics, model card
model/               committed model + metadata (regenerate with `make train`)
```

## Results
See [docs/model-card.md](../../docs/model-card.md): PR-AUC 0.918 on held-out days.

## Test
```bash
make test-fraud      # from repo root: unit, API, Postgres store, model quality gate
```
