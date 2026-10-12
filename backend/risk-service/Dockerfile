# syntax=docker/dockerfile:1
FROM registry.datadoghq.com/agent:7

WORKDIR /app

COPY requirements.txt ./
RUN --mount=type=cache,target=/root/.cache/pip \
    /opt/datadog-agent/embedded/bin/python3 -m pip install --trusted-host pypi.org --trusted-host files.pythonhosted.org -r requirements.txt \
    && /opt/datadog-agent/embedded/bin/python3 -m pip install --no-deps --trusted-host pypi.org --trusted-host files.pythonhosted.org xgboost

COPY app ./app

ENV PYTHONPATH=/app
EXPOSE 8084

ENTRYPOINT ["/opt/datadog-agent/embedded/bin/python3", "-m", "app.server"]
