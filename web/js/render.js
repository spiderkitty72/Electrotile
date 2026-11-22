// Canvas drawing for board tiles.
import { Dir, Kind, isRotatable, formatResistance } from './engine.js';

export const Palette = {
  tile: '#1b2e25',
  tileFixed: '#101d17',
  tileEdge: '#2a4236',
  wireIdle: '#5f7369',
  wireLive: '#ffc94d',
  glow: 'rgba(255, 224, 130, 0.25)',
  glowSolid: '#ffe082',
  charge: '#4dd0e1',
  danger: '#ff5252',
  textMuted: '#9db3a8',
};

const DX = [0, 1, 0, -1];
const DY = [-1, 0, 1, 0];
const LIVE_CURRENT = 1e-4;

/** What the simulation says about one tile, shaped for the renderer. */
export function tileLook(tile, index, result) {
  const arms = [0, 1, 2, 3].map((d) => result.armCurrent[index][d] > LIVE_CURRENT && !result.shortCircuit);
  const current = Math.abs(result.componentCurrent[index]);
  let lamp = 'off';
  if (tile.kind === Kind.LAMP) {
    if (result.shortCircuit || current > tile.maxCurrent) lamp = current > LIVE_CURRENT ? 'blown' : 'off';
    else if (current >= tile.minCurrent) lamp = 'lit';
    else if (current > 1e-3) lamp = 'dim';
  }
  const charge = tile.kind === Kind.CAPACITOR && !result.shortCircuit ? Math.abs(result.componentVoltage[index]) : 0;
  return { arms, live: arms.some(Boolean), lamp, charge };
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

/**
 * Draws `tile` into the square at (ox, oy) of side s. The component is drawn in its rotation-0
 * pose (terminal A at the top) and the context is turned by `angle` degrees, so taps animate.
 */
export function drawTile(ctx, tile, ox, oy, s, angle, look) {
  const inset = s * 0.03;
  ctx.save();
  ctx.translate(ox, oy);
  ctx.fillStyle = isRotatable(tile) ? Palette.tile : Palette.tileFixed;
  roundRect(ctx, inset, inset, s - 2 * inset, s - 2 * inset, s * 0.12);
  ctx.fill();
  if (tile.kind === Kind.EMPTY) {
    ctx.restore();
    return;
  }

  const c = s / 2;
  const h = s / 2;
  const stroke = s * 0.09;
  const thin = stroke * 0.6;
  const color = (live) => (live ? Palette.wireLive : Palette.wireIdle);
  const edge = (d) => [c + DX[d] * h, c + DY[d] * h];
  const liveSide = (base) => look.arms[(base + tile.rotation) % 4];

  const line = (x1, y1, x2, y2, live, width = stroke) => {
    ctx.lineCap = 'round';
    if (live) {
      ctx.strokeStyle = Palette.glow;
      ctx.lineWidth = width * 2.4;
      ctx.beginPath();
      ctx.moveTo(x1, y1);
      ctx.lineTo(x2, y2);
      ctx.stroke();
    }
    ctx.strokeStyle = color(live);
    ctx.lineWidth = width;
    ctx.beginPath();
    ctx.moveTo(x1, y1);
    ctx.lineTo(x2, y2);
    ctx.stroke();
  };
  const plate = (x1, x2, y, width, style) => {
    ctx.strokeStyle = style;
    ctx.lineWidth = width;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(x1, y);
    ctx.lineTo(x2, y);
    ctx.stroke();
  };
  const sign = (text, x, y, style, size = s * 0.16) => {
    ctx.fillStyle = style;
    ctx.font = `bold ${size}px system-ui, sans-serif`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(text, x, y);
  };
  const circle = (r, fill, strokeStyle) => {
    ctx.beginPath();
    ctx.arc(c, c, r, 0, Math.PI * 2);
    if (fill) {
      ctx.fillStyle = fill;
      ctx.fill();
    }
    if (strokeStyle) {
      ctx.strokeStyle = strokeStyle;
      ctx.lineWidth = thin;
      ctx.stroke();
    }
  };

  ctx.save();
  ctx.translate(c, c);
  ctx.rotate((angle * Math.PI) / 180);
  ctx.translate(-c, -c);

  const live = look.live;
  const [nx, ny] = edge(Dir.N);
  const [sx, sy] = edge(Dir.S);

  switch (tile.kind) {
    case Kind.WIRE_STRAIGHT:
    case Kind.WIRE_CORNER:
    case Kind.WIRE_TEE:
    case Kind.WIRE_CROSS: {
      for (const port of tile.kind.ports) {
        const [ex, ey] = edge(port);
        line(c, c, ex, ey, liveSide(port));
      }
      ctx.fillStyle = color(live);
      ctx.beginPath();
      ctx.arc(c, c, stroke * 0.75, 0, Math.PI * 2);
      ctx.fill();
      break;
    }
    case Kind.RESISTOR: {
      const top = c - h * 0.42;
      const bottom = c + h * 0.42;
      line(nx, ny, c, top, live);
      line(c, bottom, sx, sy, live);
      ctx.strokeStyle = color(live);
      ctx.lineWidth = thin;
      ctx.lineJoin = 'round';
      ctx.beginPath();
      ctx.moveTo(c, top);
      const steps = 6;
      for (let k = 1; k <= steps; k++) {
        const y = top + ((bottom - top) * k) / steps;
        const x = k === steps ? c : c + (k % 2 === 1 ? 1 : -1) * h * 0.28;
        ctx.lineTo(x, y);
      }
      ctx.stroke();
      break;
    }
    case Kind.CAPACITOR: {
      const gap = h * 0.12;
      line(nx, ny, c, c - gap, live);
      line(c, c + gap, sx, sy, live);
      const charged = look.charge > 0.05;
      if (charged) {
        ctx.globalAlpha = Math.min(0.6, Math.max(0.15, look.charge / 10));
        ctx.fillStyle = Palette.charge;
        ctx.fillRect(c - h * 0.4, c - gap, h * 0.8, gap * 2);
        ctx.globalAlpha = 1;
      }
      const style = charged ? Palette.charge : Palette.wireIdle;
      plate(c - h * 0.45, c + h * 0.45, c - gap, thin * 1.4, style);
      plate(c - h * 0.45, c + h * 0.45, c + gap, thin * 1.4, style);
      break;
    }
    case Kind.DIODE: {
      const top = c - h * 0.3;
      const bottom = c + h * 0.3;
      line(nx, ny, c, top, live);
      line(c, bottom, sx, sy, live);
      // Arrow points from anode (A, top) to cathode (B, bottom).
      ctx.beginPath();
      ctx.moveTo(c - h * 0.34, top);
      ctx.lineTo(c + h * 0.34, top);
      ctx.lineTo(c, bottom);
      ctx.closePath();
      ctx.fillStyle = live ? Palette.wireLive : Palette.tileEdge;
      ctx.fill();
      ctx.strokeStyle = color(live);
      ctx.lineWidth = thin;
      ctx.lineJoin = 'round';
      ctx.stroke();
      plate(c - h * 0.34, c + h * 0.34, bottom, thin * 1.3, color(live));
      break;
    }
    case Kind.LAMP: {
      const r = h * 0.42;
      line(nx, ny, c, c - r, live);
      line(c, c + r, sx, sy, live);
      const glow = { lit: [255, 224, 130, 1], dim: [255, 224, 130, 0.35], blown: [255, 82, 82, 1] }[look.lamp];
      if (glow) {
        const [rr, gg, bb, aa] = glow;
        const gradient = ctx.createRadialGradient(c, c, 0, c, c, h);
        gradient.addColorStop(0, `rgba(${rr},${gg},${bb},${aa * 0.8})`);
        gradient.addColorStop(1, `rgba(${rr},${gg},${bb},0)`);
        ctx.fillStyle = gradient;
        ctx.beginPath();
        ctx.arc(c, c, h, 0, Math.PI * 2);
        ctx.fill();
        circle(r, `rgba(${rr},${gg},${bb},${aa})`, null);
      } else {
        circle(r, Palette.tileFixed, null);
      }
      circle(r, null, color(live));
      const d = r * 0.62;
      ctx.strokeStyle = look.lamp === 'off' ? Palette.wireIdle : '#6b4300';
      ctx.lineWidth = thin;
      ctx.beginPath();
      ctx.moveTo(c - d, c - d);
      ctx.lineTo(c + d, c + d);
      ctx.moveTo(c + d, c - d);
      ctx.lineTo(c - d, c + d);
      ctx.stroke();
      break;
    }
    case Kind.BATTERY: {
      const gap = h * 0.14;
      line(nx, ny, c, c - gap, live);
      line(c, c + gap, sx, sy, live);
      plate(c - h * 0.5, c + h * 0.5, c - gap, thin * 1.2, Palette.wireLive);
      plate(c - h * 0.26, c + h * 0.26, c + gap, thin * 2.4, Palette.wireLive);
      sign('+', c + h * 0.42, c - h * 0.42, Palette.wireLive);
      sign('−', c + h * 0.42, c + h * 0.42, Palette.textMuted);
      break;
    }
    case Kind.METER: {
      const r = h * 0.46;
      line(nx, ny, c, c - r, live);
      line(c, c + r, sx, sy, live);
      circle(r, Palette.tileFixed, live ? Palette.charge : Palette.wireIdle);
      sign('V', c, c, live ? Palette.charge : Palette.textMuted, s * 0.22);
      sign('+', c + h * 0.55, c - h * 0.6, Palette.charge);
      break;
    }
    default:
      break;
  }
  ctx.restore();

  const label =
    tile.kind === Kind.RESISTOR || tile.kind === Kind.LAMP
      ? formatResistance(tile.value)
      : tile.kind === Kind.BATTERY
        ? `${tile.value.toFixed(0)}V`
        : null;
  if (label) {
    ctx.fillStyle = Palette.textMuted;
    ctx.font = `500 ${s * 0.13}px system-ui, sans-serif`;
    ctx.textAlign = 'right';
    ctx.textBaseline = 'bottom';
    ctx.fillText(label, s - inset * 2.5, s - inset * 2);
  }
  ctx.restore();
}
