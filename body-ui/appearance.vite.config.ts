import path from 'path'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  base: './',
  plugins: [react()],
  build: {
    outDir: path.resolve(__dirname, '../client-mod-gradle/src/client/resources/assets/eclipseclient/web/appearance'),
    emptyOutDir: true,
    rollupOptions: {
      input: path.resolve(__dirname, 'appearance.html'),
      output: {
        entryFileNames: 'appearance.js',
        chunkFileNames: 'appearance-[name].js',
        assetFileNames: asset => asset.name?.endsWith('.css') ? 'appearance.css' : 'assets/[name][extname]',
      },
    },
  },
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
})
