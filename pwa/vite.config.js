import { defineConfig } from 'vite';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url)));

const walk = (dir) => readdirSync(dir).flatMap((f) => {
  const p = join(dir, f);
  return statSync(p).isDirectory() ? walk(p) : [p];
});

/** After the build: version.json (what the page polls) and sw.js (version + file list inlined). */
function releasePlugin() {
  let outDir = 'dist';
  return {
    name: 'libretag-release',
    apply: 'build',
    configResolved(c) { outDir = c.build.outDir; },
    closeBundle() {
      const files = walk(outDir).map((p) => relative(outDir, p)).filter((f) => f !== 'sw.js' && f !== 'version.json');
      const hash = createHash('sha256');
      for (const f of files.sort()) hash.update(f).update(readFileSync(join(outDir, f)));
      const build = hash.digest('hex').slice(0, 10);
      const version = `${pkg.version}+${build}`;
      writeFileSync(join(outDir, 'version.json'), JSON.stringify({ version: pkg.version, build }) + '\n');
      const sw = readFileSync(new URL('./src/sw.template.js', import.meta.url), 'utf8')
        .replace('__VERSION__', version)
        .replace('__FILES__', JSON.stringify(['./', ...files]));
      writeFileSync(join(outDir, 'sw.js'), sw);
    },
  };
}

export default defineConfig({
  base: './',
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  build: { target: 'es2022', outDir: 'dist', sourcemap: false, chunkSizeWarningLimit: 900 },
  plugins: [releasePlugin()],
  server: { host: '127.0.0.1' },
});
