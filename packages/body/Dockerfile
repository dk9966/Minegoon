FROM node:24-slim

RUN corepack enable
WORKDIR /app

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY packages/body/package.json packages/body/
RUN pnpm install --frozen-lockfile --prod --filter @minegent/body

COPY packages/body packages/body

USER node
WORKDIR /app/packages/body
CMD ["node", "src/index.ts"]
