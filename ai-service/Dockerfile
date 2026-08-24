# Static deployment recipe; the local Docker daemon was unavailable during the
# UAT-candidate handoff. XGBoost remains optional, so the image uses MA7 when
# the optional package is not installed.
FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1
WORKDIR /app
COPY requirements.txt requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY examples ./examples
EXPOSE 8001
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]

