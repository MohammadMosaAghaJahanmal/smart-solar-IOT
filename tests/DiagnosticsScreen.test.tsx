import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';
import { Alert, Share, Text } from 'react-native';
import { DiagnosticsScreen } from '../src/screens/DiagnosticsScreen';
import type { SetupController } from '../src/hooks/useSetup';
import { setup } from '../src/services/setup';

jest.mock('react-native-safe-area-context', () => ({
  SafeAreaView: require('react-native').View,
}));
jest.mock('../src/services/camera', () => ({
  camera: {
    status: jest.fn().mockResolvedValue({ phase: 'active' }),
    cameras: jest.fn().mockResolvedValue([]),
    support: jest.fn().mockResolvedValue({ supported: true }),
  },
}));
jest.mock('../src/services/setup', () => ({
  setup: { export: jest.fn().mockResolvedValue('{"sanitized":true}') },
}));
const run = jest.fn();
const controller: SetupController = {
  status: {
    schemaVersion: 1,
    state: 'ready',
    supported: true,
    ready: true,
    canPrepare: false,
    canVerify: true,
    needsConsent: false,
    title: 'Ready',
    message: 'Checks passed',
    steps: [],
    preferences: { autoRecover: false, autoResume: false },
    lastVerifiedAt: 1793617200000,
  },
  run,
  permissions: jest.fn(),
  preferences: jest.fn(),
  busy: false,
  error: '',
};
async function render(selectedController = controller) {
  let view: TestRenderer.ReactTestRenderer;
  await act(async () => {
    view = TestRenderer.create(
      <DiagnosticsScreen controller={selectedController} onBack={jest.fn()} />,
    );
  });
  return view!;
}

afterEach(() => {
  jest.restoreAllMocks();
  jest.clearAllMocks();
});

test('verification warns before interrupting an active feed and date is readable', async () => {
  const alert = jest.spyOn(Alert, 'alert');
  const view = await render();
  const labels = view.root
    .findAllByType(Text)
    .map(node => node.props.children)
    .flat()
    .join(' ');
  expect(labels).toContain(new Date(1793617200000).toLocaleString());
  expect(labels).not.toContain('1793617200000');
  await act(async () =>
    view.root
      .findAllByProps({ testID: 'diagnostics-verify' })[0]
      .props.onPress(),
  );
  expect(run).not.toHaveBeenCalled();
  expect(alert.mock.calls[0][1]).toContain('stops any active virtual camera');
  await act(async () => alert.mock.calls[0][2]![1].onPress!());
  expect(run).toHaveBeenCalledWith('verify', false);
  await act(async () => view.unmount());
});

test('sanitized export opens the share sheet only after the user presses export', async () => {
  const share = jest
    .spyOn(Share, 'share')
    .mockResolvedValue({ action: Share.sharedAction });
  const view = await render();
  expect(setup.export).not.toHaveBeenCalled();
  expect(share).not.toHaveBeenCalled();
  await act(async () =>
    view.root
      .findAllByProps({ testID: 'diagnostics-export' })[0]
      .props.onPress(),
  );
  expect(share).toHaveBeenCalledWith({
    title: 'Virtual Camera Pro diagnostics',
    message: '{"sanitized":true}',
  });
  await act(async () => view.unmount());
});

test('large reports stay closed until requested while recovery actions remain available', async () => {
  const view = await render({
    ...controller,
    status: {
      ...controller.status!,
      canRestore: true,
      environment: {
        cameraCapabilities: Array.from({ length: 1000 }, (_, index) => ({
          id: index,
          detail: 'GUEST_CAPABILITY_DETAIL',
        })),
      },
    },
  });
  const renderedText = () =>
    view.root
      .findAllByType(Text)
      .map(node => node.props.children)
      .flat()
      .join(' ');
  expect(renderedText()).not.toContain('GUEST_CAPABILITY_DETAIL');
  expect(
    view.root.findAllByProps({ testID: 'setup-restore' }).length,
  ).toBeGreaterThan(0);
  expect(
    view.root.findAllByProps({ testID: 'diagnostics-export' }).length,
  ).toBeGreaterThan(0);
  await act(async () =>
    view.root
      .findAllByProps({
        accessibilityLabel: 'Guest and environment: Show details',
      })[0]
      .props.onPress(),
  );
  expect(renderedText()).toContain('GUEST_CAPABILITY_DETAIL');
  await act(async () =>
    view.root
      .findAllByProps({
        accessibilityLabel: 'Guest and environment: Hide details',
      })[0]
      .props.onPress(),
  );
  expect(renderedText()).not.toContain('GUEST_CAPABILITY_DETAIL');
  await act(async () => view.unmount());
});
