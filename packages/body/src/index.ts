import mineflayer, { type Bot } from 'mineflayer'
import pathfinderPlugin from 'mineflayer-pathfinder'
import { config } from './config.ts'
import { sendTickEndAfterMoves } from './tickEnd.ts'

const { pathfinder, Movements, goals } = pathfinderPlugin

const RECONNECT_BASE_MS = 5_000
const RECONNECT_MAX_MS = 5 * 60_000
const STABLE_MS = 60_000

let failures = 0
let current: Bot | undefined
let shuttingDown = false

function log(...parts: unknown[]): void {
  console.log(new Date().toISOString(), ...parts)
}

function connect(): void {
  log(`connecting to ${config.host}:${config.port} as ${config.username} (${config.version})`)
  const bot = mineflayer.createBot({
    host: config.host,
    port: config.port,
    username: config.username,
    auth: 'offline',
    version: config.version,
  })
  current = bot
  sendTickEndAfterMoves(bot)
  bot.loadPlugin(pathfinder)
  let spawnedAt: number | undefined

  bot.once('spawn', () => {
    spawnedAt = Date.now()
    bot.pathfinder.setMovements(new Movements(bot))
    bot.chat(`/login ${config.password}`)
    log('spawned and sent /login')
  })

  bot.on('messagestr', (message) => log('[server]', message))

  bot.on('chat', (username, message) => {
    if (username === bot.username) return
    if (message.trim() === '!come') void come(bot, username)
  })

  bot.on('kicked', (reason) => log('kicked:', typeof reason === 'string' ? reason : JSON.stringify(reason)))
  bot.on('error', (err) => log('error:', err.message))

  bot.once('end', (reason) => {
    current = undefined
    if (shuttingDown) return
    if (spawnedAt && Date.now() - spawnedAt > STABLE_MS) failures = 0
    const delay = Math.min(RECONNECT_BASE_MS * 2 ** failures, RECONNECT_MAX_MS)
    failures++
    log(`disconnected (${reason}); reconnecting in ${delay / 1000}s`)
    setTimeout(connect, delay)
  })
}

async function come(bot: Bot, username: string): Promise<void> {
  const target = bot.players[username]?.entity
  if (!target) {
    bot.chat(`I can't see you, ${username}. Come closer and try again.`)
    return
  }
  const { x, y, z } = target.position
  bot.chat('On my way.')
  try {
    await bot.pathfinder.goto(new goals.GoalNear(x, y, z, 2))
    bot.chat('Here.')
  } catch (err) {
    bot.chat(`I couldn't get there: ${(err as Error).message}`)
  }
}

function shutdown(): void {
  shuttingDown = true
  log('shutting down')
  current?.quit()
  setTimeout(() => process.exit(0), 1_000).unref()
}

process.on('SIGTERM', shutdown)
process.on('SIGINT', shutdown)

connect()
