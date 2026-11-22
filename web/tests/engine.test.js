import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  Dir, Kind, LEVELS, isRotatable, isSolved, parseBoard, rotationsOf, solveCircuit, startingBoard,
  terminalA, terminalB, tilePorts, withRotations,
} from '../js/engine.js';

const near = (expected, actual, tolerance = 5e-3) =>
  assert.ok(Math.abs(expected - actual) <= tolerance, `expected ${expected} but was ${actual}`);
const idx = (board, x, y) => y * board.width + x;
const turned = (board, x, y, rotation) => {
  const r = rotationsOf(board);
  r[idx(board, x, y)] = rotation;
  return withRotations(board, r);
};

test('series loop follows Ohm\'s law', () => {
  const board = parseBoard(`
    L1    I1  L2
    V0:6  .   R0:60
    L0    I1  L3
  `);
  const result = solveCircuit(board);
  near(0.1, result.sourceCurrent);
  near(0.1, Math.abs(result.componentCurrent[idx(board, 2, 1)]));
  near(6.0, Math.abs(result.componentVoltage[idx(board, 2, 1)]));
  assert.equal(result.shortCircuit, false);
  near(0.1, result.armCurrent[idx(board, 1, 0)][Dir.E]);
});

test('open loop carries no current', () => {
  const board = parseBoard(`
    L1    I0  L2
    V0:6  .   R0:60
    L0    I1  L3
  `);
  near(0, solveCircuit(board).sourceCurrent, 1e-5);
});

test('wire across the battery is a short', () => {
  const board = parseBoard(`
    L1    L2
    V0:6  I0
    L0    L3
  `);
  assert.equal(solveCircuit(board).shortCircuit, true);
});

test('diode conducts only forward', () => {
  const forward = parseBoard(`
    L1    D3  L2
    V0:9  .   R0:83
    L0    I1  L3
  `);
  near(0.1, solveCircuit(forward).sourceCurrent, 2e-3);
  near(0, solveCircuit(turned(forward, 1, 0, 1)).sourceCurrent, 1e-5);
});

test('capacitor charges to the divider voltage', () => {
  const board = parseBoard(`
    L1    R1:100  T1      L2
    V0:9  .       R0:200  C0
    L0    I1      T3      L3
  `);
  const result = solveCircuit(board);
  near(6.0, Math.abs(result.componentVoltage[idx(board, 3, 1)]));
  near(0.03, result.sourceCurrent);
});

test('rotation turns ports clockwise', () => {
  assert.deepEqual(tilePorts({ kind: Kind.WIRE_CORNER, rotation: 1 }), [Dir.E, Dir.S]);
  const diode = { kind: Kind.DIODE, rotation: 3 };
  assert.equal(terminalA(diode), Dir.W);
  assert.equal(terminalB(diode), Dir.E);
});

test('every level solution meets its requirements', () => {
  for (const level of LEVELS) {
    const result = solveCircuit(level.solution);
    const statuses = level.requirements.map((r) => r.evaluate(level.solution, result));
    assert.ok(isSolved(level, level.solution), `Level ${level.number} fails: ${JSON.stringify(statuses)}`);
  }
});

test('starting boards are scrambled only by rotation', () => {
  for (const level of LEVELS) {
    const start = startingBoard(level);
    assert.equal(isSolved(level, start), false, `Level ${level.number} starts solved`);
    level.solution.tiles.forEach((solved, i) => {
      const scrambled = start.tiles[i];
      assert.equal(scrambled.kind, solved.kind);
      assert.equal(scrambled.value, solved.value);
      if (!isRotatable(solved)) assert.equal(scrambled.rotation, solved.rotation);
    });
  }
});

test('levels are numbered in order', () => {
  assert.deepEqual(LEVELS.map((l) => l.number), LEVELS.map((_, i) => i + 1));
});

test('obvious wrong answers fail', () => {
  const tooBright = LEVELS[1];
  assert.equal(isSolved(tooBright, turned(tooBright.solution, 1, 3, 1)), false);
  const divider = LEVELS[3];
  assert.equal(isSolved(divider, turned(divider.solution, 3, 1, 0)), false);
});
