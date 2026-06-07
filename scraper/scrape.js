#!/usr/bin/env node
/*
 * mcwd-scraper — Mineflayer auto-explorer for the Minecraft world-downloader.
 *
 * Spawns one or more bots that connect THROUGH the world-downloader proxy and walk/fly a grid so the
 * proxy passively captures every chunk in an area. Designed to automate world scraping end to end.
 *
 * Features:
 *   - Microsoft account login (prismarine-auth device-code flow) or offline accounts, per bot.
 *   - Multiple bots, each with its own account; the target grid is partitioned across them.
 *   - Gamemode-aware movement: creative/spectator fly the grid; survival/adventure walk it
 *     (mineflayer-pathfinder if installed, else a flat-world control-state fallback).
 *   - Visited-chunk dedup persisted to disk so re-runs skip already-downloaded chunks
 *     (override with revisit:true).
 *   - Area as a center+radius or an explicit block bounding box ("block x block distance format").
 *
 * Config: pass --config <file.json>, or rely on defaults + env. See config.example.json.
 *   node scrape.js --config config.json
 *
 * The proxy itself does the saving — point the bots at the proxy's host:port (NOT the real server).
 */
'use strict';

const fs = require('fs');
const path = require('path');
const mineflayer = require('mineflayer');

// Optional deps — degrade gracefully if not installed.
let pathfinderPkg = null;
try { pathfinderPkg = require('mineflayer-pathfinder'); } catch (_) { /* fallback movement */ }
let prismarineAuth = null;
try { prismarineAuth = require('prismarine-auth'); } catch (_) { /* offline only */ }

// ---------------------------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------------------------
function loadConfig() {
  const args = process.argv.slice(2);
  let cfgPath = null;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--config' || args[i] === '-c') cfgPath = args[i + 1];
  }
  let fileCfg = {};
  if (cfgPath) {
    fileCfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'));
  } else if (fs.existsSync(path.join(process.cwd(), 'config.json'))) {
    fileCfg = JSON.parse(fs.readFileSync(path.join(process.cwd(), 'config.json'), 'utf8'));
  }

  const cfg = Object.assign({
    host: process.env.SCRAPE_HOST || '127.0.0.1',
    port: parseInt(process.env.SCRAPE_PORT || '25565', 10),
    version: process.env.SCRAPE_VERSION || false,   // false => auto-detect
    // accounts: [{ auth: 'microsoft' | 'offline', username, cacheDir }]
    accounts: [{ auth: 'offline', username: 'Scraper' }],
    // area to cover, in BLOCKS. Either center+radius or explicit bbox (min/max take precedence).
    center: { x: 0, z: 0 },
    radius: 256,
    bbox: null,                  // { minX, minZ, maxX, maxZ }
    centerOnSpawn: false,        // if true, each bot covers `radius` around ITS OWN spawn point
    chunkStep: 1,                // visit every Nth chunk (1 = every chunk)
    // movement
    flyWhenAble: true,           // fly the grid in creative/spectator
    walkWhenGrounded: true,      // walk the grid in survival/adventure
    flyAltitude: 120,            // y to fly at when flying
    arriveRadius: 6,             // blocks: considered "arrived" within this XZ distance
    waypointTimeoutMs: 20000,    // give up on a waypoint after this long
    loadWaitMs: 600,             // pause at each chunk so the proxy captures it
    // dedup
    visitedFile: 'visited.json',
    revisit: false,              // ignore the visited cache and re-walk everything
    // misc
    loginStaggerMs: 4000,        // delay between starting each bot
    auth: undefined,
  }, fileCfg);

  return cfg;
}

// ---------------------------------------------------------------------------------------------
// Visited-chunk persistence (shared across bots in this run)
// ---------------------------------------------------------------------------------------------
class VisitedStore {
  constructor(file, revisit) {
    this.file = file;
    this.set = new Set();
    if (!revisit && file && fs.existsSync(file)) {
      try {
        const arr = JSON.parse(fs.readFileSync(file, 'utf8'));
        for (const k of arr) this.set.add(k);
      } catch (_) { /* start fresh */ }
    }
    this._dirty = false;
    this._timer = setInterval(() => this.flush(), 5000);
    if (this._timer.unref) this._timer.unref();
  }
  static key(cx, cz) { return cx + ',' + cz; }
  has(cx, cz) { return this.set.has(VisitedStore.key(cx, cz)); }
  add(cx, cz) { this.set.add(VisitedStore.key(cx, cz)); this._dirty = true; }
  flush() {
    if (!this._dirty || !this.file) return;
    try { fs.writeFileSync(this.file, JSON.stringify([...this.set])); this._dirty = false; } catch (_) {}
  }
}

// ---------------------------------------------------------------------------------------------
// Build the list of target chunk coordinates for the configured area
// ---------------------------------------------------------------------------------------------
function buildTargets(cfg) {
  let minCX, minCZ, maxCX, maxCZ;
  if (cfg.bbox) {
    minCX = Math.floor(cfg.bbox.minX / 16); maxCX = Math.floor(cfg.bbox.maxX / 16);
    minCZ = Math.floor(cfg.bbox.minZ / 16); maxCZ = Math.floor(cfg.bbox.maxZ / 16);
  } else {
    const cCX = Math.floor(cfg.center.x / 16), cCZ = Math.floor(cfg.center.z / 16);
    const r = Math.ceil(cfg.radius / 16);
    minCX = cCX - r; maxCX = cCX + r; minCZ = cCZ - r; maxCZ = cCZ + r;
  }
  // Spiral outward from the center so the most relevant area is covered first.
  const targets = [];
  for (let cx = minCX; cx <= maxCX; cx += cfg.chunkStep) {
    for (let cz = minCZ; cz <= maxCZ; cz += cfg.chunkStep) {
      targets.push([cx, cz]);
    }
  }
  const midX = (minCX + maxCX) / 2, midZ = (minCZ + maxCZ) / 2;
  targets.sort((a, b) => (Math.hypot(a[0] - midX, a[1] - midZ)) - (Math.hypot(b[0] - midX, b[1] - midZ)));
  return targets;
}

// ---------------------------------------------------------------------------------------------
// Account auth -> mineflayer createBot options
// ---------------------------------------------------------------------------------------------
function botOptionsFor(cfg, account, index) {
  const opts = {
    host: cfg.host,
    port: cfg.port,
    username: account.username || ('Scraper' + (index + 1)),
    hideErrors: false,
  };
  if (cfg.version) opts.version = cfg.version;

  if (account.auth === 'microsoft') {
    if (!prismarineAuth) {
      throw new Error('Microsoft login requested but prismarine-auth is not installed (npm i prismarine-auth)');
    }
    opts.auth = 'microsoft';
    // username is the MS email or a stable id; cache profiles per-account to avoid re-login.
    opts.profilesFolder = account.cacheDir || path.join(process.cwd(), '.minecraft-auth', String(account.username || index));
    fs.mkdirSync(opts.profilesFolder, { recursive: true });
    opts.onMsaCode = (data) => {
      console.log(`[bot${index + 1}] Microsoft login: open ${data.verification_uri} and enter code ${data.user_code}`);
    };
  } else {
    opts.auth = 'offline';
  }
  return opts;
}

// ---------------------------------------------------------------------------------------------
// One bot's exploration lifecycle
// ---------------------------------------------------------------------------------------------
function runBot(cfg, account, index, allTargets, botCount, visited) {
  return new Promise((resolve) => {
    const tag = `[bot${index + 1}]`;
    let bot;
    try {
      bot = mineflayer.createBot(botOptionsFor(cfg, account, index));
    } catch (e) {
      console.error(tag, 'failed to start:', e.message);
      return resolve({ index, visited: 0, error: e.message });
    }

    if (pathfinderPkg) bot.loadPlugin(pathfinderPkg.pathfinder);

    let stopped = false;
    let visitedCount = 0;
    bot.once('end', (reason) => { if (!stopped) { stopped = true; console.log(tag, 'disconnected:', reason); resolve({ index, visited: visitedCount }); } });
    bot.on('error', (e) => console.error(tag, 'error:', e && e.message ? e.message : e));
    bot.on('kicked', (r) => console.error(tag, 'kicked:', r));

    bot.once('spawn', async () => {
      const mode = bot.game.gameMode; // 'survival' | 'creative' | 'adventure' | 'spectator'
      console.log(tag, `spawned at ${vecStr(bot.entity.position)} gamemode=${mode}`);

      // Decide how this bot moves, by gamemode + settings + what's installed:
      //  - spectator: must fly (no collision); honours flyWhenAble.
      //  - creative:  walk via pathfinder if available (reliable); else fly if flyWhenAble.
      //  - survival/adventure: walk (pathfinder or flat-world fallback) when walkWhenGrounded.
      const hasPath = !!pathfinderPkg;
      let action;
      if (mode === 'spectator') {
        action = cfg.flyWhenAble ? 'fly' : 'idle';
      } else if (mode === 'creative') {
        action = hasPath ? 'walk' : (cfg.flyWhenAble ? 'fly' : 'walk');
      } else { // survival / adventure
        action = cfg.walkWhenGrounded ? 'walk' : 'idle';
      }
      if (action === 'idle') {
        console.log(tag, `gamemode ${mode} not enabled for movement in settings; idling.`);
      }

      // Choose this bot's chunk list. With centerOnSpawn each bot covers its own spawn area (full,
      // unpartitioned); otherwise bots share the pre-built grid, interleaved across the fleet.
      let mine;
      if (cfg.centerOnSpawn) {
        const sp = bot.entity.position;
        mine = buildTargets(Object.assign({}, cfg, { center: { x: sp.x, z: sp.z }, bbox: null }));
        console.log(tag, `centering on spawn ${vecStr(sp)} -> ${mine.length} chunks`);
      } else {
        mine = allTargets.filter((_, i) => i % botCount === index);
      }

      const Vec3 = require('vec3');
      let pf = null;
      if (pathfinderPkg && action === 'walk') {
        const { Movements, goals } = pathfinderPkg;
        const mcData = require('minecraft-data')(bot.version);
        const moves = new Movements(bot, mcData);
        moves.allowParkour = true; moves.canDig = false;
        bot.pathfinder.setMovements(moves);
        pf = { goals };
      }
      if (action === 'fly' && mode === 'creative' && bot.creative && bot.creative.startFlying) {
        try { bot.creative.startFlying(); } catch (_) {}
      }

      for (const [cx, cz] of mine) {
        if (stopped) break;
        if (action === 'idle') break;
        if (!cfg.revisit && visited.has(cx, cz)) continue;

        const tx = cx * 16 + 8, tz = cz * 16 + 8;
        try {
          if (action === 'fly') {
            await flyTo(bot, Vec3, tx, cfg.flyAltitude, tz, cfg, mode);
          } else {
            await walkTo(bot, pf, tx, tz, cfg);
          }
          await sleep(cfg.loadWaitMs);
        } catch (e) {
          // couldn't reach this chunk; mark visited anyway so we don't loop on it forever
        }
        visited.add(cx, cz);
        visitedCount++;
        if (visitedCount % 25 === 0) console.log(tag, `visited ${visitedCount}/${mine.length} chunks`);
      }

      visited.flush();
      console.log(tag, `done — visited ${visitedCount} chunks`);
      stopped = true;
      try { bot.quit('scrape complete'); } catch (_) {}
      resolve({ index, visited: visitedCount });
    });
  });
}

// ---- movement helpers -----------------------------------------------------------------------
function vecStr(p) { return `(${p.x.toFixed(0)}, ${p.y.toFixed(0)}, ${p.z.toFixed(0)})`; }
function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }
function distXZ(p, x, z) { return Math.hypot(p.x - x, p.z - z); }

async function flyTo(bot, Vec3, x, y, z, cfg, mode) {
  // Fly toward (x, z) at altitude y using movement controls. Works for creative (after
  // startFlying) and spectator; resolves on arrival or after the per-waypoint timeout.
  const start = Date.now();
  bot.setControlState('sprint', true);
  bot.setControlState('forward', true);
  try {
    while (Date.now() - start < cfg.waypointTimeoutMs && distXZ(bot.entity.position, x, z) > cfg.arriveRadius) {
      const p = bot.entity.position;
      await bot.lookAt(new Vec3(x, p.y, z), true);     // look horizontally so forward moves us in XZ
      bot.setControlState('jump', p.y < y - 1);        // ascend toward target altitude
      bot.setControlState('sneak', p.y > y + 1);       // descend if above
      await sleep(100);
    }
  } finally {
    bot.setControlState('forward', false);
    bot.setControlState('jump', false);
    bot.setControlState('sneak', false);
  }
}

async function walkTo(bot, pf, x, z, cfg) {
  if (pf) {
    const goal = new pf.goals.GoalNearXZ(x, z, cfg.arriveRadius);
    await Promise.race([bot.pathfinder.goto(goal), sleep(cfg.waypointTimeoutMs)]);
    bot.pathfinder.setGoal(null);
    return;
  }
  // fallback: walk forward toward the target (works on mostly-flat terrain)
  const Vec3 = require('vec3');
  const start = Date.now();
  bot.setControlState('sprint', true);
  try {
    let stuckAt = null, stuckSince = Date.now();
    while (Date.now() - start < cfg.waypointTimeoutMs && distXZ(bot.entity.position, x, z) > cfg.arriveRadius) {
      await bot.lookAt(new Vec3(x, bot.entity.position.y, z), true);
      bot.setControlState('forward', true);
      // jump if we appear stuck
      const p = bot.entity.position;
      if (stuckAt && distXZ(p, stuckAt.x, stuckAt.z) < 0.5 && Date.now() - stuckSince > 800) {
        bot.setControlState('jump', true); await sleep(150); bot.setControlState('jump', false);
        stuckSince = Date.now();
      } else if (!stuckAt || distXZ(p, stuckAt.x, stuckAt.z) >= 0.5) {
        stuckAt = { x: p.x, z: p.z }; stuckSince = Date.now();
      }
      await sleep(150);
    }
  } finally {
    bot.clearControlStates();
  }
}

// ---------------------------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------------------------
async function main() {
  const cfg = loadConfig();
  const targets = cfg.centerOnSpawn ? [] : buildTargets(cfg);
  const visited = new VisitedStore(cfg.visitedFile, cfg.revisit);
  const accounts = cfg.accounts && cfg.accounts.length ? cfg.accounts : [{ auth: 'offline', username: 'Scraper' }];

  console.log(`mcwd-scraper: ${accounts.length} bot(s) -> ${cfg.host}:${cfg.port}, ${targets.length} chunks in area` +
              (cfg.revisit ? ' (revisit: ignoring cache)' : `, ${visited.set.size} already visited`));
  if (!pathfinderPkg) console.log('note: mineflayer-pathfinder not installed — using flat-world walk fallback for survival/adventure.');

  const runs = [];
  for (let i = 0; i < accounts.length; i++) {
    runs.push(runBot(cfg, accounts[i], i, targets, accounts.length, visited));
    if (i < accounts.length - 1) await sleep(cfg.loginStaggerMs);
  }
  const results = await Promise.all(runs);
  visited.flush();
  const total = results.reduce((a, r) => a + (r.visited || 0), 0);
  console.log(`mcwd-scraper: complete — ${total} chunks visited this run, ${visited.set.size} total in cache.`);
  process.exit(0);
}

if (require.main === module) {
  main().catch((e) => { console.error('fatal:', e); process.exit(1); });
}

module.exports = { buildTargets, VisitedStore };
