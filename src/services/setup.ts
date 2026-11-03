import NativeVirtualCamera from '../../specs/NativeVirtualCamera';
import type { SetupStatus } from '../types/setup';

function decode(value: string): SetupStatus {
  const status = JSON.parse(value) as SetupStatus;
  if (
    !status ||
    typeof status.state !== 'string' ||
    !Array.isArray(status.steps)
  ) {
    throw new Error(
      'The setup service returned an invalid status. Open diagnostics and retry.',
    );
  }
  return status;
}

export const setup = {
  status: async () => decode(await NativeVirtualCamera.getSetupStatus()),
  run: async (action: string, approved = false) =>
    decode(await NativeVirtualCamera.runSetup(action, approved)),
  permissions: async () =>
    decode(await NativeVirtualCamera.requestSetupPermissions()),
  preferences: (autoRecover: boolean, autoResume: boolean) =>
    NativeVirtualCamera.setRecoveryPreferences(autoRecover, autoResume),
  export: () => NativeVirtualCamera.exportDiagnostics(),
};
