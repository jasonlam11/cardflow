FROM python:3.14.7-slim
WORKDIR /app
# The base image ships pip 26.2.1, whose vendored certifi has the compromised emSign roots (GHSA-6q48-6c7w-9gw2)
RUN pip install --no-cache-dir --retries 10 --timeout 60 pip==26.2.2
COPY requirements.txt .
RUN pip install --no-cache-dir --retries 10 --timeout 60 -r requirements.txt
COPY cardflow_sim cardflow_sim
RUN useradd --system --no-create-home sim
USER sim
ENTRYPOINT ["python", "-m", "cardflow_sim"]
