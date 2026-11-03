import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';
import { Text } from 'react-native';
import { CameraScreen } from '../src/screens/CameraScreen';
import type {
  CameraDevice,
  CameraStatus,
  OverlayStatus,
  SystemSupport,
} from '../src/types/camera';
import { camera } from '../src/services/camera';

jest.mock('react-native-safe-area-context', () => ({
  SafeAreaView: require('react-native').View,
}));
jest.mock('../src/components/MediaPreview', () => ({
  MediaPreview: () => null,
}));
jest.mock('../src/hooks/useCamera', () => ({ useCamera: () => mockState }));
jest.mock('../src/services/camera', () => ({
  camera: {
    deactivate: jest.fn().mockResolvedValue(undefined),
    activate: jest.fn().mockResolvedValue(true),
    clear: jest.fn().mockResolvedValue(undefined),
    output: jest.fn().mockResolvedValue(undefined),
    replacementMode: jest.fn().mockResolvedValue(undefined),
    overlayVisible: jest.fn().mockResolvedValue({}),
    requestOverlayPermission: jest.fn().mockResolvedValue({}),
  },
}));
const mockState = {
  status: {} as CameraStatus,
  support: {} as SystemSupport,
  overlay: undefined as OverlayStatus | undefined,
  busy: false,
  error: '',
  run: jest.fn(async (action: () => Promise<unknown>) => action()),
  cameras: [] as CameraDevice[],
  activating: false,
  cancelling: false,
  cancelActivation: jest.fn(),
  updateTransform: jest.fn(),
};
beforeEach(() => {
  jest.clearAllMocks();
  mockState.status = {
    phase: 'integration_required',
    message: '',
    uri: 'file:///selected.jpg',
    type: 'image',
    name: 'selected.jpg',
    width: 1280,
    height: 720,
    loop: true,
    cameraId: '0',
    rotation: 0,
    scaleMode: 'fit',
  };
  mockState.support = {
    supported: false,
    message: 'System integration is missing',
  };
  mockState.cameras = [{ id: '0', facing: 'back' }];
  mockState.overlay = undefined;
  mockState.activating = false;
  mockState.cancelling = false;
  mockState.busy = false;
});
function labels(renderer: TestRenderer.ReactTestRenderer) {
  return renderer.root
    .findAllByType(Text)
    .map(node => node.props.children)
    .filter(value => typeof value === 'string');
}
async function render() {
  let view!: TestRenderer.ReactTestRenderer;
  await act(async () => {
    view = TestRenderer.create(<CameraScreen />);
  });
  return view;
}
async function press(view: TestRenderer.ReactTestRenderer, label: string) {
  await act(async () => {
    await view.root.findByProps({ label }).props.onPress();
  });
}
async function close(view: TestRenderer.ReactTestRenderer) {
  await act(async () => view.unmount());
}
test('selecting media on a stock device cannot claim Active or enable activation', async () => {
  const view = await render();
  expect(labels(view)).toContain('System Integration Required');
  expect(labels(view)).not.toContain('Virtual Camera Active');
  expect(
    view.root.findByProps({ testID: 'camera-activation' }).props
      .accessibilityState.disabled,
  ).toBe(true);
  await close(view);
});
test('a missing physical camera ID prevents activation even with system support', async () => {
  mockState.status.phase = 'ready';
  mockState.support.supported = true;
  mockState.cameras = [{ id: '7', facing: 'front' }];
  const view = await render();
  expect(labels(view)).toContain('Front Camera Only');
  expect(
    view.root.findByProps({ label: 'Front Camera Only' }).props.disabled,
  ).toBe(false);
  expect(
    view.root.findByProps({ testID: 'camera-activation' }).props
      .accessibilityState.disabled,
  ).toBe(true);
  await close(view);
});
test('activation shows progress and offers cancellation without claiming Active', async () => {
  mockState.activating = true;
  mockState.busy = true;
  const view = await render();
  expect(labels(view)).toContain('Activating…');
  expect(labels(view)).not.toContain('Virtual Camera Active');
  await press(view, 'Cancel activation');
  expect(mockState.cancelActivation).toHaveBeenCalledTimes(1);
  await close(view);
});
test('an active system status exposes deactivation', async () => {
  mockState.status.phase = 'active';
  mockState.support.supported = true;
  const view = await render();
  expect(labels(view)).toContain('Virtual Camera Active');
  expect(labels(view)).toContain('■  Deactivate Virtual Camera');
  expect(
    view.root.findByProps({ testID: 'camera-activation' }).props
      .accessibilityState.disabled,
  ).toBe(false);
  await close(view);
});
test('output sizes follow camera inventory and clear uses the native action', async () => {
  mockState.cameras = [
    { id: '0', facing: 'back', resolutions: [{ width: 960, height: 540 }] },
  ];
  const view = await render();
  expect(labels(view)).toContain('960 × 540');
  expect(labels(view)).not.toContain('720p');
  expect(labels(view)).not.toContain('1080p');
  await act(async () => {
    await view.root.findByProps({ testID: 'camera-clear' }).props.onPress();
  });
  expect(mockState.run).toHaveBeenCalledWith(camera.clear);
  await close(view);
});
test('front and back modes discover facing IDs and explicitly select a single route', async () => {
  mockState.cameras = [
    { id: 'rear-wide', facing: 'back' },
    { id: 'selfie-9', facing: 'front' },
  ];
  mockState.status.replacementMode = 'both';
  const view = await render();
  await press(view, 'Front Camera Only');
  expect(camera.output).toHaveBeenLastCalledWith(0, 'fit', 'selfie-9');
  expect(camera.replacementMode).toHaveBeenLastCalledWith('single');
  await press(view, 'Back Camera Only');
  expect(camera.output).toHaveBeenLastCalledWith(0, 'fit', 'rear-wide');
  expect(camera.replacementMode).toHaveBeenCalledTimes(2);
  await close(view);
});
test('both mode requires explicit bridge support and discovers common resolutions', async () => {
  mockState.cameras = [
    {
      id: 'rear-wide',
      facing: 'back',
      resolutions: [{ width: 960, height: 540 }],
    },
    {
      id: 'selfie-9',
      facing: 'front',
      resolutions: [{ width: 800, height: 600 }],
    },
  ];
  mockState.support = {
    supported: true,
    message: '',
    replacementModes: ['single', 'both'],
    bothCameraIds: ['rear-wide', 'selfie-9'],
    bothResolutions: [{ width: 640, height: 480 }],
  };
  mockState.status.replacementMode = 'both';
  mockState.status.cameraId = 'rear-wide';
  mockState.status.phase = 'active';
  const view = await render();
  expect(
    view.root.findByProps({ label: 'Replace Both Cameras' }).props.selected,
  ).toBe(true);
  expect(
    view.root.findByProps({ label: 'Replace Both Cameras' }).props.disabled,
  ).toBe(true);
  expect(labels(view)).toContain('640 × 480');
  expect(labels(view)).not.toContain('960 × 540');
  expect(labels(view)).not.toContain('800 × 600');
  expect(labels(view)).toContain(
    'Front and back cameras · Selected media is active',
  );
  expect(labels(view)).toContain(
    'Deactivate to change camera replacement mode.',
  );
  expect(
    view.root.findByProps({ label: 'Front Camera Only' }).props.disabled,
  ).toBe(true);
  expect(
    view.root.findByProps({ label: 'Back Camera Only' }).props.disabled,
  ).toBe(true);
  for (const label of [
    'Front Camera Only',
    'Back Camera Only',
    'Replace Both Cameras',
  ]) {
    const button = view.root
      .findByProps({ label })
      .findAllByProps({ accessibilityRole: 'button' })[0];
    expect(button.props.accessibilityState.disabled).toBe(true);
  }
  await press(view, '90°');
  expect(camera.output).toHaveBeenCalledWith(90, 'fit', 'rear-wide');
  expect(camera.replacementMode).not.toHaveBeenCalledWith('single');
  await close(view);
  mockState.status.phase = 'ready';
  const inactive = await render();
  expect(
    inactive.root.findByProps({ label: 'Replace Both Cameras' }).props.disabled,
  ).toBe(false);
  await press(inactive, 'Replace Both Cameras');
  expect(camera.replacementMode).toHaveBeenCalledWith('both');
  await close(inactive);
});
test('both mode remains disabled on a single-camera bridge or missing facing', async () => {
  mockState.cameras.push({ id: '9', facing: 'front' });
  let view = await render();
  expect(
    view.root.findByProps({ label: 'Replace Both Cameras' }).props.disabled,
  ).toBe(true);
  await close(view);
  mockState.support.replacementModes = ['single', 'both'];
  mockState.cameras = [{ id: '0', facing: 'back' }];
  view = await render();
  expect(
    view.root.findByProps({ label: 'Replace Both Cameras' }).props.disabled,
  ).toBe(true);
  await close(view);
});
test('additional camera choices retain their actual camera IDs', async () => {
  mockState.cameras.push({ id: 'usb-camera', facing: 'external' });
  mockState.status.replacementMode = 'both';
  const view = await render();
  await press(view, 'Camera usb-camera · external');
  expect(camera.output).toHaveBeenCalledWith(0, 'fit', 'usb-camera');
  expect(camera.replacementMode).toHaveBeenCalledWith('single');
  const modeCall = (camera.replacementMode as jest.Mock).mock
    .invocationCallOrder[0];
  const outputCall = (camera.output as jest.Mock).mock.invocationCallOrder[0];
  expect(modeCall).toBeLessThan(outputCall);
  await close(view);
});
test('floating controller requests permission only after its explicit button is pressed', async () => {
  mockState.overlay = {
    supported: true,
    permissionGranted: false,
    visible: false,
  };
  const view = await render();
  expect(camera.requestOverlayPermission).not.toHaveBeenCalled();
  expect(labels(view)).not.toContain('Show Floating Controller');
  await press(view, 'Allow Floating Controller');
  expect(camera.requestOverlayPermission).toHaveBeenCalledTimes(1);
  expect(camera.overlayVisible).not.toHaveBeenCalled();
  await close(view);
});
test('granted floating controller can be shown and hidden without requiring Active', async () => {
  mockState.overlay = {
    supported: true,
    permissionGranted: true,
    visible: false,
  };
  let view = await render();
  await press(view, 'Show Floating Controller');
  expect(camera.overlayVisible).toHaveBeenLastCalledWith(true);
  await close(view);
  mockState.overlay.visible = true;
  view = await render();
  await press(view, 'Hide Floating Controller');
  expect(camera.overlayVisible).toHaveBeenLastCalledWith(false);
  await close(view);
});
