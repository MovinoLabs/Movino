import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

// Plugin to strip crossorigin attributes from built HTML
// Capacitor's https://localhost WebView can silently fail with crossorigin
const stripCrossOrigin = () => ({
  name: "strip-crossorigin",
  transformIndexHtml(html: string) {
    let result = html.replace(/ crossorigin/g, "");
    // Add onerror + onload diagnostics to the main module script
    // Vite strips the original onerror when it replaces the dev script tag
    result = result.replace(
      /<script type="module" src="([^"]+)">/g,
      '<script type="module" src="$1" onerror="window.__setBootError && window.__setBootError(\'Module script failed to load: $1\')">',
    );
    return result;
  },
});

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => ({
  server: {
    host: "::",
    port: 8080,
    hmr: {
      overlay: false,
    },
  },
  plugins: [react(), stripCrossOrigin()].filter(Boolean),
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  build: {
    // Target modern WebViews (Chromium 75+ per Capacitor config)
    target: "es2020",
    // Reduce source map size for release
    sourcemap: mode === "development",
    // Disable module preload (can cause issues in Capacitor WebView)
    modulePreload: false,
  },
}));
