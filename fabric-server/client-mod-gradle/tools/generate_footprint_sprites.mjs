import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";

const root = path.resolve(import.meta.dirname, "..");
const textureDir = path.join(root, "src/main/resources/assets/eclipseclient/textures/particle/blood");
const atlasPath = path.join(root, "src/main/resources/assets/eclipseclient/particles/blood_decal.json");
const stages = ["fresh", "settled", "drying", "dry"];

function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const typeBytes = Buffer.from(type);
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBytes, data])));
  return Buffer.concat([length, typeBytes, data, crc]);
}

function writePng(file, pixels) {
  const width = 32;
  const height = 32;
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    const row = y * (width * 4 + 1);
    raw[row] = 0;
    pixels.copy(raw, row + 1, y * width * 4, (y + 1) * width * 4);
  }
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  fs.writeFileSync(file, Buffer.concat([
    signature,
    chunk("IHDR", ihdr),
    chunk("IDAT", zlib.deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]));
}

function hash(x, y, variant, stage, foot) {
  let value = Math.imul(x + 31, 0x45d9f3b) ^ Math.imul(y + 17, 0x119de1f3)
    ^ Math.imul(variant + 7, 0x27d4eb2d) ^ Math.imul(stage + 3, 0x165667b1)
    ^ Math.imul(foot + 11, 0x9e3779b1);
  value ^= value >>> 16;
  value = Math.imul(value, 0x7feb352d);
  value ^= value >>> 15;
  return (value >>> 0) / 0xffffffff;
}

function insideEllipse(x, y, cx, cy, rx, ry) {
  const dx = (x - cx) / rx;
  const dy = (y - cy) / ry;
  return dx * dx + dy * dy <= 1;
}

function createSprite(foot, variant, stage) {
  const pixels = Buffer.alloc(32 * 32 * 4);
  const mirror = foot === 0 ? 1 : -1;
  const stageKeep = [1, 0.88, 0.67, 0.43][stage];
  const palette = [
    [[139, 13, 12], [104, 7, 9], [174, 22, 16]],
    [[112, 10, 10], [82, 8, 9], [139, 17, 14]],
    [[79, 12, 11], [57, 9, 9], [101, 19, 15]],
    [[52, 15, 13], [39, 12, 11], [70, 22, 17]],
  ][stage];

  for (let y = 2; y < 30; y++) {
    for (let x = 3; x < 29; x++) {
      const localX = 16 + (x - 16) * mirror;
      const runStretch = variant === 2 || variant === 3 ? 2 : 0;
      let shape = insideEllipse(localX, y, 15.5, 8 - runStretch, 5.8, 5.7 + runStretch)
        || insideEllipse(localX, y, 15.0, 24, 4.8, 5.0)
        || insideEllipse(localX, y, 14.3, 16.3, 3.5, 6.5);

      // The inner arch is the readable left/right asymmetry.
      if (insideEllipse(localX, y, 18.6, 17.0, 3.2, 5.4)) shape = false;
      if (variant === 1 && y > 11 && y < 21 && hash(x, y, variant, 1, foot) < 0.35) shape = false;
      if (variant === 3 && y > 18) shape = hash(x, y, variant, 0, foot) > 0.48;
      if (variant === 4) {
        shape ||= insideEllipse(localX, y, 15.4, 20.5, 5.6, 4.2);
      }
      if (variant === 5) {
        shape ||= insideEllipse(localX, y, 15.4, 15.5, 5.0, 10.5);
      }
      if (!shape) continue;

      const edgeNoise = hash(x, y, variant, stage, foot);
      if (edgeNoise > stageKeep && !insideEllipse(localX, y, 15.3, y < 15 ? 8 : 23, 2.8, 3.2)) continue;
      const color = palette[edgeNoise < 0.19 ? 2 : edgeNoise > 0.72 ? 1 : 0];
      const index = (y * 32 + x) * 4;
      pixels[index] = color[0];
      pixels[index + 1] = color[1];
      pixels[index + 2] = color[2];
      pixels[index + 3] = 255;
    }
  }

  // Sparse directional pixels make running/landing silhouettes feel authored.
  const extras = variant === 2 ? [[13, 28], [18, 29], [11, 26]]
    : variant === 5 ? [[9, 17], [22, 14], [8, 21], [23, 20]] : [];
  for (const [sourceX, y] of extras) {
    if (stage === 3 && hash(sourceX, y, variant, stage, foot) > 0.5) continue;
    const x = foot === 0 ? sourceX : 31 - sourceX;
    const index = (y * 32 + x) * 4;
    pixels[index] = palette[1][0];
    pixels[index + 1] = palette[1][1];
    pixels[index + 2] = palette[1][2];
    pixels[index + 3] = 255;
  }
  return pixels;
}

fs.mkdirSync(textureDir, { recursive: true });
const footprintIds = [];
for (let foot = 0; foot < 2; foot++) {
  const side = foot === 0 ? "l" : "r";
  for (let variant = 0; variant < 6; variant++) {
    for (let stage = 0; stage < stages.length; stage++) {
      const stem = `footprint_${side}${String(variant).padStart(2, "0")}_${stages[stage]}`;
      writePng(path.join(textureDir, `${stem}.png`), createSprite(foot, variant, stage));
      footprintIds.push(`eclipseclient:blood/${stem}`);
    }
  }
}

const atlas = JSON.parse(fs.readFileSync(atlasPath, "utf8"));
atlas.textures = atlas.textures.filter(id => !id.includes("/footprint_"));
atlas.textures.push(...footprintIds);
fs.writeFileSync(atlasPath, `${JSON.stringify(atlas, null, 2)}\n`, "utf8");
console.log(`Generated ${footprintIds.length} pixel-art footprint sprites.`);
