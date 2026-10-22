function required(name: string): string {
  const value = process.env[name]
  if (!value) {
    throw new Error(`Missing ${name}. Set it in .env (see infra/cloud/.env.example).`)
  }
  return value
}

export const config = {
  host: required('MC_HOST'),
  port: Number(process.env.MC_PORT || 25565),
  version: process.env.MC_VERSION || '26.1',
  username: process.env.BOT_USERNAME || 'Builder',
  password: required('BOT_PASSWORD'),
}
