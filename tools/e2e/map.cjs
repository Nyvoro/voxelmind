// Prints a top-down map of one block type in an area, one layer per y.
const { createRequire } = require('module');
const req = createRequire('V:/VoxelMind/voxelmind-platform/package.json');
const mineflayer = req('mineflayer');
const { Vec3 } = req('vec3');
const [x0, x1, z0, z1, y0, y1] = process.argv.slice(2, 8).map(Number);
const block = process.argv[8] || 'cobblestone';
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25599, username: 'VmlMapper', version: '26.2', auth: 'offline' });
bot.once('spawn', async () => {
  bot.chat(`/tp VmlMapper ${(x0 + x1) / 2} ${y1 + 6} ${(z0 + z1) / 2}`);
  await new Promise((r) => setTimeout(r, 3000));
  for (let y = y0; y <= y1; y++) {
    console.log(`y=${y}   x ${x0}..${x1} →   (# = ${block}, . = air, o = other)`);
    for (let z = z0; z <= z1; z++) {
      let row = `z=${String(z).padEnd(4)} `;
      for (let x = x0; x <= x1; x++) {
        const b = bot.blockAt(new Vec3(x, y, z));
        row += !b ? '?' : b.name === block ? '#' : b.name === 'air' || b.name === 'cave_air' ? '.' : 'o';
      }
      console.log(row);
    }
  }
  bot.quit();
});
bot.on('end', () => process.exit(0));
bot.on('error', (e) => { console.log('ERROR', e.message); process.exit(1); });
