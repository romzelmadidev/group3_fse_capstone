# ==============================================================================
# FSE Capstone: Customer Retail Banking Flutter Web Dockerfile
# Builds Flutter Web bundle and serves via Nginx Alpine
# Expected Build Context: Root directory of group3_fse_capstone
# ==============================================================================

FROM ghcr.io/cirruslabs/flutter:stable AS builder

WORKDIR /repo

# Copy core package and web app
COPY aurabank_core /repo/aurabank_core
COPY aurabank_web /repo/aurabank_web

WORKDIR /repo/aurabank_web

RUN flutter config --no-analytics
RUN flutter pub get
RUN flutter build web --release --pwa-strategy=none

FROM nginx:alpine
RUN rm -rf /usr/share/nginx/html/*
COPY --from=builder /repo/aurabank_web/build/web /usr/share/nginx/html
COPY aurabank_web/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
