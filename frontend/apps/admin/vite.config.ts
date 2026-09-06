import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  base: '/admin/',
  server: { port: 5175, strictPort: true, proxy: { '/api': 'http://127.0.0.1:8080' } },
});
