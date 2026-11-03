import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';
import { Alert, Text } from 'react-native';
import { SetupScreen } from '../src/screens/SetupScreen';
import type { SetupController } from '../src/hooks/useSetup';
import type { SetupStatus } from '../src/types/setup';

jest.mock('react-native-safe-area-context', () => ({
  SafeAreaView: require('react-native').View,
}));

const run = jest.fn();
const permissions = jest.fn();
const base: SetupStatus = {
  schemaVersion: 1,
  state: 'setup_required',
  supported: true,
  ready: false,
  canPrepare: true,
  needsConsent: true,
  title: 'Guest preparation required',
  message: 'A verified camera bridge is missing.',
  preferences: { autoRecover: false, autoResume: false },
  steps: [
    { id: 'environment', title: 'Environment detected', state: 'complete' },
    { id: 'bridge', title: 'Camera bridge', state: 'pending' },
    { id: 'unneeded', title: 'Not needed repair', state: 'not_required' },
  ],
};
const controller = (status = base): SetupController => ({
  status,
  run,
  permissions,
  preferences: jest.fn(),
  busy: false,
  error: '',
});
const render = async (status = base) => {
  let view: TestRenderer.ReactTestRenderer;
  await act(async () => {
    view = TestRenderer.create(
      <SetupScreen
        controller={controller(status)}
        onDiagnostics={jest.fn()}
        onContinue={jest.fn()}
      />,
    );
  });
  return view!;
};
const labels = (view: TestRenderer.ReactTestRenderer) =>
  view.root.findAllByType(Text).map(node => node.props.children);

beforeEach(() => jest.clearAllMocks());

test('privileged preparation requires approval and cancellation does not start setup', async () => {
  const alert = jest.spyOn(Alert, 'alert');
  const view = await render();
  await act(async () =>
    view.root.findAllByProps({ testID: 'setup-prepare' })[0].props.onPress(),
  );
  expect(run).not.toHaveBeenCalled();
  const buttons = alert.mock.calls[0][2]!;
  expect(buttons[0].style).toBe('cancel');
  expect(buttons[0].onPress).toBeUndefined();
  await act(async () => buttons[1].onPress!());
  expect(run).toHaveBeenCalledWith('prepare', true);
  expect(labels(view)).not.toContain('Not needed repair');
  await act(async () => view.unmount());
  alert.mockRestore();
});

test('root and permission actions are shown only when actually required', async () => {
  const view = await render({
    ...base,
    state: 'root_required',
    canPrepare: false,
  });
  expect(view.root.findAllByProps({ testID: 'setup-prepare' })).toHaveLength(0);
  expect(
    view.root.findAllByProps({ testID: 'setup-permissions' }),
  ).toHaveLength(0);
  await act(async () =>
    view.root.findAllByProps({ testID: 'setup-root' })[0].props.onPress(),
  );
  expect(run).toHaveBeenCalledWith('check_root', true);
  await act(async () =>
    view.update(
      <SetupScreen
        controller={controller({
          ...base,
          state: 'permission_required',
          canPrepare: false,
        })}
        onDiagnostics={jest.fn()}
        onContinue={jest.fn()}
      />,
    ),
  );
  expect(view.root.findAllByProps({ testID: 'setup-root' })).toHaveLength(0);
  await act(async () =>
    view.root
      .findAllByProps({ testID: 'setup-permissions' })[0]
      .props.onPress(),
  );
  expect(permissions).toHaveBeenCalledTimes(1);
  await act(async () => view.unmount());
});

test('unsupported environments cannot prepare or claim ready', async () => {
  const view = await render({
    ...base,
    supported: false,
    ready: false,
    state: 'unsupported',
    canPrepare: false,
  });
  expect(view.root.findAllByProps({ testID: 'setup-prepare' })).toHaveLength(0);
  expect(view.root.findAllByProps({ testID: 'setup-continue' })).toHaveLength(
    0,
  );
  await act(async () => view.unmount());
});

test('an installed guest awaiting frame proof can verify without repeating preparation', async () => {
  const view = await render({ ...base, canPrepare: false, canVerify: true });
  expect(view.root.findAllByProps({ testID: 'setup-prepare' })).toHaveLength(0);
  await act(async () =>
    view.root.findAllByProps({ testID: 'setup-verify' })[0].props.onPress(),
  );
  expect(run).toHaveBeenCalledWith('verify', false);
  await act(async () => view.unmount());
});

test('guest root manager opens only when the native policy exposes that recovery', async () => {
  const view = await render({
    ...base,
    state: 'root_required',
    canPrepare: false,
    canOpenRootManager: true,
  });
  await act(async () =>
    view.root
      .findAllByProps({ testID: 'setup-root-manager' })[0]
      .props.onPress(),
  );
  expect(run).toHaveBeenCalledWith('open_root_manager', false);
  await act(async () =>
    view.update(
      <SetupScreen
        controller={controller({
          ...base,
          state: 'root_required',
          canPrepare: false,
          canOpenRootManager: false,
        })}
        onDiagnostics={jest.fn()}
        onContinue={jest.fn()}
      />,
    ),
  );
  expect(
    view.root.findAllByProps({ testID: 'setup-root-manager' }),
  ).toHaveLength(0);
  await act(async () => view.unmount());
});
