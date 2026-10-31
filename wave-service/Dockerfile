# Сервис Volna: волна, библиотека и веб-версия. Как запустить рядом с Navidrome — deploy/README.md.
FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1 \
    VOLNA_HOST=0.0.0.0 VOLNA_PORT=8765 VOLNA_DB=/data/volna.db

WORKDIR /app
COPY pyproject.toml ./
COPY volna ./volna
RUN pip install . && useradd --uid 1000 --no-create-home volna && mkdir /data && chown volna /data

USER volna
VOLUME /data
EXPOSE 8765
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8765/api/health', timeout=4)"
CMD ["python", "-m", "volna"]
