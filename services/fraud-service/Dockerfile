FROM python:3.14.7-slim
WORKDIR /app

COPY requirements.txt .
# xgboost-cpu: same package without the GPU/CUDA libraries (~6 MB instead of ~250 MB with dependencies)
RUN sed 's/^xgboost==/xgboost-cpu==/' requirements.txt > requirements-image.txt \
    && pip install --no-cache-dir --retries 10 --timeout 60 -r requirements-image.txt

COPY fraud fraud
COPY model model
RUN useradd --system --no-create-home fraud
USER fraud

EXPOSE 8083
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=6 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8083/health', timeout=2).status == 200 else 1)"
# 3 worker processes (scoring is CPU-bound; one process saturated around 100 req/s and the
# circuit breaker fell back to rules). httptools + uvloop are pinned explicitly: with uvicorn's
# pure-Python h11 stack, multi-worker mode stalled ~40 ms per kept-alive request (Nagle +
# delayed ACK); these set TCP_NODELAY. Measured 0.5 ms median / 0.7 ms p95 per request.
CMD ["uvicorn", "fraud.api:app", "--host", "0.0.0.0", "--port", "8083", "--workers", "3", "--http", "httptools", "--loop", "uvloop", "--no-access-log"]
