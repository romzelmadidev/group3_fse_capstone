FROM adminer:latest

USER root

# Install dependencies required for Oracle Instant Client and Alpine compatibility
RUN apk --no-cache add ca-certificates libaio gcompat libnsl libc6-compat curl && \
    ln -s /usr/lib/libaio.so.1 /lib/libaio.so.1 || true && \
    ln -s /usr/lib/libaio.so.1 /lib64/libaio.so.1 || true

# Set up Oracle environment
ENV ORACLE_BASE=/usr/local/oracle \
    ORACLE_HOME=/usr/local/oracle/instantclient_21_7 \
    LD_LIBRARY_PATH=/usr/local/oracle/instantclient_21_7:/lib64:/usr/lib:/lib \
    TNS_ADMIN=/usr/local/oracle/instantclient_21_7/network/admin

# Copy pre-bundled packages (zero network downloads required during docker build)
COPY basic_lite.zip /tmp/
COPY oci8.so /usr/local/lib/php/extensions/no-debug-non-zts-20240924/oci8.so

# Unpack Oracle client libraries, enable oci8 extension, and register library search paths
RUN mkdir -p /usr/local/oracle && \
    unzip -q -d /usr/local/oracle /tmp/basic_lite.zip && \
    rm -f /tmp/basic_lite.zip && \
    ln -s /usr/lib/libaio.so.1 /usr/local/oracle/instantclient_21_7/libaio.so.1 || true && \
    docker-php-ext-enable oci8 && \
    echo '/lib:/usr/local/lib:/usr/lib:/usr/local/oracle/instantclient_21_7:/lib64' > /etc/ld-musl-x86_64.path

USER adminer
