import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes("node_modules")) {
            if (id.includes("react-router")) return "vendor-router";
            if (id.includes("framer-motion")) return "vendor-motion";
            if (id.includes("sockjs-client") || id.includes("stompjs")) return "vendor-realtime";
            if (id.includes("react") || id.includes("scheduler")) return "vendor-react";
            return "vendor";
          }
          if (id.includes("/src/pages/chat/")) return "feature-chat";
          if (id.includes("/src/pages/teacher/")) return "feature-teacher";
          if (id.includes("/src/pages/student/")) return "feature-student";
          if (id.includes("/src/pages/admin/")) return "feature-admin";
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/ws": {
        target: "http://localhost:8080",
        changeOrigin: true,
        ws: true,
      },
    },
  },
});
