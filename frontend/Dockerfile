# Static deployment recipe; Docker was not available during this UAT-candidate
# handoff. The Nginx proxy keeps the browser on one origin and does not expose
# the internal AI service.
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --no-audit --no-fund
COPY . .
ARG VITE_API_BASE_URL=/api/v1
ARG VITE_ENABLE_DEMO_FALLBACK=false
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_ENABLE_DEMO_FALLBACK=$VITE_ENABLE_DEMO_FALLBACK
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]

