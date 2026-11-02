import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';
import { Text } from 'react-native';
import { CameraScreen } from '../src/screens/CameraScreen';

jest.mock('react-native-safe-area-context', () => ({
  SafeAreaView: require('react-native').View,
}));
jest.mock('../src/components/MediaPreview', () => ({
  MediaPreview: () => null,
}));
jest.mock('../src/hooks/useCamera', () => ({ useCamera: () => mockState }));
jest.mock('../src/services/camera', () => ({
  camera: { deactivate: jest.fn(), activate: jest.fn() },
}));
const mockState = {
  status: {
    phase: 'integration_required',
    uri: 'file:///selected.jpg',
    type: 'image',
    name: 'selected.jpg',
    width: 1280,
    loop: true,
    cameraId: '0',
  },
  support: { supported: false, message: 'System integration is missing' },
  busy: false,
  error: '',
  run: jest.fn(),
  cameras: [{ id: '0', facing: 'back' }],
  activating: false,
};
function labels(renderer: TestRenderer.ReactTestRenderer) {
  return renderer.root
    .findAllByType(Text)
    .map(node => node.props.children)
    .filter(value => typeof value === 'string');
}
test('selecting media on a stock device cannot claim Active or enable activation', async () => {
  let view: TestRenderer.ReactTestRenderer;
  await act(async () => {
    view = TestRenderer.create(<CameraScreen />);
  });
  expect(labels(view!)).toContain('System Integration Required');
  expect(labels(view!)).not.toContain('Virtual Camera Active');
  const buttons = view!.root.findAllByProps({ testID: 'camera-activation' });
  expect(buttons[0].props.accessibilityState.disabled).toBe(true);
  await act(async () => view!.unmount());
});

test('a missing physical camera ID prevents activation even with system support', async () => {
  mockState.status.phase = 'ready';
  mockState.support.supported = true;
  mockState.cameras = [{ id: '7', facing: 'front' }];
  let view: TestRenderer.ReactTestRenderer;
  await act(async () => {
    view = TestRenderer.create(<CameraScreen />);
  });
  expect(labels(view!)).toContain('Camera 7 · front');
  expect(
    view!.root.findAllByProps({ testID: 'camera-activation' })[0].props
      .accessibilityState.disabled,
  ).toBe(true);
  await act(async () => view!.unmount());
  mockState.cameras = [{ id: '0', facing: 'back' }];
  mockState.status.phase = 'integration_required';
  mockState.support.supported = false;
});

test('activation shows progress without claiming Active', async () => {
  mockState.activating = true;
  mockState.busy = true;
  let view: TestRenderer.ReactTestRenderer;
  await act(async () => {
    view = TestRenderer.create(<CameraScreen />);
  });
  expect(labels(view!)).toContain('Activating…');
  expect(labels(view!)).not.toContain('Virtual Camera Active');
  await act(async () => view!.unmount());
  mockState.activating = false;
  mockState.busy = false;
});
test('an active system status exposes deactivation', async () => {
  mockState.status.phase = 'active';
  mockState.support.supported = true;
  let view: TestRenderer.ReactTestRenderer;
  await act(async () => {
    view = TestRenderer.create(<CameraScreen />);
  });
  expect(labels(view!)).toContain('Virtual Camera Active');
  expect(labels(view!)).toContain('■  Deactivate Virtual Camera');
  const buttons = view!.root.findAllByProps({ testID: 'camera-activation' });
  expect(buttons[0].props.accessibilityState.disabled).toBe(false);
  await act(async () => view!.unmount());
  mockState.status.phase = 'integration_required';
  mockState.support.supported = false;
});
