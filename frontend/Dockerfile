# ---------- build the SPA ----------
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# ---------- Nginx: static files + reverse proxy + TLS ----------
FROM nginx:1.27-alpine
RUN rm /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
# Site configuration (nginx/hrms.conf, nginx/proxy_params.inc) and TLS certificates are mounted by docker-compose.
EXPOSE 80 443
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://localhost/healthz || exit 1
