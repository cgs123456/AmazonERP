# syntax=docker/dockerfile:1.7

FROM node:22.14.0-alpine3.21 AS builder

ARG VERSION=dev
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
ARG VITE_API_BASE_URL=/api
ARG VITE_WS_URL=/ws/socket

ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
ENV VITE_WS_URL=${VITE_WS_URL}

WORKDIR /app

COPY amz-frontend/package.json amz-frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY amz-frontend/tsconfig*.json ./
COPY amz-frontend/vite.config.ts ./
COPY amz-frontend/index.html ./
COPY amz-frontend/src ./src
COPY amz-frontend/public ./public

RUN npm run build

# 1.27-alpine 指向的 Alpine 包已过期：Grype 扫出 103 个 HIGH/CRITICAL
# （openssl 3.3.3 / curl 8.12.1 / libxml2 2.13.4 等，Alpine 仓库均已有修复版本）。
# 升到 1.30.5-alpine 后同一 DB 复扫降到 4 个，其中 3 个（tiff/zlib）上游暂无修复版本。
FROM nginx:1.30.5-alpine

ARG VERSION=dev
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="AmazonERP Frontend" \
      org.opencontainers.image.description="Amazon ERP Vue frontend" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="https://github.com/cgs123456/AmazonERP"

COPY --from=builder /app/dist /usr/share/nginx/html

RUN cat > /etc/nginx/conf.d/default.conf <<'NGINX'
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;

    location /api/ {
        proxy_pass http://amz-gateway:10010/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /ws/ {
        proxy_pass http://amz-service-message:8888/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
NGINX

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1