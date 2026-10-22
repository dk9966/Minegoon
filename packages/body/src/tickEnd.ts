import type { Bot } from 'mineflayer'

const MOVE_PACKETS = new Set(['position', 'position_look', 'look', 'flying'])

// 26.3 servers allow one position packet per client tick and only reset that
// limit on tick_end, which mineflayer 4.39 never sends, so without this every
// bot is kicked for invalid_player_movement shortly after joining.
// https://github.com/PrismarineJS/mineflayer/issues/4189
export function sendTickEndAfterMoves(bot: Bot): void {
  const client = bot._client
  const write = client.write.bind(client)
  client.write = (name: string, params: unknown) => {
    write(name, params)
    if (MOVE_PACKETS.has(name)) write('tick_end', {})
  }
}
