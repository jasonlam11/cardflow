FROM python:3.14.7-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 FASTEMBED_CACHE_PATH=/app/models HF_HUB_DISABLE_TELEMETRY=1

COPY requirements.txt requirements-bedrock.txt ./
RUN pip install --no-cache-dir --retries 10 --timeout 60 -r requirements.txt
# AWS builds add the Bedrock client (boto3); local images stay smaller without it
ARG WITH_BEDROCK=false
RUN if [ "$WITH_BEDROCK" = "true" ]; then pip install --no-cache-dir --retries 10 --timeout 60 -r requirements-bedrock.txt; fi

# Bake the embedding model into the image: no download at startup, no network needed at runtime
RUN python -c "from fastembed import TextEmbedding; TextEmbedding(model_name='BAAI/bge-small-en-v1.5', cache_dir='/app/models')"

COPY assistant assistant
COPY docs docs
RUN useradd --system --no-create-home assistant && chown -R assistant /app/models
USER assistant

EXPOSE 8084
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8084/health', timeout=2).status == 200 else 1)"
CMD ["uvicorn", "assistant.main:app", "--host", "0.0.0.0", "--port", "8084", "--no-access-log"]
