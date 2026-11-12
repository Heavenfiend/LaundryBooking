import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    // для локальной разработки без докера: npm run dev + бэкенд на :8000
    proxy: { "/api": "http://localhost:8000" },
  },
});
