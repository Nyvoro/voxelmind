// Plays the owner in a local VoxelMind Local test: joins, runs a scripted chat, logs everything it sees.
const { createRequire } = require('module');
const req = createRequire('V:/VoxelMind/voxelmind-platform/package.json');
const mineflayer = req('mineflayer');

const steps = JSON.parse(require('fs').readFileSync(process.argv[2], 'utf8'));
const companion = process.argv[3] || 'Lysa';
const start = Date.now();
const log = (...a) => console.log(`[${((Date.now() - start) / 1000).toFixed(1)}s]`, ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25599, username: 'VmlOwner', version: '26.2', auth: 'offline' });
bot.on('messagestr', (msg, pos) => { if (pos !== 'game_info') log('CHAT', msg); });
bot.on('kicked', (r) => log('KICKED', JSON.stringify(r)));
bot.on('error', (e) => log('ERROR', e.message));
bot.on('end', (r) => { log('END', r); process.exit(0); });

function where() {
  const p = bot.players[companion];
  const me = bot.entity && bot.entity.position;
  if (!p || !p.entity || !me) return `${companion}: not visible`;
  const c = p.entity.position;
  return `${companion} at (${c.x.toFixed(1)},${c.y.toFixed(1)},${c.z.toFixed(1)}) dist ${c.distanceTo(me).toFixed(1)}`;
}

bot.once('spawn', async () => {
  log('spawned at', bot.entity.position.toString());
  const tracker = setInterval(() => log('POS', where()), 10000);
  for (const s of steps) {
    if (s.wait) await sleep(s.wait);
    if (s.chat) { log('SAY', s.chat); bot.chat(s.chat); }
    if (s.pickup) { // walk onto nearby dropped items so a hand-over can be observed
      const items = Object.values(bot.entities).filter((e) => e.name === 'item' && e.position.distanceTo(bot.entity.position) < 8);
      log('ITEMS NEAR', items.length);
    }
    if (s.walk) { // walk in the facing direction for s.walk ms, optionally turning first
      if (typeof s.yaw === 'number') await bot.look(s.yaw, 0, true);
      bot.setControlState('forward', true); bot.setControlState('sprint', !!s.sprint);
      await sleep(s.walk);
      bot.setControlState('forward', false); bot.setControlState('sprint', false);
      log('WALKED to', bot.entity.position.toString());
    }
    if (s.inventory) log('INVENTORY', bot.inventory.items().map((i) => `${i.name}x${i.count}`).join(', ') || 'empty');
  }
  clearInterval(tracker);
  log('POS', where());
  log('INVENTORY', bot.inventory.items().map((i) => `${i.name}x${i.count}`).join(', ') || 'empty');
  bot.quit();
});
