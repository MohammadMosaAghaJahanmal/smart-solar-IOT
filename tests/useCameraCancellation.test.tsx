import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';
import { useCamera } from '../src/hooks/useCamera';
import { camera } from '../src/services/camera';

jest.mock('../src/services/camera', () => ({
  camera: {
    status: jest.fn(),
    support: jest.fn(),
    cameras: jest.fn(),
    deactivate: jest.fn(),
    activate: jest.fn(),
    transform: jest.fn(),
    overlayStatus: jest.fn(),
  },
}));

test('Cancel reaches native Stop before a pending activation promise settles', async () => {
  jest.useFakeTimers();
  const native = camera as jest.Mocked<typeof camera>;
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
    cameraId: '0',
  });
  native.support.mockResolvedValue({ supported: true, message: 'Ready' });
  native.cameras.mockResolvedValue([{ id: '0', facing: 'back' }]);
  native.overlayStatus.mockResolvedValue({
    supported: true,
    permissionGranted: false,
    visible: false,
  });
  native.deactivate.mockResolvedValue(undefined);
  let finishActivation!: (active: boolean) => void;
  native.activate.mockReturnValue(
    new Promise(resolve => {
      finishActivation = resolve;
    }),
  );
  let current!: ReturnType<typeof useCamera>;
  function Harness() {
    current = useCamera();
    return null;
  }
  let view!: TestRenderer.ReactTestRenderer;
  try {
    await act(async () => {
      view = TestRenderer.create(<Harness />);
    });
    let activation!: Promise<void>;
    let activationSettled = false;
    await act(async () => {
      activation = current.run(camera.activate, true).then(() => {
        activationSettled = true;
      });
    });
    expect(current.activating).toBe(true);
    expect(current.busy).toBe(true);
    await act(async () => {
      await current.cancelActivation();
    });
    expect(native.deactivate).toHaveBeenCalledTimes(1);
    expect(activationSettled).toBe(false);
    expect(current.status?.phase).toBe('stopped');
    expect(current.status?.proof).toBeUndefined();
    native.status.mockResolvedValue({ ...current.status!, phase: 'stopped' });
    await act(async () => {
      finishActivation(false);
      await activation;
    });
    expect(current.busy).toBe(false);
    expect(current.status?.phase).toBe('stopped');
  } finally {
    await act(async () => {
      view?.unmount();
    });
    jest.useRealTimers();
  }
});
