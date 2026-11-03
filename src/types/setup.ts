export type SetupState =
  | 'inspecting'
  | 'setup_required'
  | 'permission_required'
  | 'root_required'
  | 'repair_required'
  | 'ready'
  | 'unsupported'
  | 'error'
  | 'preparing'
  | 'verifying';

export interface SetupStatus {
  schemaVersion: number;
  state: SetupState;
  supported: boolean;
  ready: boolean;
  canPrepare: boolean;
  canVerify?: boolean;
  canOpenRootManager?: boolean;
  canRestore?: boolean;
  needsConsent: boolean;
  title: string;
  message: string;
  recovery?: string;
  profile?: { id: string; name: string; version: string | number };
  environment?: Record<string, unknown>;
  components?: Record<string, unknown> | Record<string, unknown>[];
  steps: {
    id: string;
    title: string;
    state:
      | 'complete'
      | 'pending'
      | 'blocked'
      | 'running'
      | 'error'
      | 'not_required';
    detail?: string;
  }[];
  preferences: { autoRecover: boolean; autoResume: boolean };
  lastVerifiedAt?: number | string;
  errorCode?: string;
}

export const isSetupWorking = (status?: SetupStatus) =>
  !!status && ['inspecting', 'preparing', 'verifying'].includes(status.state);
export const isSetupReady = (status?: SetupStatus) =>
  status?.state === 'ready' &&
  status.ready === true &&
  status.supported === true;
