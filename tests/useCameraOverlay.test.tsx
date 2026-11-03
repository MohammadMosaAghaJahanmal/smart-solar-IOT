import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';
import { AppState, type AppStateStatus } from 'react-native';
import { useCamera } from '../src/hooks/useCamera';
import { camera } from '../src/services/camera';

jest.mock('../src/services/camera', () => ({
  camera: {
    status: jest.fn(),
    support: jest.fn(),
    cameras: jest.fn(),
    overlayStatus: jest.fn(),
    requestOverlayPermission: jest.fn(),
    overlayVisible: jest.fn(),
  },
}));
const native = camera as jest.Mocked<typeof camera>;
let current!: ReturnType<typeof useCamera>;
function Harness() {
  current = useCamera();
  return null;
}
beforeEach(() => {
  jest.useFakeTimers();
  jest.clearAllMocks();
  Object.defineProperty(AppState, 'currentState', {
    configurable: true,
    get: () => 'active',
  });
  native.status.mockResolvedValue({
    phase: 'ready',
    message: 'Ready',
    uri: 'file:///selected.png',
    type: 'image',
    name: 'selected.png',
    loop: true,
    width: 640,
    height: 480,
    rotation: 0,
    scaleMode: 'fit',
    cameraId: 'actual-camera',
  });
  native.support.mockResolvedValue({ supported: true, message: 'Ready' });
  native.cameras.mockResolvedValue([{ id: 'actual-camera', facing: 'front' }]);
  native.overlayStatus.mockResolvedValue({
    supported: true,
    permissionGranted: false,
    visible: false,
  });
});
afterEach(() => {
  jest.restoreAllMocks();
  jest.useRealTimers();
});
test('returning from overlay settings refreshes permission without showing it automatically', async () => {
  let foreground!: (state: AppStateStatus) => void;
  const remove = jest.fn();
  jest
    .spyOn(AppState, 'addEventListener')
    .mockImplementation((_event, listener) => {
      foreground = listener;
      return { remove };
    });
  let view!: TestRenderer.ReactTestRenderer;
  await act(async () => {
    view = TestRenderer.create(<Harness />);
  });
  expect(current.overlay?.permissionGranted).toBe(false);
  native.overlayStatus.mockResolvedValue({
    supported: true,
    permissionGranted: true,
    visible: false,
  });
  await act(async () => {
    foreground('active');
  });
  expect(current.overlay?.permissionGranted).toBe(true);
  expect(native.overlayVisible).not.toHaveBeenCalled();
  expect(native.requestOverlayPermission).not.toHaveBeenCalled();
  await act(async () => {
    view.unmount();
  });
  expect(remove).toHaveBeenCalledTimes(1);
});
test('an optional overlay status failure leaves camera inventory and readiness available', async () => {
  native.overlayStatus.mockRejectedValue(new Error('Overlay unavailable'));
  let view!: TestRenderer.ReactTestRenderer;
  await act(async () => {
    view = TestRenderer.create(<Harness />);
  });
  expect(current.status?.phase).toBe('ready');
  expect(current.cameras[0].id).toBe('actual-camera');
  expect(current.support?.supported).toBe(true);
  expect(current.overlay).toEqual({
    supported: false,
    permissionGranted: false,
    visible: false,
    message: 'Overlay unavailable',
  });
  expect(current.error).toBe('');
  await act(async () => {
    view.unmount();
  });
});
test('foreground polling observes asynchronously shown overlay after its show request returns', async () => {
  native.overlayStatus.mockResolvedValue({
    supported: true,
    permissionGranted: true,
    visible: false,
  });
  native.overlayVisible.mockResolvedValue({
    supported: true,
    permissionGranted: true,
    visible: false,
  });
  let view!: TestRenderer.ReactTestRenderer;
  await act(async () => {
    view = TestRenderer.create(<Harness />);
  });
  await act(async () => {
    await current.run(() => camera.overlayVisible(true));
  });
  expect(current.overlay?.visible).toBe(false);
  native.overlayStatus.mockResolvedValue({
    supported: true,
    permissionGranted: true,
    visible: true,
  });
  await act(async () => {
    jest.advanceTimersByTime(2000);
  });
  expect(current.overlay?.visible).toBe(true);
  expect(native.overlayVisible).toHaveBeenCalledTimes(1);
  expect(native.overlayVisible).toHaveBeenCalledWith(true);
  await act(async () => {
    view.unmount();
  });
});
