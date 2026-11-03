import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { setup } from '../services/setup';
import { isSetupWorking } from '../types/setup';
import type { SetupStatus } from '../types/setup';

export function useSetup() {
  const [status, setStatus] = useState<SetupStatus>();
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const alive = useRef(true);
  const running = useRef(false);
  const current = useRef<SetupStatus | undefined>(undefined);
  const accept = useCallback((next: SetupStatus) => {
    current.current = next;
    if (alive.current) {
      setStatus(next);
    }
  }, []);
  const execute = useCallback(
    async (task: () => Promise<SetupStatus>, showBusy = true) => {
      if (running.current) {
        return;
      }
      running.current = true;
      if (showBusy) {
        setBusy(true);
        setError('');
      }
      try {
        accept(await task());
        if (alive.current) {
          setError('');
        }
      } catch (failure) {
        if (alive.current) {
          setError(
            failure instanceof Error ? failure.message : String(failure),
          );
        }
      } finally {
        running.current = false;
        if (alive.current && showBusy) {
          setBusy(false);
        }
      }
    },
    [accept],
  );
  const run = useCallback(
    (action: string, approved = false) =>
      execute(() => setup.run(action, approved)),
    [execute],
  );
  const permissions = useCallback(() => execute(setup.permissions), [execute]);
  const preferences = useCallback(
    (autoRecover: boolean, autoResume: boolean) =>
      execute(async () => {
        await setup.preferences(autoRecover, autoResume);
        return setup.status();
      }),
    [execute],
  );

  useEffect(() => {
    alive.current = true;
    run('inspect');
    let idleTicks = 0;
    const timer = setInterval(() => {
      if (AppState.currentState !== 'active') {
        return;
      }
      if (isSetupWorking(current.current)) {
        idleTicks = 0;
        execute(setup.status, false);
      } else if (++idleTicks >= 5) {
        // Terminal states can change after permission revocation or broker recovery.
        // The native service limits its own full health checks to every 30 seconds.
        idleTicks = 0;
        execute(setup.status, false);
      }
    }, 1000);
    const subscription = AppState.addEventListener('change', value => {
      if (value === 'active') {
        idleTicks = 0;
        // Returning from a target camera app must preserve the selected-media
        // screen. Status checks current permissions and schedules native health
        // inspection when due, without starting a new visible setup operation.
        execute(setup.status, false);
      }
    });
    return () => {
      alive.current = false;
      clearInterval(timer);
      subscription.remove();
    };
  }, [execute, run]);
  return {
    status,
    error,
    busy: busy || isSetupWorking(status),
    run,
    permissions,
    preferences,
  };
}

export type SetupController = ReturnType<typeof useSetup>;
