import React, { useState } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { CameraScreen } from './src/screens/CameraScreen';
import { SetupScreen } from './src/screens/SetupScreen';
import { DiagnosticsScreen } from './src/screens/DiagnosticsScreen';
import { useSetup } from './src/hooks/useSetup';
import { isSetupReady } from './src/types/setup';
export default function App() {
  const controller = useSetup();
  const [screen, setScreen] = useState<'automatic' | 'setup' | 'diagnostics'>(
    'automatic',
  );
  return (
    <SafeAreaProvider>
      {screen === 'diagnostics' ? (
        <DiagnosticsScreen
          controller={controller}
          onBack={() => setScreen('automatic')}
        />
      ) : screen === 'setup' || !isSetupReady(controller.status) ? (
        <SetupScreen
          controller={controller}
          onDiagnostics={() => setScreen('diagnostics')}
          onContinue={() => setScreen('automatic')}
        />
      ) : (
        <CameraScreen
          setupStatus={controller.status}
          onDiagnostics={() => setScreen('diagnostics')}
          onSetup={() => setScreen('setup')}
        />
      )}
    </SafeAreaProvider>
  );
}
