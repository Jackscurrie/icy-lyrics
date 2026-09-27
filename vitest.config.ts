import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    exclude: [
      "**/node_modules/**",
      "**/dist/**",
      "**/iOS/build/**",
      "**/android-v2/**/build/**",
    ],
  },
});
