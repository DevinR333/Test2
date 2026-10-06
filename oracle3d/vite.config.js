import fs from 'node:fs';
import path from 'node:path';
import { defineConfig } from 'vite';

// During development only, dev-data/ (git-ignored) can hold seasons.gbc and a prebuilt
// world/ from `npm run capture`; the dev server serves them so the game starts without
// picking a ROM. Builds never include them: the app builds the world from the player's
// ROM on first launch instead.
function devData() {
  const root = path.resolve('dev-data');
  return {
    name: 'dev-data',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const url = decodeURIComponent((req.url || '').split('?')[0]);
        if (url !== '/seasons.gbc' && !url.startsWith('/world/')) return next();
        const file = path.join(root, url);
        if (!file.startsWith(root) || !fs.existsSync(file)) { res.statusCode = 404; res.end(); return; }
        res.setHeader('Content-Type', url.endsWith('.json') ? 'application/json' : url.endsWith('.png') ? 'image/png' : 'application/octet-stream');
        fs.createReadStream(file).pipe(res);
      });
    },
  };
}

export default defineConfig({
  base: './',
  plugins: [devData()],
  server: { host: true },
  build: { chunkSizeWarningLimit: 2000 },
});
