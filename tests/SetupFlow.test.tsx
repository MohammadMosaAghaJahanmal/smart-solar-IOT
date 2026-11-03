import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';
import { Text } from 'react-native';
import App from '../App';
import type { SetupStatus } from '../src/types/setup';

jest.mock('react-native-safe-area-context', () => ({
  SafeAreaProvider: require('react-native').View,
  SafeAreaView: require('react-native').View,
}));
jest.mock('../src/hooks/useSetup', () => ({ useSetup: () => mockController }));
jest.mock('../src/screens/CameraScreen', () => ({
  CameraScreen: () =>
    require('react').createElement(
      require('react-native').Text,
      null,
      'Real camera controls',
    ),
}));
jest.mock('../src/screens/DiagnosticsScreen', () => ({
  DiagnosticsScreen: () =>
    require('react').createElement(
      require('react-native').Text,
      null,
      'Diagnostics screen',
    ),
}));

const mockController = {
  status: undefined as SetupStatus | undefined,
  busy: false,
  error: '',
  run: jest.fn(),
  permissions: jest.fn(),
  preferences: jest.fn(),
};
const ready: SetupStatus = {
  schemaVersion: 1,
  state: 'ready',
  supported: true,
  ready: true,
  canPrepare: false,
  needsConsent: false,
  title: 'Ready',
  message: 'Actual guest checks passed',
  steps: [],
  preferences: { autoRecover: false, autoResume: false },
};

test('healthy launch skips setup; a real missing requirement returns to its conditional wizard', async () => {
  mockController.status = ready;
  let view: TestRenderer.ReactTestRenderer;
  await act(async () => {
    view = TestRenderer.create(<App />);
  });
  expect(
    view!.root.findAllByType(Text).map(node => node.props.children),
  ).toContain('Real camera controls');
  expect(view!.root.findAllByProps({ testID: 'setup-prepare' })).toHaveLength(
    0,
  );
  mockController.status = {
    ...ready,
    ready: false,
    state: 'root_required',
    title: 'Guest root required',
  };
  await act(async () => view!.update(<App />));
  expect(
    view!.root.findAllByProps({ testID: 'setup-root' }).length,
  ).toBeGreaterThan(0);
  expect(
    view!.root.findAllByType(Text).map(node => node.props.children),
  ).not.toContain('Real camera controls');
  await act(async () => view!.unmount());
});

test('a ready flag without successful compatible status does not bypass setup', async () => {
  mockController.status = { ...ready, state: 'verifying' };
  let view: TestRenderer.ReactTestRenderer;
  await act(async () => {
    view = TestRenderer.create(<App />);
  });
  expect(
    view!.root.findAllByType(Text).map(node => node.props.children),
  ).not.toContain('Real camera controls');
  await act(async () => view!.unmount());
});
