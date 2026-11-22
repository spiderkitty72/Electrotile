// Electrotile web UI: level select, board interaction, progress and PWA hooks.
import { LEVELS, isRotatable, isSolved, solveCircuit, startingBoard, withRotations } from './engine.js';
import { drawTile, tileLook } from './render.js';

const ROTATE_MS = 160;
const $ = (id) => document.getElementById(id);

// ----- Progress (per browser; storage may be unavailable in private modes) -----

const STORAGE_KEY = 'electrotile.completed';

function loadCompleted() {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]');
    return new Set(Array.isArray(parsed) ? parsed : []);
  } catch {
    return new Set();
  }
}

let completed = loadCompleted();

function markCompleted(number) {
  completed.add(number);
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([...completed]));
  } catch {
    // Progress just won't persist.
  }
}

const isUnlocked = (n) => n === 1 || completed.has(n - 1) || completed.has(n);

// ----- Level select -----

function renderSelect() {
  const grid = $('level-grid');
  grid.replaceChildren();
  for (const level of LEVELS) {
    const unlocked = isUnlocked(level.number);
    const done = completed.has(level.number);
    const button = document.createElement('button');
    button.className = `level-card${done ? ' done' : ''}`;
    button.disabled = !unlocked;
    button.innerHTML = `
      <span class="level-number"></span>
      <span class="level-title"></span>
      <span class="level-state"></span>`;
    button.querySelector('.level-number').textContent = level.number;
    button.querySelector('.level-title').textContent = level.title;
    button.querySelector('.level-state').textContent = done ? '✓ lit' : unlocked ? 'play' : 'locked';
    button.addEventListener('click', () => {
      location.hash = `level-${level.number}`;
    });
    grid.append(button);
  }
}

// ----- Game -----

let game = null; // { level, start, turns, angles, board, result, solved }
let animating = false;

function openLevel(level) {
  const start = startingBoard(level);
  game = {
    level,
    start,
    turns: new Array(start.tiles.length).fill(0),
    angles: start.tiles.map((t) => t.rotation * 90),
    board: start,
    result: null,
    solved: false,
  };
  $('level-heading').textContent = `${level.number}. ${level.title}`;
  $('level-tip').textContent = level.tip;
  $('win').close?.();
  recompute();
  layoutBoard();
}

function targetAngle(i) {
  return (game.start.tiles[i].rotation + game.turns[i]) * 90;
}

function recompute() {
  const rotations = game.start.tiles.map((t, i) => t.rotation + game.turns[i]);
  game.board = withRotations(game.start, rotations);
  game.result = solveCircuit(game.board);
  const wasSolved = game.solved;
  game.solved = isSolved(game.level, game.board, game.result);
  renderRequirements();
  $('moves').textContent = `Moves ${game.turns.reduce((a, b) => a + b, 0)}`;
  if (game.solved && !wasSolved) {
    markCompleted(game.level.number);
    const level = game.level;
    setTimeout(() => {
      if (game?.level === level && game.solved) showWin();
    }, 450);
  }
}

function renderRequirements() {
  const panel = $('requirements');
  panel.replaceChildren();
  if (game.result.shortCircuit) {
    const warning = document.createElement('p');
    warning.className = 'short';
    warning.textContent = '⚡ Short circuit! The battery is wired straight back to itself.';
    panel.append(warning);
  }
  for (const requirement of game.level.requirements) {
    const status = requirement.evaluate(game.board, game.result);
    const row = document.createElement('div');
    row.className = `requirement${status.met ? ' met' : ''}`;
    row.innerHTML = '<span class="mark"></span><div><div class="desc"></div><div class="reading"></div></div>';
    row.querySelector('.mark').textContent = status.met ? '✓' : '○';
    row.querySelector('.desc').textContent = requirement.description;
    row.querySelector('.reading').textContent = `Now: ${status.reading}`;
    panel.append(row);
  }
}

function showWin() {
  const moves = game.turns.reduce((a, b) => a + b, 0);
  $('win-text').textContent = `Solved in ${moves} moves.`;
  const hasNext = LEVELS.some((l) => l.number === game.level.number + 1);
  $('win-next').textContent = hasNext ? 'Next level' : 'All levels done';
  $('win').showModal();
}

// ----- Board canvas -----

const canvas = $('board');
const ctx = canvas.getContext('2d');
let tileSize = 0;

function layoutBoard() {
  if (!game) return;
  const wrap = $('board-wrap');
  const { width, height } = game.board;
  const available = wrap.getBoundingClientRect();
  tileSize = Math.max(24, Math.floor(Math.min(available.width / width, available.height / height, 120)));
  const dpr = window.devicePixelRatio || 1;
  canvas.style.width = `${tileSize * width}px`;
  canvas.style.height = `${tileSize * height}px`;
  canvas.width = Math.round(tileSize * width * dpr);
  canvas.height = Math.round(tileSize * height * dpr);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  draw();
}

function draw() {
  if (!game || tileSize === 0) return;
  const { width, tiles } = game.board;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  tiles.forEach((tile, i) => {
    const x = (i % width) * tileSize;
    const y = Math.floor(i / width) * tileSize;
    drawTile(ctx, tile, x, y, tileSize, game.angles[i], tileLook(tile, i, game.result));
  });
}

let lastFrame = 0;
function animate(now) {
  const dt = lastFrame ? now - lastFrame : 16;
  lastFrame = now;
  const step = (90 * dt) / ROTATE_MS;
  let moving = false;
  game.angles = game.angles.map((angle, i) => {
    const target = targetAngle(i);
    if (angle === target) return angle;
    moving = true;
    return angle < target ? Math.min(target, angle + step) : Math.max(target, angle - step);
  });
  draw();
  if (moving) {
    requestAnimationFrame(animate);
  } else {
    animating = false;
    lastFrame = 0;
  }
}

function startAnimation() {
  if (animating) return;
  animating = true;
  requestAnimationFrame(animate);
}

function rotateTile(i) {
  if (!game || game.solved || !isRotatable(game.start.tiles[i])) return;
  game.turns[i] += 1;
  navigator.vibrate?.(8);
  recompute();
  startAnimation();
}

canvas.addEventListener('click', (event) => {
  if (!game) return;
  const rect = canvas.getBoundingClientRect();
  const x = Math.floor((event.clientX - rect.left) / tileSize);
  const y = Math.floor((event.clientY - rect.top) / tileSize);
  if (x < 0 || y < 0 || x >= game.board.width || y >= game.board.height) return;
  rotateTile(y * game.board.width + x);
});

new ResizeObserver(() => layoutBoard()).observe($('board-wrap'));

// ----- Routing: #level-N opens a level, anything else shows the level select -----

function route() {
  const match = /^#level-(\d+)$/.exec(location.hash);
  const level = match && LEVELS.find((l) => l.number === Number(match[1]));
  if (level && isUnlocked(level.number)) {
    $('select-screen').hidden = true;
    $('game-screen').hidden = false;
    openLevel(level);
  } else {
    game = null;
    $('win').close?.();
    $('game-screen').hidden = true;
    $('select-screen').hidden = false;
    renderSelect();
  }
}

window.addEventListener('hashchange', route);

$('back').addEventListener('click', () => {
  location.hash = '';
});
$('reset').addEventListener('click', () => {
  if (!game) return;
  game.turns.fill(0);
  game.solved = false;
  recompute();
  startAnimation();
});
$('win-next').addEventListener('click', () => {
  const next = LEVELS.find((l) => l.number === game.level.number + 1);
  location.hash = next ? `level-${next.number}` : '';
});
$('win-levels').addEventListener('click', () => {
  location.hash = '';
});

// ----- PWA: offline support and install button -----

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('sw.js').catch(() => {
      // Offline play is a bonus; the game still works without it.
    });
  });
}

let installPrompt = null;
window.addEventListener('beforeinstallprompt', (event) => {
  event.preventDefault();
  installPrompt = event;
  $('install').hidden = false;
});
$('install').addEventListener('click', async () => {
  if (!installPrompt) return;
  installPrompt.prompt();
  await installPrompt.userChoice;
  installPrompt = null;
  $('install').hidden = true;
});
window.addEventListener('appinstalled', () => {
  $('install').hidden = true;
});

route();
