import React from 'react';
import { Alert, Text, View } from 'react-native';
import { Action, Page, Progress, ui } from '../components/SetupUI';
import type { SetupController } from '../hooks/useSetup';
import { isSetupReady } from '../types/setup';

const markers = {
  complete: '✓',
  pending: '○',
  blocked: '!',
  running: '◌',
  error: '!',
  not_required: '–',
};

export function SetupScreen({
  controller,
  onDiagnostics,
  onContinue,
}: {
  controller: SetupController;
  onDiagnostics: () => void;
  onContinue: () => void;
}) {
  const { status, busy, error, run, permissions } = controller;
  const prepare = () =>
    Alert.alert(
      'Prepare the VMOS guest camera?',
      'Virtual Camera Pro will use guest root to install or repair only the camera components verified for this guest profile: the camera bridge, JPEG/color repairs and zoom/crop adapter as needed. It backs up original files, checks hashes and preserves file permissions. The guest camera provider may restart. Restore is available from Diagnostics. The physical phone system is not modified.',
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Prepare guest camera', onPress: () => run('prepare', true) },
      ],
    );
  return (
    <Page
      title={status?.title || 'Preparing your workspace'}
      subtitle={
        status?.message ||
        'Inspecting the guest environment and existing camera setup.'
      }
    >
      {busy && <Progress />}
      {status?.profile && (
        <View style={ui.card}>
          <Text style={ui.label}>{status.profile.name}</Text>
          <Text style={ui.small}>
            Adapter profile {status.profile.version}. Compatibility follows this
            guest's actual components; other host Android versions remain
            unverified.
          </Text>
        </View>
      )}
      {!!status?.steps.length && (
        <View style={ui.card}>
          <Text style={ui.heading}>Setup checks</Text>
          {status.steps
            .filter(step => step.state !== 'not_required')
            .map(step => (
              <View key={step.id} style={ui.row}>
                <Text
                  accessibilityLabel={step.state}
                  style={[ui.label, step.state === 'complete' && ui.complete]}
                >
                  {markers[step.state]}
                </Text>
                <View style={ui.grow}>
                  <Text style={ui.label}>{step.title}</Text>
                  {!!step.detail && <Text style={ui.small}>{step.detail}</Text>}
                </View>
              </View>
            ))}
        </View>
      )}
      {!!status?.recovery && <Text style={ui.body}>{status.recovery}</Text>}
      {!!error && (
        <Text accessibilityRole="alert" style={ui.error}>
          {error}
        </Text>
      )}
      {status?.state === 'permission_required' && (
        <Action
          testID="setup-permissions"
          label="Grant required permissions"
          disabled={busy}
          onPress={permissions}
        />
      )}
      {status?.state === 'root_required' && (
        <>
          <Text style={ui.small}>
            Enable root inside VMOS and allow Virtual Camera Pro in the guest
            Superuser prompt. Rooting the physical phone is not required for
            this guest configuration.
          </Text>
          <Action
            testID="setup-root"
            label="Check guest root"
            disabled={busy}
            onPress={() => run('check_root', true)}
          />
        </>
      )}
      {status?.canOpenRootManager && (
        <Action
          testID="setup-root-manager"
          label="Open guest Superuser"
          secondary
          disabled={busy}
          onPress={() => run('open_root_manager', false)}
        />
      )}
      {status?.canPrepare && !isSetupReady(status) && (
        <Action
          testID="setup-prepare"
          label={
            status.state === 'repair_required'
              ? 'Repair guest camera'
              : 'Prepare guest camera'
          }
          disabled={busy}
          onPress={prepare}
        />
      )}
      {status?.canVerify && !isSetupReady(status) && (
        <>
          <Text style={ui.small}>
            Verification uses calibration frames and stops any active virtual
            camera. Activate your selected media again afterward.
          </Text>
          <Action
            testID="setup-verify"
            label="Verify camera"
            disabled={busy}
            onPress={() => run('verify', false)}
          />
        </>
      )}
      {isSetupReady(status) && (
        <Action
          testID="setup-continue"
          label="Open camera controls"
          disabled={busy}
          onPress={onContinue}
        />
      )}
      {!busy && !isSetupReady(status) && (
        <Action label="Check again" secondary onPress={() => run('inspect')} />
      )}
      <Action label="Diagnostics" secondary onPress={onDiagnostics} />
      <Text style={ui.small}>
        Install and use camera apps inside this VMOS guest. Camera access in the
        host VMOS settings must be enabled before guest startup when that
        environment requires it.
      </Text>
    </Page>
  );
}
