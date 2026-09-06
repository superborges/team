import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  base: '/h5/',
  server: { port: 5174, strictPort: true, proxy: { '/api': 'http://127.0.0.1:8080' } },
});
