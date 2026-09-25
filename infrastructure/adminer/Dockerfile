FROM adminer:latest

USER root

# Install dependencies required for Oracle Instant Client and Alpine compatibility
RUN apk --no-cache add libaio libnsl libc6-compat curl autoconf m4 file gcc make g++ && \
    ln -s /lib/libc.so.6 /usr/lib/libresolv.so.2 || true

# Set up Oracle environment
ENV ORACLE_BASE=/usr/local/oracle \
    ORACLE_HOME=/usr/local/oracle/instantclient_21_7 \
    LD_LIBRARY_PATH=/usr/local/oracle/instantclient_21_7:/lib64 \
    TNS_ADMIN=/usr/local/oracle/instantclient_21_7/network/admin

ENV AUTOCONF=/usr/bin/autoconf

# Download Oracle Instant Client Basic Lite & SDK, compile and enable oci8 extension
RUN mkdir -p /usr/local/oracle && \
    curl -k -sSL --retry 3 -o /tmp/basic_lite.zip https://download.oracle.com/otn_software/linux/instantclient/217000/instantclient-basiclite-linux.x64-21.7.0.0.0dbru.zip && \
    curl -k -sSL --retry 3 -o /tmp/sdk.zip https://download.oracle.com/otn_software/linux/instantclient/217000/instantclient-sdk-linux.x64-21.7.0.0.0dbru.zip && \
    unzip -q -d /usr/local/oracle /tmp/basic_lite.zip && \
    unzip -q -d /usr/local/oracle /tmp/sdk.zip && \
    curl -k -sSL --retry 3 -o /tmp/oci8-3.4.0.tgz https://pecl.php.net/get/oci8-3.4.0.tgz && \
    cd /tmp && tar -xzf oci8-3.4.0.tgz && cd oci8-3.4.0 && \
    phpize && \
    ./configure --with-oci8=instantclient,/usr/local/oracle/instantclient_21_7 && \
    make -j$(nproc) && \
    make install && \
    docker-php-ext-enable oci8 && \
    echo '/lib:/usr/local/lib:/usr/lib:/usr/local/oracle/instantclient_21_7' > /etc/ld-musl-x86_64.path && \
    apk del --purge autoconf gcc make g++ && \
    rm -rf /tmp/* /var/cache/apk/*

USER adminer
