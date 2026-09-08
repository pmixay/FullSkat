FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    DATA_DIR=/data \
    HOST=0.0.0.0 \
    PORT=8080

RUN useradd --create-home --uid 1000 fullskat \
    && mkdir -p /data && chown fullskat:fullskat /data

WORKDIR /app
COPY pyproject.toml README.md ./
COPY fullskat_server ./fullskat_server
RUN pip install .

# For local speech recognition build with: --build-arg EXTRAS=whisper
ARG EXTRAS=
RUN if [ -n "$EXTRAS" ]; then pip install ".[$EXTRAS]"; fi

USER fullskat
VOLUME ["/data"]
EXPOSE 8080

CMD ["python", "-m", "fullskat_server"]
