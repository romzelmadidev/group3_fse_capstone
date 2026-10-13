FROM registry.datadoghq.com/agent:7

WORKDIR /app

COPY requirements.txt ./
RUN /opt/datadog-agent/embedded/bin/python3 -m pip install --trusted-host pypi.org --trusted-host files.pythonhosted.org -r requirements.txt \
    && /opt/datadog-agent/embedded/bin/python3 -m pip install --no-deps --trusted-host pypi.org --trusted-host files.pythonhosted.org xgboost

COPY app ./app
RUN mkdir -p /app/data

ENV PYTHONPATH=/app
EXPOSE 8084

ENTRYPOINT ["/opt/datadog-agent/embedded/bin/python3", "-m", "app.server"]
