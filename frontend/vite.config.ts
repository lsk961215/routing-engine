import { defineConfig } from "vite";

export default defineConfig(({ mode }) => ({
  root: mode === "demo" ? "demo" : ".",
  base: mode === "demo" ? "/routing-engine/" : "/",
  publicDir: "public",
  build: { outDir: mode === "demo" ? "../dist-demo" : "dist", emptyOutDir: true },
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
    proxy: { "/api": "http://127.0.0.1:8080" },
  },
  optimizeDeps: {
    exclude: ["maplibre-gl"],
  },
}));
