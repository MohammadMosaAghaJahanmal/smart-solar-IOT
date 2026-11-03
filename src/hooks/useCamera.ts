import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { camera } from '../services/camera';
import type {
  CameraStatus,
  SystemSupport,
  CameraDevice,
  SourceTransform,
  OverlayStatus,
} from '../types/camera';
export function useCamera() {
  const [status, setStatus] = useState<CameraStatus>();
  const [support, setSupport] = useState<SystemSupport>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [cameras, setCameras] = useState<CameraDevice[]>([]);
  const [overlay, setOverlay] = useState<OverlayStatus>();
  const [activating, setActivating] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const refreshing = useRef(false);
  const mounted = useRef(true);
  const pendingTransform = useRef<SourceTransform | undefined>(undefined);
  const sendingTransform = useRef(false);
  const transformTimer = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );
  const optimisticTransform = useRef<SourceTransform | undefined>(undefined);
  const refresh = useCallback(async () => {
    if (refreshing.current) {
      return;
    }
    refreshing.current = true;
    try {
      const [next, available, devices, overlayState] = await Promise.all([
        camera.status(),
        camera.support(),
        camera.cameras(),
        camera.overlayStatus().catch(failure => ({
          supported: false,
          permissionGranted: false,
          visible: false,
          message: failure instanceof Error ? failure.message : String(failure),
        })),
      ]);
      if (mounted.current) {
        setStatus({ ...next, ...optimisticTransform.current });
        setSupport(available);
        setCameras(devices);
        setOverlay(overlayState);
      }
    } finally {
      refreshing.current = false;
    }
  }, []);
  useEffect(() => {
    mounted.current = true;
    const update = () => {
      refresh().catch(e => mounted.current && setError(String(e.message)));
    };
    update();
    const timer = setInterval(
      () => AppState.currentState === 'active' && update(),
      2000,
    );
    const sub = AppState.addEventListener(
      'change',
      value => value === 'active' && update(),
    );
    return () => {
      mounted.current = false;
      clearInterval(timer);
      clearTimeout(transformTimer.current);
      pendingTransform.current = undefined;
      sub.remove();
    };
  }, [refresh]);
  const sendTransform = useCallback(
    async function drainTransform() {
      transformTimer.current = undefined;
      if (sendingTransform.current || !mounted.current) {
        return;
      }
      const next = pendingTransform.current;
      if (!next) {
        return;
      }
      pendingTransform.current = undefined;
      sendingTransform.current = true;
      try {
        await camera.transform(next.zoom, next.panX, next.panY);
        if (!pendingTransform.current) {
          optimisticTransform.current = undefined;
        }
      } catch (failure) {
        pendingTransform.current = undefined;
        optimisticTransform.current = undefined;
        if (mounted.current) {
          setError(
            failure instanceof Error ? failure.message : String(failure),
          );
        }
      } finally {
        sendingTransform.current = false;
        if (mounted.current && pendingTransform.current) {
          transformTimer.current = setTimeout(drainTransform, 64);
        } else if (mounted.current) {
          refresh().catch(failure => setError(String(failure.message)));
        }
      }
    },
    [refresh],
  );
  const updateTransform = useCallback(
    (value: SourceTransform) => {
      optimisticTransform.current = value;
      pendingTransform.current = value;
      setStatus(current => current && { ...current, ...value });
      setError('');
      if (!sendingTransform.current && !transformTimer.current) {
        transformTimer.current = setTimeout(sendTransform, 64);
      }
    },
    [sendTransform],
  );
  const run = async (action: () => Promise<unknown>, isActivation = false) => {
    if (busy) {
      return;
    }
    setBusy(true);
    setActivating(isActivation);
    setError('');
    try {
      await action();
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (mounted.current) {
        setBusy(false);
        setActivating(false);
      }
    }
  };
  const cancelActivation = async () => {
    if (!activating || cancelling) {
      return;
    }
    setCancelling(true);
    try {
      // This uses the native Stop executor, independent of pending activation.
      await camera.deactivate();
      if (mounted.current) {
        setActivating(false);
        setStatus(
          current =>
            current && {
              ...current,
              phase: 'stopped',
              message: 'Virtual Camera Stopped',
              frames: 0,
              proof: undefined,
            },
        );
      }
    } catch (failure) {
      if (mounted.current) {
        setError(failure instanceof Error ? failure.message : String(failure));
      }
    } finally {
      if (mounted.current) {
        setCancelling(false);
      }
    }
  };
  return {
    status,
    support,
    busy,
    error,
    run,
    cameras,
    overlay,
    activating,
    cancelling,
    cancelActivation,
    updateTransform,
  };
}
