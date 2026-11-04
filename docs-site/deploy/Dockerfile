# Builds the docs site and serves it as static files. The build context is docs-site/:
#   docker build -f docs-site/deploy/Dockerfile -t supermux-docs docs-site
# (In Coolify: base directory docs-site, Dockerfile deploy/Dockerfile, port 80.)
FROM oven/bun:1 AS build
WORKDIR /site
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN bun run build

FROM caddy:2-alpine
COPY --from=build /site/out /srv
RUN printf ':80 {\n\troot * /srv\n\ttry_files {path} {path}/ {path}/index.html\n\tfile_server\n\thandle_errors {\n\t\trewrite * /404.html\n\t\tfile_server\n\t}\n}\n' > /etc/caddy/Caddyfile
EXPOSE 80
