import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';
import { AppState } from 'react-native';
import { useSetup } from '../src/hooks/useSetup';
import { setup } from '../src/services/setup';
import type { SetupStatus } from '../src/types/setup';

jest.mock('../src/services/setup', () => ({
  setup: {
    run: jest.fn(),
    status: jest.fn(),
    permissions: jest.fn(),
    preferences: jest.fn(),
  },
}));
const result: SetupStatus = {
  schemaVersion: 1,
  state: 'inspecting',
  supported: false,
  ready: false,
  canPrepare: false,
  needsConsent: false,
  title: 'Inspecting',
  message: '',
  steps: [],
  preferences: { autoRecover: false, autoResume: false },
};
let observed: ReturnType<typeof useSetup>;
function Harness() {
  observed = useSetup();
  return null;
}

beforeEach(() => jest.clearAllMocks());

test('inspection polls quickly while running, then checks terminal health without approving changes', async () => {
  jest.useFakeTimers();
  const previousAppState = AppState.currentState;
  AppState.currentState = 'active';
  (setup.run as jest.Mock).mockResolvedValue(result);
  (setup.status as jest.Mock).mockResolvedValue({
    ...result,
    state: 'ready',
    supported: true,
    ready: true,
  });
  let view: TestRenderer.ReactTestRenderer;
  await act(async () => {
    view = TestRenderer.create(<Harness />);
  });
  expect(setup.run).toHaveBeenCalledWith('inspect', false);
  await act(async () => {
    jest.advanceTimersByTime(1000);
  });
  expect(observed.status?.state).toBe('ready');
  expect(setup.status).toHaveBeenCalledTimes(1);
  await act(async () => {
    jest.advanceTimersByTime(4000);
  });
  expect(setup.status).toHaveBeenCalledTimes(1);
  (setup.status as jest.Mock).mockResolvedValue({
    ...result,
    state: 'permission_required',
    message: 'Camera permission was revoked',
  });
  await act(async () => {
    jest.advanceTimersByTime(1000);
  });
  expect(setup.status).toHaveBeenCalledTimes(2);
  expect(observed.status?.state).toBe('permission_required');
  expect(observed.busy).toBe(false);
  expect(setup.run).toHaveBeenCalledTimes(1);
  await act(async () => view!.unmount());
  AppState.currentState = previousAppState;
  jest.useRealTimers();
});

test('terminal health reads do not show a busy spinner or run while the app is in the background', async () => {
  jest.useFakeTimers();
  const previousAppState = AppState.currentState;
  AppState.currentState = 'active';
  const ready = { ...result, state: 'ready' as const, ready: true, supported: true };
  (setup.run as jest.Mock).mockResolvedValue(ready);
  let finishRead!: (status: SetupStatus) => void;
  (setup.status as jest.Mock).mockImplementation(
    () => new Promise<SetupStatus>(resolve => (finishRead = resolve)),
  );
  let view: TestRenderer.ReactTestRenderer;
  await act(async () => {
    view = TestRenderer.create(<Harness />);
  });
  await act(async () => {
    jest.advanceTimersByTime(5000);
  });
  expect(setup.status).toHaveBeenCalledTimes(1);
  expect(observed.busy).toBe(false);
  await act(async () => finishRead(ready));
  AppState.currentState = 'background';
  await act(async () => {
    jest.advanceTimersByTime(15000);
  });
  expect(setup.status).toHaveBeenCalledTimes(1);
  await act(async () => view!.unmount());
  AppState.currentState = previousAppState;
  jest.useRealTimers();
});

test('returning from a target app reads health without rerunning the visible setup flow', async () => {
  const ready = { ...result, state: 'ready' as const, ready: true, supported: true };
  (setup.run as jest.Mock).mockResolvedValue(ready);
  (setup.status as jest.Mock).mockResolvedValue(ready);
  let foreground!: () => void;
  const subscription = jest.spyOn(AppState, 'addEventListener').mockImplementation((_type, listener) => {
    foreground = () => listener('active');
    return { remove: jest.fn() };
  });
  let view: TestRenderer.ReactTestRenderer;
  await act(async () => {
    view = TestRenderer.create(<Harness />);
  });
  await act(async () => foreground());
  expect(setup.run).toHaveBeenCalledTimes(1);
  expect(setup.status).toHaveBeenCalledTimes(1);
  expect(observed.status?.ready).toBe(true);
  expect(observed.busy).toBe(false);
  await act(async () => view!.unmount());
  subscription.mockRestore();
});
