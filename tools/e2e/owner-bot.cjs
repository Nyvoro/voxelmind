// Plays the owner in a local VoxelMind Local test: joins, runs a scripted chat, logs everything it sees.
//
//   node tools/e2e/owner-bot.cjs <scenario.json> [companion] [--build=hand|command] [--dry-run]
//
// A scenario is a JSON list of steps. The keys of one step run in this order:
//   wait (ms), anchor, note, chat, pickup, walk (+ yaw, sprint), build, check, inventory.
// chat text may use {c} (companion name), {owner} (this bot) and {at:dx,dy,dz}: the absolute "x y z" of the block
// at that offset from the anchor (the bot's feet block when an anchor step ran; its spawn block before that).
// build places blocks like a player does, by hand (use-item packets, survival); a block that does not land that way
// is set with /setblock and counted. --build=command uses /fill and /setblock throughout. check logs what the world
// shows: a build's blocks and doors, single cells, the companion's position, or blocks of a type near a point.
// --dry-run validates the scenario and prints the expanded plan without connecting.
const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
const positional = args.filter((a) => !a.startsWith('--'));
const flags = args.filter((a) => a.startsWith('--'));
const scenarioFile = positional[0];
const companion = positional[1] || 'Lysa';
const OWNER = 'VmlOwner';
const DRY = flags.includes('--dry-run');
const buildFlag = flags.find((f) => f.startsWith('--build='));
const BUILD_MODE = buildFlag ? buildFlag.slice('--build='.length) : 'hand';
const REACH = 4.4; // eye to block; survival allows 4.5 plus a server tolerance of 1
const FACINGS = ['north', 'south', 'east', 'west'];
const STEP_KEYS = new Set(['wait', 'anchor', 'note', 'chat', 'pickup', 'walk', 'yaw', 'sprint', 'build', 'check', 'inventory']);
const INTERACTIVE = /door|gate|chest|barrel|furnace|smoker|crafting|table|bed$|button|lever|anvil|shulker|hopper|dispenser|dropper|stonecutter|loom|grindstone|bell|lectern|beacon|brewing|cauldron|composter|jukebox|campfire|sign|flower_pot|cake|anchor|crafter|vault|note_block|repeater|comparator/;

if (!scenarioFile || !['hand', 'command'].includes(BUILD_MODE)) {
  console.error('usage: node tools/e2e/owner-bot.cjs <scenario.json> [companion] [--build=hand|command] [--dry-run]');
  process.exit(2);
}
const steps = JSON.parse(fs.readFileSync(scenarioFile, 'utf8'));

// ---- scenario shape (also used by --dry-run) ----

const isNums = (a, n) => Array.isArray(a) && a.length === n && a.every((v) => Number.isInteger(v));

function validate(list) {
  const problems = [];
  if (!Array.isArray(list)) return ['the scenario must be a JSON list of steps'];
  list.forEach((s, i) => {
    const at = `step ${i + 1}`;
    if (!s || typeof s !== 'object' || Array.isArray(s)) return problems.push(`${at}: not an object`);
    for (const k of Object.keys(s)) if (!STEP_KEYS.has(k)) problems.push(`${at}: unknown key "${k}"`);
    if (s.wait !== undefined && !(Number.isFinite(s.wait) && s.wait >= 0)) problems.push(`${at}: wait must be milliseconds >= 0`);
    if (s.chat !== undefined) {
      if (typeof s.chat !== 'string' || !s.chat.trim()) problems.push(`${at}: chat must be a non-empty string`);
      else {
        const rest = s.chat.replace(/\{c\}|\{owner\}|\{at:-?\d+,-?\d+,-?\d+\}/g, '');
        if (/\{at:|\{(c|owner)(?![A-Za-z0-9_])/.test(rest)) problems.push(`${at}: malformed placeholder in "${s.chat}" (use {c}, {owner}, {at:dx,dy,dz})`);
        if (s.chat.length > 256) problems.push(`${at}: chat longer than 256 characters`);
      }
    }
    if (s.walk !== undefined && !(Number.isFinite(s.walk) && s.walk > 0)) problems.push(`${at}: walk must be milliseconds > 0`);
    if (s.yaw !== undefined && !Number.isFinite(s.yaw)) problems.push(`${at}: yaw must be a number (radians)`);
    if (s.build !== undefined) problems.push(...validateBuild(s.build).map((p) => `${at}: build: ${p}`));
    if (s.check !== undefined) problems.push(...validateCheck(s.check).map((p) => `${at}: check: ${p}`));
  });
  const names = new Set();
  list.forEach((s, i) => {
    if (s && s.build) names.add(s.build.name || 'build');
    if (s && s.check && s.check.build && !names.has(s.check.build)) problems.push(`step ${i + 1}: check: no earlier build named "${s.check.build}"`);
  });
  return problems;
}

function validateBuild(b) {
  const p = [];
  if (!b || typeof b !== 'object') return ['must be an object'];
  if (b.name !== undefined && typeof b.name !== 'string') p.push('name must be a string');
  if (b.by !== undefined && !['hand', 'command'].includes(b.by)) p.push('by must be "hand" or "command"');
  if (b.clear !== undefined && b.clear !== true && !isNums(b.clear, 6)) p.push('clear must be true or [x1,y1,z1,x2,y2,z2]');
  if (b.stand !== undefined && !Number.isInteger(b.stand)) p.push('stand must be an integer y offset');
  if (!Array.isArray(b.parts) || b.parts.length === 0) return p.concat('parts must be a non-empty list');
  b.parts.forEach((part, j) => {
    const kinds = ['fill', 'walls', 'door'].filter((k) => part && part[k] !== undefined);
    if (kinds.length !== 1) return p.push(`part ${j + 1}: needs exactly one of fill, walls, door`);
    if (part.door !== undefined) {
      if (!isNums(part.door, 3)) p.push(`part ${j + 1}: door must be [x,y,z]`);
      if (part.facing !== undefined && !FACINGS.includes(part.facing)) p.push(`part ${j + 1}: facing must be one of ${FACINGS.join(', ')}`);
      if (part.block !== undefined && !/_door$/.test(part.block)) p.push(`part ${j + 1}: a door block ends in _door`);
    } else {
      if (!isNums(part[kinds[0]], 6)) p.push(`part ${j + 1}: ${kinds[0]} must be [x1,y1,z1,x2,y2,z2]`);
      if (typeof part.block !== 'string' || !/^[a-z0-9_]+$/.test(part.block)) p.push(`part ${j + 1}: block must be a block id like "oak_planks"`);
    }
  });
  return p;
}

function validateCheck(c) {
  const p = [];
  if (!c || typeof c !== 'object') return ['must be an object'];
  const known = ['label', 'build', 'cells', 'companion', 'find'];
  for (const k of Object.keys(c)) if (!known.includes(k)) p.push(`unknown key "${k}"`);
  if (!['build', 'cells', 'companion', 'find'].some((k) => c[k] !== undefined)) p.push('needs build, cells, companion or find');
  if (c.cells !== undefined && !(Array.isArray(c.cells) && c.cells.every((d) => isNums(d, 3)))) p.push('cells must be a list of [x,y,z]');
  if (c.find !== undefined && !(c.find && typeof c.find.block === 'string' && isNums(c.find.near, 3))) p.push('find needs block and near [x,y,z]');
  return p;
}

// ---- build planning (pure: the dry run shows exactly what a live run does) ----

function boxCells(b) {
  const [x1, y1, z1, x2, y2, z2] = b;
  const out = [];
  for (let y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
    for (let x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
      for (let z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) out.push([x, y, z]);
    }
  }
  return out;
}

function wallCells(b) {
  const [x1, , z1, x2, , z2] = b;
  const [ax, bx, az, bz] = [Math.min(x1, x2), Math.max(x1, x2), Math.min(z1, z2), Math.max(z1, z2)];
  return boxCells(b).filter(([x, , z]) => x === ax || x === bx || z === az || z === bz);
}

/** The four sides of a walls box as fill boxes. */
function wallBoxes(b) {
  const [x1, y1, z1, x2, y2, z2] = b;
  const [ax, bx, az, bz] = [Math.min(x1, x2), Math.max(x1, x2), Math.min(z1, z2), Math.max(z1, z2)];
  return [[ax, y1, az, bx, y2, az], [ax, y1, bz, bx, y2, bz], [ax, y1, az, ax, y2, bz], [bx, y1, az, bx, y2, bz]];
}

/** Eye of a body standing on spot (feet block) to the nearest point of block d. */
function reachTo(spot, d) {
  const eye = [spot[0] + 0.5, spot[1] + 1.62, spot[2] + 0.5];
  let sum = 0;
  for (let i = 0; i < 3; i++) {
    const v = eye[i] < d[i] ? d[i] - eye[i] : eye[i] > d[i] + 1 ? eye[i] - d[i] - 1 : 0;
    sum += v * v;
  }
  return Math.sqrt(sum);
}

function planBuild(spec) {
  const key = (d) => d.join(',');
  const cells = new Map();
  const doors = [];
  for (const part of spec.parts) {
    if (part.door) {
      doors.push({ d: part.door.slice(), block: part.block || 'oak_door', facing: part.facing || 'north' });
      continue;
    }
    for (const d of part.fill ? boxCells(part.fill) : wallCells(part.walls)) {
      if (part.block === 'air') cells.delete(key(d));
      else cells.set(key(d), { d, block: part.block });
    }
  }
  for (const o of doors) {
    cells.delete(key(o.d));
    cells.delete(key([o.d[0], o.d[1] + 1, o.d[2]]));
  }
  const all = [...cells.values()];
  const pts = all.map((c) => c.d).concat(doors.flatMap((o) => [o.d, [o.d[0], o.d[1] + 1, o.d[2]]]));
  const min = [0, 1, 2].map((i) => Math.min(...pts.map((p) => p[i])));
  const max = [0, 1, 2].map((i) => Math.max(...pts.map((p) => p[i])));
  const stand = spec.stand === undefined ? 0 : spec.stand;
  const midX = Math.floor((min[0] + max[0]) / 2);
  const midZ = Math.floor((min[2] + max[2]) / 2);
  // Standing places two blocks outside each side; they must be walkable ground (the scenarios flatten the site first).
  const spots = [[min[0] - 2, stand, midZ], [max[0] + 2, stand, midZ], [midX, stand, min[2] - 2], [midX, stand, max[2] + 2]];
  const spotNames = ['W', 'E', 'N', 'S'];
  const nearest = (d) => {
    let best = -1;
    let dist = Infinity;
    spots.forEach((s, i) => {
      const r = reachTo(s, d);
      if (r < dist - 1e-9) {
        dist = r;
        best = i;
      }
    });
    return dist <= REACH ? best : -1;
  };
  const edge = (d) => Math.min(d[0] - min[0], max[0] - d[0], d[2] - min[2], max[2] - d[2]);
  for (const c of all) c.spot = nearest(c.d);
  for (const o of doors) o.spot = nearest(o.d);
  // Per standing place: bottom layer first, outer ring before inner cells, so each block has a neighbour to go against.
  all.sort((a, b) => a.spot - b.spot || a.d[1] - b.d[1] || edge(a.d) - edge(b.d) || a.d[0] - b.d[0] || a.d[2] - b.d[2]);
  const clear = spec.clear === true ? min.concat(max) : Array.isArray(spec.clear) ? spec.clear : null;
  const blocks = {};
  for (const c of all) blocks[c.block] = (blocks[c.block] || 0) + 1;
  for (const o of doors) blocks[o.block] = (blocks[o.block] || 0) + 1;
  return { name: spec.name || 'build', by: spec.by || BUILD_MODE, cells: all, doors, clear, spots, spotNames, blocks, min, max };
}

function describePlan(plan) {
  const counts = Object.entries(plan.blocks).map(([b, n]) => `${b} ${n}`).join(', ');
  const perSpot = plan.spots.map((s, i) => `${plan.spotNames[i]}(${s.join(',')}) ${plan.cells.filter((c) => c.spot === i).length + plan.doors.filter((o) => o.spot === i).length}`);
  const far = plan.cells.filter((c) => c.spot < 0).length + plan.doors.filter((o) => o.spot < 0).length;
  return `${plan.cells.length + plan.doors.length} placements (${counts})` + (plan.clear ? `, clears (${plan.clear.slice(0, 3)})..(${plan.clear.slice(3)}) first` : '')
    + (plan.by === 'hand' ? `; by hand from ${perSpot.join(', ')}; out of reach (by command): ${far}` : '; by command');
}

// ---- placeholders ----

let anchor = null;

function expand(text, anchorPos) {
  return text.replace(/\{c\}/g, companion).replace(/\{owner\}/g, OWNER).replace(/\{at:(-?\d+),(-?\d+),(-?\d+)\}/g, (m, x, y, z) => {
    const a = anchorPos();
    return `${a[0] + Number(x)} ${a[1] + Number(y)} ${a[2] + Number(z)}`;
  });
}

// ---- dry run ----

if (DRY) {
  const problems = validate(steps);
  const fake = [0, 64, 0];
  let t = 0;
  const line = (...a) => console.log(`  [${(t / 1000).toFixed(1).padStart(6)}s]`, ...a);
  console.log(`DRY RUN ${path.basename(scenarioFile)}: companion ${companion}, owner ${OWNER}, builds ${BUILD_MODE}, anchor assumed at ${fake.join(' ')}`);
  if (problems.length === 0) {
    for (const s of steps) {
      t += s.wait || 0;
      if (s.anchor) line('ANCHOR here');
      if (s.note) line('NOTE', s.note);
      if (s.chat) line('SAY', expand(s.chat, () => fake));
      if (s.pickup) line('ITEMS NEAR');
      if (s.walk) line(`WALK ${s.walk}ms` + (typeof s.yaw === 'number' ? ` yaw ${s.yaw}` : ''));
      if (s.build) line('BUILD', planBuild(s.build).name + ':', describePlan(planBuild(s.build)));
      if (s.check) line('CHECK', JSON.stringify(s.check));
      if (s.inventory) line('INVENTORY');
    }
  }
  console.log(`${steps.length} steps, about ${(t / 60000).toFixed(1)} min of waits plus building, ${problems.length} problem(s)`);
  for (const p of problems) console.log('  PROBLEM', p);
  process.exit(problems.length ? 1 : 0);
}

// ---- live run ----

const { createRequire } = require('module');
const req = createRequire('V:/VoxelMind/voxelmind-platform/package.json');
const mineflayer = req('mineflayer');
const { Vec3 } = req('vec3');

const problems = validate(steps);
if (problems.length) {
  for (const p of problems) console.error('PROBLEM', p);
  process.exit(1);
}

const start = Date.now();
const log = (...a) => console.log(`[${((Date.now() - start) / 1000).toFixed(1)}s]`, ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const builds = new Map();

const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25599, username: OWNER, version: '26.2', auth: 'offline' });
bot.on('messagestr', (msg, pos) => { if (pos !== 'game_info') log('CHAT', msg); });
bot.on('kicked', (r) => log('KICKED', JSON.stringify(r)));
bot.on('error', (e) => log('ERROR', e.message));
bot.on('end', (r) => { log('END', r); process.exit(0); });

function setAnchor(why) {
  const p = bot.entity.position;
  anchor = [Math.floor(p.x), Math.floor(p.y), Math.floor(p.z)];
  log(why, anchor.join(' '));
}
const anchorPos = () => anchor;
const abs = (d) => { const a = anchorPos(); return new Vec3(a[0] + d[0], a[1] + d[1], a[2] + d[2]); };
const rel = (v) => { const a = anchorPos(); return `(${Math.floor(v.x) - a[0]},${Math.floor(v.y) - a[1]},${Math.floor(v.z) - a[2]})`; };

function where() {
  const p = bot.players[companion];
  const me = bot.entity && bot.entity.position;
  if (!p || !p.entity || !me) return `${companion}: not visible`;
  const c = p.entity.position;
  return `${companion} at (${c.x.toFixed(1)},${c.y.toFixed(1)},${c.z.toFixed(1)}) rel ${rel(c)} dist ${c.distanceTo(me).toFixed(1)}`;
}

async function cmd(text) {
  bot.chat(text);
  await sleep(150);
}

function describeBlock(b) {
  if (!b) return 'unloaded';
  if (!/_door$|_trapdoor$|_gate$/.test(b.name)) return b.name;
  try {
    const p = b.getProperties();
    const shown = ['half', 'open', 'facing'].filter((k) => p[k] !== undefined).map((k) => `${k}=${p[k]}`);
    return shown.length ? `${b.name}[${shown.join(',')}]` : b.name;
  } catch {
    return b.name;
  }
}

async function hold(name) {
  if (bot.heldItem && bot.heldItem.name === name) return true;
  const item = bot.inventory.items().find((i) => i.name === name);
  if (!item) return false;
  try {
    await bot.equip(item, 'hand');
  } catch (e) {
    log('EQUIP', name, 'failed:', e.message);
    return false;
  }
  for (let i = 0; i < 50 && !(bot.heldItem && bot.heldItem.name === name); i++) await sleep(20);
  return !!(bot.heldItem && bot.heldItem.name === name);
}

async function standAt(spot) {
  const target = abs(spot).offset(0.5, 0, 0.5);
  if (bot.entity.position.distanceTo(target) < 0.7) return;
  await cmd(`/tp ${OWNER} ${target.x} ${target.y} ${target.z}`);
  for (let i = 0; i < 60 && bot.entity.position.distanceTo(target) > 0.7; i++) await sleep(50);
  await sleep(400); // the server ignores block clicks until the client confirmed the teleport
}

/** One block like a player: hold it, look at a solid neighbour's face, use it, and wait for the world to show the block. */
async function placeByHand(d, block) {
  const dest = abs(d);
  const before = bot.blockAt(dest);
  if (before && before.name === block) return 'already';
  if (before && before.boundingBox === 'block') return `occupied by ${before.name}`;
  const dirs = [[0, -1, 0], [0, 0, -1], [0, 0, 1], [1, 0, 0], [-1, 0, 0], [0, 1, 0]];
  let ref = null;
  let face = null;
  for (const [x, y, z] of dirs) {
    const n = bot.blockAt(dest.offset(x, y, z));
    if (n && n.boundingBox === 'block' && !INTERACTIVE.test(n.name)) {
      ref = n;
      face = new Vec3(-x, -y, -z);
      break;
    }
  }
  if (!ref) return 'no solid neighbour';
  if (!(await hold(block))) return `cannot hold ${block}`;
  try {
    await bot.lookAt(ref.position.offset(0.5 + face.x * 0.5, 0.5 + face.y * 0.5, 0.5 + face.z * 0.5), true);
    await sleep(60); // the look goes out with the next physics tick
    await bot._genericPlace(ref, face, { forceLook: 'ignore', swingArm: 'right' });
  } catch (e) {
    return `place error: ${e.message}`;
  }
  for (let i = 0; i < 30; i++) {
    await sleep(40);
    const now = bot.blockAt(dest);
    if (now && now.name === block) return 'hand';
  }
  return 'did not land';
}

const YAW = { north: 0, west: Math.PI / 2, south: Math.PI, east: -Math.PI / 2 };

async function doorByHand(o) {
  const lower = abs(o.d);
  const floor = bot.blockAt(lower.offset(0, -1, 0));
  const cur = bot.blockAt(lower);
  if (cur && cur.name === o.block) return 'already';
  if (!floor || floor.boundingBox !== 'block' || INTERACTIVE.test(floor.name)) return 'no floor under the door';
  if (!(await hold(o.block))) return `cannot hold ${o.block}`;
  try {
    // A placed door faces where its placer looks.
    await bot.look(YAW[o.facing], -0.6, true);
    await sleep(150);
    await bot._genericPlace(floor, new Vec3(0, 1, 0), { forceLook: 'ignore', swingArm: 'right' });
  } catch (e) {
    return `place error: ${e.message}`;
  }
  for (let i = 0; i < 30; i++) {
    await sleep(40);
    const a = bot.blockAt(lower);
    const b = bot.blockAt(lower.offset(0, 1, 0));
    if (a && a.name === o.block && b && b.name === o.block) return 'hand';
  }
  return 'did not land';
}

async function setDoor(o) {
  const p = abs(o.d);
  await cmd(`/setblock ${p.x} ${p.y} ${p.z} ${o.block}[facing=${o.facing},half=lower]`);
  await cmd(`/setblock ${p.x} ${p.y + 1} ${p.z} ${o.block}[facing=${o.facing},half=upper]`);
}

async function build(spec) {
  const plan = planBuild(spec);
  builds.set(plan.name, plan);
  log('BUILD', plan.name, describePlan(plan));
  if (plan.clear) {
    const a = abs(plan.clear.slice(0, 3));
    const b = abs(plan.clear.slice(3));
    await cmd(`/fill ${a.x} ${a.y} ${a.z} ${b.x} ${b.y} ${b.z} air`);
  }
  if (plan.by === 'command') {
    // Parts in order (a later part overrides an earlier one, as in the plan), doors last.
    for (const part of spec.parts) {
      if (part.door) continue;
      for (const box of part.fill ? [part.fill] : wallBoxes(part.walls)) {
        const a = abs(box.slice(0, 3));
        const b = abs(box.slice(3));
        await cmd(`/fill ${a.x} ${a.y} ${a.z} ${b.x} ${b.y} ${b.z} ${part.block}`);
      }
    }
    for (const o of plan.doors) await setDoor(o);
    log('BUILD', plan.name, 'done by command:', plan.cells.length + plan.doors.length, 'placements');
    return;
  }
  for (const [block, n] of Object.entries(plan.blocks)) await cmd(`/give ${OWNER} ${block} ${n + 4}`);
  await sleep(800);
  const back = bot.entity.position.clone();
  const tally = { hand: 0, already: 0, command: 0 };
  const why = {};
  let pending = plan.cells.filter((c) => c.spot >= 0);
  const late = plan.cells.filter((c) => c.spot < 0);
  let misses = 0; // in a row; a server that takes no hand placement at all should not cost minutes
  for (let pass = 0; pass < 3 && pending.length && misses < 8; pass++) {
    const next = [];
    for (let s = 0; s < plan.spots.length; s++) {
      const mine = pending.filter((c) => c.spot === s);
      if (!mine.length) continue;
      if (misses < 8) await standAt(plan.spots[s]);
      for (const c of mine) {
        const r = misses < 8 ? await placeByHand(c.d, c.block) : 'skipped after 8 misses in a row';
        if (r === 'hand' || r === 'already') {
          tally[r]++;
          misses = 0;
        } else {
          why[r] = (why[r] || 0) + 1;
          if (/did not land|place error/.test(r)) misses++;
          next.push(c);
        }
      }
    }
    const progress = next.length < pending.length;
    pending = next;
    if (!progress) break;
  }
  for (const c of pending.concat(late)) {
    const p = abs(c.d);
    await cmd(`/setblock ${p.x} ${p.y} ${p.z} ${c.block}`);
    tally.command++;
  }
  for (const o of plan.doors) {
    let r = o.spot < 0 ? 'out of reach' : 'skipped after 8 misses in a row';
    if (o.spot >= 0 && misses < 8) {
      await standAt(plan.spots[o.spot]);
      r = await doorByHand(o);
    }
    if (r === 'hand' || r === 'already') tally[r]++;
    else {
      why[`door: ${r}`] = (why[`door: ${r}`] || 0) + 1;
      await setDoor(o);
      tally.command++;
    }
  }
  await cmd(`/tp ${OWNER} ${back.x.toFixed(2)} ${back.y.toFixed(2)} ${back.z.toFixed(2)}`);
  await sleep(500);
  const reasons = Object.entries(why).map(([r, n]) => `${r} ×${n}`).join('; ');
  log('BUILD', plan.name, `done: ${tally.hand} by hand, ${tally.command} by /setblock, ${tally.already} already there` + (reasons ? ` (hand misses: ${reasons})` : ''));
}

function check(c) {
  const label = c.label ? `${c.label}: ` : '';
  if (c.build) {
    const plan = builds.get(c.build);
    let same = 0;
    const changed = [];
    for (const cell of plan.cells) {
      const b = bot.blockAt(abs(cell.d));
      if (b && b.name === cell.block) same++;
      else changed.push(`(${cell.d.join(',')}) ${describeBlock(b)} instead of ${cell.block}`);
    }
    const doors = plan.doors.map((o) => `door (${o.d.join(',')}) ${describeBlock(bot.blockAt(abs(o.d)))} / ${describeBlock(bot.blockAt(abs([o.d[0], o.d[1] + 1, o.d[2]])))}`);
    log('CHECK', `${label}${c.build}: ${same}/${plan.cells.length} blocks as built` + (changed.length ? `; changed ${changed.length}: ${changed.slice(0, 30).join('; ')}` : '')
      + (doors.length ? `; ${doors.join('; ')}` : ''));
  }
  if (c.cells) log('CHECK', `${label}` + c.cells.map((d) => `(${d.join(',')}) ${describeBlock(bot.blockAt(abs(d)))}`).join('; '));
  if (c.companion) log('CHECK', `${label}${where()}`);
  if (c.find) {
    const type = bot.registry.blocksByName[c.find.block];
    const radius = c.find.radius || 4;
    const found = type ? bot.findBlocks({ point: abs(c.find.near), matching: type.id, maxDistance: radius, count: 20 }) : [];
    log('CHECK', `${label}${c.find.block} within ${radius} of (${c.find.near.join(',')}): ${found.length ? found.map(rel).join(' ') : 'none'}`);
  }
}

bot.once('spawn', async () => {
  log('START', new Date(start).toISOString(), path.basename(scenarioFile), 'companion', companion, 'builds', BUILD_MODE);
  log('spawned at', bot.entity.position.toString());
  setAnchor('ANCHOR (spawn)');
  const tracker = setInterval(() => log('POS', where()), 10000);
  try {
    for (const s of steps) {
      if (s.wait) await sleep(s.wait);
      if (s.anchor) setAnchor('ANCHOR');
      if (s.note) log('NOTE', s.note);
      if (s.chat) {
        const text = expand(s.chat, anchorPos);
        log('SAY', text);
        bot.chat(text);
      }
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
      if (s.build) await build(s.build);
      if (s.check) check(s.check);
      if (s.inventory) log('INVENTORY', bot.inventory.items().map((i) => `${i.name}x${i.count}`).join(', ') || 'empty');
    }
  } catch (e) {
    log('STEP FAILED', e && e.stack ? e.stack : e);
  }
  clearInterval(tracker);
  log('POS', where());
  log('INVENTORY', bot.inventory.items().map((i) => `${i.name}x${i.count}`).join(', ') || 'empty');
  bot.quit();
});
