import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'app.movino.tv',
  appName: 'Movino',
  webDir: 'dist',
  android: {
    allowMixedContent: true,
    backgroundColor: '#0a0e1a',
    minWebViewVersion: 75,
    webContentsDebuggingEnabled: process.env.NODE_ENV !== 'production',
    adjustModeOnResize: 'none',
  },
  server: {
    androidScheme: 'https',
    cleartext: true,
  },
};

export default config;
