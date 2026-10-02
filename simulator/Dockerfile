FROM python:3.14.7-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY cardflow_sim cardflow_sim
RUN useradd --system --no-create-home sim
USER sim
ENTRYPOINT ["python", "-m", "cardflow_sim"]
