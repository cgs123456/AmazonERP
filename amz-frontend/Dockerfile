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
# 升到 1.30.5-alpine 后同一 DB 复扫降到 4 个：tiff x2 / zlib / libexpat。
# 再删掉 image-filter 动态模块后降到 1 个（zlib 1.3.2-r0，Alpine 暂无修复版本），
# 见下面的 apk del：tiff <- libgd <- nginx-module-image-filter，
# libexpat <- fontconfig <- libgd，这两级依赖都只由该模块引入。
FROM nginx:1.30.5-alpine

# 能删的依据：本镜像的 nginx.conf 里没有任何 load_module，
# /usr/lib/nginx/modules/*.so 只是躺在磁盘上、不会被加载，所以 image-filter 对静态资源
# 服务和 /api、/ws 反代都不起作用。nginx -t 写在同一条 RUN 里，一旦将来有人真的
# load_module 了这个模块，构建会立刻失败，而不是让裁剪悄悄失效。
# apk add --upgrade 升级基础镜像烘焙后被标高危的 OS 包（v0.1.7 release run 37830488051 实测：
# pcre2 10.48-r0 CVE-2026-103111、libcrypto3/libssl3 3.5.8-r0 四个 HIGH CVE。
# 本镜像基于 alpine v3.24：apk add 不带 -u 不会升级已装包（实测踩坑），
# 带 --upgrade 后实测升级到 pcre2 10.49-r0 / libcrypto3+libssl3 3.5.9-r0）。
# 只升级这三个被 flag 的包，不动 nginx 本体；zlib 1.3.2 无修复版，仍走豁免。
RUN apk add --no-cache --upgrade pcre2 libcrypto3 libssl3 && \
    apk del nginx-module-image-filter libgd tiff && nginx -t

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