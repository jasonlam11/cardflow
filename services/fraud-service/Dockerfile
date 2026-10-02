FROM python:3.14.7-slim
WORKDIR /app

COPY requirements.txt .
# xgboost-cpu: same package without the GPU/CUDA libraries (~6 MB instead of ~250 MB with dependencies)
RUN sed 's/^xgboost==/xgboost-cpu==/' requirements.txt > requirements-image.txt \
    && pip install --no-cache-dir -r requirements-image.txt

COPY fraud fraud
COPY model model
RUN useradd --system --no-create-home fraud
USER fraud

EXPOSE 8083
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=6 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8083/health', timeout=2).status == 200 else 1)"
# 2 worker processes: scoring is CPU-bound and each request is ~1 ms of model time
CMD ["uvicorn", "fraud.api:app", "--host", "0.0.0.0", "--port", "8083", "--workers", "2", "--no-access-log"]
