// Electrotile game engine: board model, DC circuit solver, level targets and levels.
// Plain ES module with no DOM access, so it runs in the browser and under `node --test`.

/** Tile edges in clockwise order. */
export const Dir = Object.freeze({ N: 0, E: 1, S: 2, W: 3 });
const DX = [0, 1, 0, -1];
const DY = [-1, 0, 1, 0];

export const rotateDir = (d, quarterTurns) => (((d + quarterTurns) % 4) + 4) % 4;
export const oppositeDir = (d) => rotateDir(d, 2);

/**
 * Component kinds. Wires join all their ports into one conductor. Every other non-empty kind
 * is a two-terminal part whose terminal A faces north and terminal B faces south at rotation 0.
 * For polarised parts A is the "positive" side: battery +, diode anode, meter + probe.
 */
export const Kind = Object.freeze({
  EMPTY: { name: 'EMPTY', ports: [], wire: false, symmetry: 1 },
  WIRE_STRAIGHT: { name: 'WIRE_STRAIGHT', ports: [Dir.N, Dir.S], wire: true, symmetry: 2 },
  WIRE_CORNER: { name: 'WIRE_CORNER', ports: [Dir.N, Dir.E], wire: true, symmetry: 4 },
  WIRE_TEE: { name: 'WIRE_TEE', ports: [Dir.N, Dir.E, Dir.S], wire: true, symmetry: 4 },
  WIRE_CROSS: { name: 'WIRE_CROSS', ports: [Dir.N, Dir.E, Dir.S, Dir.W], wire: true, symmetry: 1 },
  RESISTOR: { name: 'RESISTOR', ports: [Dir.N, Dir.S], wire: false, symmetry: 2 },
  CAPACITOR: { name: 'CAPACITOR', ports: [Dir.N, Dir.S], wire: false, symmetry: 2 },
  DIODE: { name: 'DIODE', ports: [Dir.N, Dir.S], wire: false, symmetry: 4 },
  LAMP: { name: 'LAMP', ports: [Dir.N, Dir.S], wire: false, symmetry: 2 },
  BATTERY: { name: 'BATTERY', ports: [Dir.N, Dir.S], wire: false, symmetry: 4 },
  METER: { name: 'METER', ports: [Dir.N, Dir.S], wire: false, symmetry: 4 },
});

export const isTwoTerminal = (kind) => kind !== Kind.EMPTY && !kind.wire;
export const isRotatable = (tile) => !tile.fixed && tile.kind.symmetry > 1;
export const tilePorts = (tile) => tile.kind.ports.map((d) => rotateDir(d, tile.rotation));
export const terminalA = (tile) => rotateDir(Dir.N, tile.rotation);
export const terminalB = (tile) => rotateDir(Dir.S, tile.rotation);

/** A board is { width, height, tiles } with tiles stored row-major. */
export function withRotations(board, rotations) {
  return {
    width: board.width,
    height: board.height,
    tiles: board.tiles.map((t, i) => ({ ...t, rotation: (((rotations[i] % 4) + 4) % 4) })),
  };
}

export const rotationsOf = (board) => board.tiles.map((t) => t.rotation);

// ---------------------------------------------------------------------------------------------
// Circuit solver
// ---------------------------------------------------------------------------------------------

export const WIRE_RESISTANCE = 1e-3;
export const DIODE_FORWARD_DROP = 0.7;
export const DIODE_ON_RESISTANCE = 0.05;
export const SHORT_CIRCUIT_CURRENT = 5.0;
const GMIN = 1e-9;
const MAX_DIODE_ITERATIONS = 40;

/**
 * Modified nodal analysis of the tile grid. Every tile edge with a port is a node and touching
 * ports on neighbouring tiles are merged. Wire tiles join their ports to a centre node through
 * a tiny resistance so the current in each arm can be drawn. Capacitors are open at DC; diodes
 * use a piecewise-linear model iterated until every diode's on/off state is consistent.
 *
 * Returns {
 *   componentCurrent[i]  current A→B through a two-terminal part (amps),
 *   componentVoltage[i]  V(A) − V(B) across it (volts),
 *   armCurrent[i][dir]   magnitude of the current through that edge of tile i,
 *   sourceCurrent        current the battery pushes out of its + terminal,
 *   shortCircuit         true when the battery is (nearly) shorted,
 * }
 */
export function solveCircuit(board) {
  const { width, height, tiles } = board;
  const n = tiles.length;
  const portTerminal = tiles.map(() => [-1, -1, -1, -1]);
  const centreTerminal = new Array(n).fill(-1);
  let terminals = 0;
  tiles.forEach((tile, i) => {
    for (const d of tilePorts(tile)) portTerminal[i][d] = terminals++;
    if (tile.kind.wire) centreTerminal[i] = terminals++;
  });

  const parent = Array.from({ length: terminals }, (_, k) => k);
  const find = (x) => {
    while (parent[x] !== x) {
      parent[x] = parent[parent[x]];
      x = parent[x];
    }
    return x;
  };
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = y * width + x;
      for (const d of tilePorts(tiles[i])) {
        const nx = x + DX[d];
        const ny = y + DY[d];
        if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
        const other = portTerminal[ny * width + nx][oppositeDir(d)];
        if (other >= 0) parent[find(portTerminal[i][d])] = find(other);
      }
    }
  }

  const nodeOfRoot = new Map();
  const nodeOf = new Array(terminals);
  for (let t = 0; t < terminals; t++) {
    const r = find(t);
    if (!nodeOfRoot.has(r)) nodeOfRoot.set(r, nodeOfRoot.size);
    nodeOf[t] = nodeOfRoot.get(r);
  }
  const nodeCount = nodeOfRoot.size;

  const nodeA = (i) => nodeOf[portTerminal[i][terminalA(tiles[i])]];
  const nodeB = (i) => nodeOf[portTerminal[i][terminalB(tiles[i])]];

  const conductors = []; // { a, b, g, tile, arm }
  const diodes = [];
  let battery = -1;
  tiles.forEach((tile, i) => {
    if (tile.kind.wire) {
      for (const d of tilePorts(tile)) {
        conductors.push({ a: nodeOf[portTerminal[i][d]], b: nodeOf[centreTerminal[i]], g: 1 / WIRE_RESISTANCE, tile: i, arm: d });
      }
    } else if (tile.kind === Kind.RESISTOR || tile.kind === Kind.LAMP || tile.kind === Kind.METER) {
      conductors.push({ a: nodeA(i), b: nodeB(i), g: 1 / Math.max(tile.value, WIRE_RESISTANCE), tile: i, arm: null });
    } else if (tile.kind === Kind.DIODE) {
      diodes.push(i);
    } else if (tile.kind === Kind.BATTERY && battery < 0) {
      battery = i;
    } // Capacitor: open circuit at DC.
  });

  const ground = battery >= 0 ? nodeB(battery) : 0;
  const diodeOn = new Array(diodes.length).fill(false);
  let voltages = new Array(nodeCount).fill(0);
  let batteryCurrent = 0;

  for (let iteration = 0; iteration < MAX_DIODE_ITERATIONS; iteration++) {
    [voltages, batteryCurrent] = solveNetwork(nodeCount, ground, conductors, diodes, diodeOn, nodeA, nodeB, battery, tiles);
    let changed = false;
    diodes.forEach((d, k) => {
      const drop = voltages[nodeA(d)] - voltages[nodeB(d)];
      const shouldBeOn = diodeOn[k] ? drop >= DIODE_FORWARD_DROP : drop > DIODE_FORWARD_DROP + 1e-9;
      if (shouldBeOn !== diodeOn[k]) {
        diodeOn[k] = shouldBeOn;
        changed = true;
      }
    });
    if (!changed) break;
  }

  const componentCurrent = new Array(n).fill(0);
  const componentVoltage = new Array(n).fill(0);
  const armCurrent = tiles.map(() => [0, 0, 0, 0]);
  for (const c of conductors) {
    const current = (voltages[c.a] - voltages[c.b]) * c.g;
    if (c.arm !== null) armCurrent[c.tile][c.arm] = Math.abs(current);
    else componentCurrent[c.tile] = current;
  }
  diodes.forEach((d, k) => {
    const drop = voltages[nodeA(d)] - voltages[nodeB(d)];
    componentCurrent[d] = diodeOn[k] ? Math.max(0, (drop - DIODE_FORWARD_DROP) / DIODE_ON_RESISTANCE) : 0;
  });
  if (battery >= 0) componentCurrent[battery] = -batteryCurrent;
  tiles.forEach((tile, i) => {
    if (!isTwoTerminal(tile.kind)) return;
    componentVoltage[i] = voltages[nodeA(i)] - voltages[nodeB(i)];
    const magnitude = Math.abs(componentCurrent[i]);
    for (const d of tilePorts(tile)) armCurrent[i][d] = magnitude;
  });

  return {
    componentCurrent,
    componentVoltage,
    armCurrent,
    sourceCurrent: batteryCurrent,
    shortCircuit: battery >= 0 && Math.abs(batteryCurrent) > SHORT_CIRCUIT_CURRENT,
  };
}

/** Returns [node voltages, current the battery delivers from its + terminal]. */
function solveNetwork(nodeCount, ground, conductors, diodes, diodeOn, nodeA, nodeB, battery, tiles) {
  const index = new Array(nodeCount).fill(-1);
  let size = 0;
  for (let k = 0; k < nodeCount; k++) if (k !== ground) index[k] = size++;
  const sourceRow = battery >= 0 ? size++ : -1;
  if (size === 0) return [new Array(nodeCount).fill(0), 0];

  const a = Array.from({ length: size }, () => new Float64Array(size));
  const rhs = new Float64Array(size);
  const stamp = (p, q, g) => {
    const ip = index[p];
    const iq = index[q];
    if (ip >= 0) a[ip][ip] += g;
    if (iq >= 0) a[iq][iq] += g;
    if (ip >= 0 && iq >= 0) {
      a[ip][iq] -= g;
      a[iq][ip] -= g;
    }
  };

  for (let k = 0; k < nodeCount; k++) if (index[k] >= 0) a[index[k]][index[k]] += GMIN;
  for (const c of conductors) stamp(c.a, c.b, c.g);
  diodes.forEach((d, k) => {
    if (!diodeOn[k]) return;
    const g = 1 / DIODE_ON_RESISTANCE;
    const anode = nodeA(d);
    const cathode = nodeB(d);
    stamp(anode, cathode, g);
    if (index[anode] >= 0) rhs[index[anode]] += g * DIODE_FORWARD_DROP;
    if (index[cathode] >= 0) rhs[index[cathode]] -= g * DIODE_FORWARD_DROP;
  });
  if (battery >= 0) {
    const plus = index[nodeA(battery)];
    const minus = index[nodeB(battery)];
    if (plus >= 0) {
      a[plus][sourceRow] += 1;
      a[sourceRow][plus] += 1;
    }
    if (minus >= 0) {
      a[minus][sourceRow] -= 1;
      a[sourceRow][minus] -= 1;
    }
    rhs[sourceRow] = tiles[battery].value;
  }

  const x = solveLinear(a, rhs);
  const voltages = Array.from({ length: nodeCount }, (_, k) => (index[k] >= 0 ? x[index[k]] : 0));
  // The MNA unknown is the current flowing into the + terminal; the battery delivers the opposite.
  return [voltages, sourceRow >= 0 ? -x[sourceRow] : 0];
}

/** Gaussian elimination with partial pivoting. Singular pivots resolve to zero. */
function solveLinear(a, b) {
  const n = b.length;
  for (let col = 0; col < n; col++) {
    let pivot = col;
    for (let r = col + 1; r < n; r++) if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) pivot = r;
    if (Math.abs(a[pivot][col]) < 1e-15) continue;
    if (pivot !== col) {
      [a[pivot], a[col]] = [a[col], a[pivot]];
      [b[pivot], b[col]] = [b[col], b[pivot]];
    }
    const p = a[col][col];
    const pivotRow = a[col];
    for (let r = col + 1; r < n; r++) {
      const f = a[r][col] / p;
      if (f === 0) continue;
      const row = a[r];
      for (let c = col; c < n; c++) row[c] -= f * pivotRow[c];
      b[r] -= f * b[col];
    }
  }
  const x = new Float64Array(n);
  for (let r = n - 1; r >= 0; r--) {
    if (Math.abs(a[r][r]) < 1e-15) continue;
    let s = b[r];
    for (let c = r + 1; c < n; c++) s -= a[r][c] * x[c];
    x[r] = s / a[r][r];
  }
  return x;
}

// ---------------------------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------------------------

export function formatCurrent(amps) {
  const a = Math.abs(amps) < 5e-7 ? 0 : amps;
  return Math.abs(a) >= 1 ? `${a.toFixed(2)} A` : `${(a * 1000).toFixed(0)} mA`;
}

export function formatVoltage(volts) {
  const v = Math.abs(volts) < 5e-4 ? 0 : volts;
  return `${v.toFixed(2)} V`;
}

export function formatResistance(ohms) {
  return ohms >= 1000 ? `${(ohms / 1000).toFixed(0)}kΩ` : `${ohms.toFixed(0)}Ω`;
}

function formatRange(min, max, format) {
  const lo = format(min);
  const hi = format(max);
  const unit = (s) => s.slice(s.indexOf(' ') + 1);
  return unit(lo) === unit(hi) ? `${lo.slice(0, lo.indexOf(' '))}–${hi}` : `${lo}–${hi}`;
}

// ---------------------------------------------------------------------------------------------
// Requirements: each has a description and evaluate(board, result) → { met, reading }
// ---------------------------------------------------------------------------------------------

const indicesOf = (board, kind) => board.tiles.flatMap((t, i) => (t.kind === kind ? [i] : []));
const inRange = (v, min, max) => v >= min && v <= max;

export const Requirement = {
  /** Every lamp carries a current inside its own window: lit, but not burnt out. */
  lightAllLamps() {
    return {
      description: 'Light every lamp without burning it out',
      evaluate(board, result) {
        const readings = indicesOf(board, Kind.LAMP).map((i) => {
          const tile = board.tiles[i];
          const current = Math.abs(result.componentCurrent[i]);
          return {
            ok: inRange(current, tile.minCurrent, tile.maxCurrent),
            text: `${formatCurrent(current)} (needs ${formatRange(tile.minCurrent, tile.maxCurrent, formatCurrent)})`,
          };
        });
        return {
          met: !result.shortCircuit && readings.length > 0 && readings.every((r) => r.ok),
          reading: readings.map((r) => r.text).join(' · '),
        };
      },
    };
  },

  /** Voltage between the output meter's + and − probes lies in a window. */
  outputVoltage(min, max) {
    return {
      description: `Output reads ${formatRange(min, max, formatVoltage)}`,
      evaluate(board, result) {
        const meter = board.tiles.findIndex((t) => t.kind === Kind.METER);
        if (meter < 0) return { met: false, reading: 'no meter' };
        const v = result.componentVoltage[meter];
        return { met: !result.shortCircuit && inRange(v, min, max), reading: formatVoltage(v) };
      },
    };
  },

  /** Current drawn from the battery lies in a window. */
  batteryCurrent(min, max) {
    return {
      description: `Battery supplies ${formatRange(min, max, formatCurrent)}`,
      evaluate(board, result) {
        const i = result.sourceCurrent;
        return { met: !result.shortCircuit && inRange(i, min, max), reading: formatCurrent(i) };
      },
    };
  },

  /** Every capacitor charges to a voltage (either polarity) inside a window. */
  chargeCapacitors(min, max) {
    return {
      description: `Charge the capacitor to ${formatRange(min, max, formatVoltage)}`,
      evaluate(board, result) {
        const volts = indicesOf(board, Kind.CAPACITOR).map((i) => Math.abs(result.componentVoltage[i]));
        return {
          met: !result.shortCircuit && volts.length > 0 && volts.every((v) => inRange(v, min, max)),
          reading: volts.map(formatVoltage).join(' · '),
        };
      },
    };
  },
};

// ---------------------------------------------------------------------------------------------
// Levels
// ---------------------------------------------------------------------------------------------

const CODES = {
  '.': Kind.EMPTY,
  I: Kind.WIRE_STRAIGHT,
  L: Kind.WIRE_CORNER,
  T: Kind.WIRE_TEE,
  '+': Kind.WIRE_CROSS,
  R: Kind.RESISTOR,
  C: Kind.CAPACITOR,
  D: Kind.DIODE,
  B: Kind.LAMP,
  V: Kind.BATTERY,
  M: Kind.METER,
};

/**
 * Parses a level layout. Tiles are separated by whitespace, rows by newlines.
 *
 * Token format: `<code><rotation>[!][:param...]`, where rotation is 0–3 clockwise quarter turns
 * and `!` pins the tile. Codes: `.` empty, `I` straight wire, `L` corner, `T` tee, `+` cross,
 * `R<rot>:ohms` resistor, `C` capacitor, `D` diode (anode on side A),
 * `B<rot>:ohms:minAmps:maxAmps` lamp, `V<rot>:volts` battery (always fixed),
 * `M<rot>:ohms` output meter (+ probe on side A).
 */
export function parseBoard(layout) {
  const rows = layout
    .split('\n')
    .map((r) => r.trim())
    .filter((r) => r.length > 0)
    .map((r) => r.split(/\s+/).map(parseTile));
  const width = rows[0].length;
  if (!rows.every((r) => r.length === width)) throw new Error(`Ragged layout:\n${layout}`);
  return { width, height: rows.length, tiles: rows.flat() };
}

function parseTile(token) {
  const [head, ...params] = token.split(':');
  const kind = CODES[head[0]];
  if (!kind) throw new Error(`Unknown tile code in '${token}'`);
  const digit = /^\d$/.test(head[1] ?? '') ? Number(head[1]) : 0;
  const values = params.map(Number);
  return {
    kind,
    rotation: digit,
    fixed: head.endsWith('!') || kind === Kind.BATTERY,
    value: values[0] ?? 0,
    minCurrent: values[1] ?? 0,
    maxCurrent: values[2] ?? Number.MAX_VALUE,
  };
}

export function isSolved(level, board, result = solveCircuit(board)) {
  return !result.shortCircuit && level.requirements.every((r) => r.evaluate(board, result).met);
}

/** Small seeded PRNG (mulberry32) so every player gets the same scramble. */
function seededRandom(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** The level's board with every rotatable tile turned to a random orientation, never solved. */
export function startingBoard(level) {
  const random = seededRandom(level.number * 7919 + 17);
  for (let attempt = 0; attempt < 100; attempt++) {
    const rotations = level.solution.tiles.map((t) => (isRotatable(t) ? Math.floor(random() * 4) : t.rotation));
    const board = withRotations(level.solution, rotations);
    if (!isSolved(level, board)) return board;
  }
  throw new Error(`Could not scramble level ${level.number}`);
}

const level = (number, title, tip, layout, requirements) => ({
  number,
  title,
  tip,
  solution: parseBoard(layout),
  requirements,
});

export const LEVELS = [
  level(1, 'First Light', 'Tap tiles to turn them. Join the battery to the lamp in a closed loop.', `
    L1    I1  L2
    V0:6  .   B0:60:0.05:0.2
    L0    I1  L3
  `, [Requirement.lightAllLamps()]),
  level(2, 'Too Bright', 'Straight wire lets too much current through. Make it flow through the resistor instead.', `
    L1    I1  I1     L2
    V0:9  .   .      B0:30:0.08:0.15
    T0    I1  R1:60  T2
    L0    I0  I1     L3
  `, [Requirement.lightAllLamps()]),
  level(3, 'One Way Street', 'Diodes pass current only in the direction of their arrow, from + round to −.', `
    L1    D3  L2
    V0:9  .   B0:30:0.1:0.3
    L0    D1  L3
  `, [Requirement.lightAllLamps()]),
  level(4, 'Divide and Conquer', "Two resistors in series split the voltage. Pick the branches that set the output just right, and mind the meter's + side.", `
    L1    R1:200  T1      T1      L2
    V0:9  .       R0:100  R1:300  M0:10000
    L0    I1      T3      T3      L3
  `, [Requirement.outputVoltage(2.8, 3.2)]),
  level(5, 'Current Budget', 'Resistors side by side each add their own current. Connect the set that adds up to the target.', `
    L1     T1      T1      T1      L2
    V0:12  R0:120  R0:240  R1:400  R1:600
    L0     T3      T3      T3      L3
  `, [Requirement.batteryCurrent(0.145, 0.155)]),
  level(6, 'Charge Up', 'A charged capacitor passes no current. The diode in front of it costs 0.7 V.', `
    L1    R1:100  T1      T1      L2
    V0:9  .       R1:100  R0:200  D0
    I0    .       I0      I0      C0
    L0    I1      T3      T3      L3
  `, [Requirement.chargeCapacitors(5.0, 5.6)]),
  level(7, 'Workbench', 'Two circuits share one battery. Light the lamp and set the output at the same time.', `
    L1     T1               I1     L2      .       .
    V0:12  B0:40:0.11:0.15  .      R0:100  .       .
    I0     T0               L2     T0      T1      L2
    I0     R1:30            R0:60  R0:100  R0:200  M0:10000
    L0     T3               T3     T3      T3      L3
  `, [Requirement.lightAllLamps(), Requirement.outputVoltage(4.6, 5.0)]),
];
