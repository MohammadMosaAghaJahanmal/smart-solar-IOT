import React, { useEffect, useState } from 'react';
import {
  Alert,
  Pressable,
  ScrollView,
  Share,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { Action, Page, RecoveryPreferences, ui } from '../components/SetupUI';
import type { SetupController } from '../hooks/useSetup';
import { camera } from '../services/camera';
import { setup } from '../services/setup';

function details(value: unknown): string {
  return typeof value === 'string'
    ? value
    : JSON.stringify(value, null, 2) ?? 'Not reported';
}

function verificationDate(value: number | string): string {
  const date = new Date(
    typeof value === 'string' && /^\d+$/.test(value) ? Number(value) : value,
  );
  return Number.isNaN(date.getTime()) ? 'Not available' : date.toLocaleString();
}

function Report({ title, value }: { title: string; value: unknown }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <View style={ui.card}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`${title}: ${
          expanded ? 'Hide details' : 'Show details'
        }`}
        accessibilityState={{ expanded }}
        onPress={() => setExpanded(current => !current)}
        style={s.reportHeading}
      >
        <Text style={[ui.heading, s.reportTitle]}>{title}</Text>
        <Text style={ui.small}>
          {expanded ? 'Hide details −' : 'Details +'}
        </Text>
      </Pressable>
      {expanded && (
        <ScrollView
          nestedScrollEnabled
          style={s.reportScroll}
          contentContainerStyle={s.reportContent}
          accessibilityLabel={`${title} details`}
        >
          <Text selectable style={ui.small}>
            {details(value)}
          </Text>
        </ScrollView>
      )}
    </View>
  );
}

export function DiagnosticsScreen({
  controller,
  onBack,
}: {
  controller: SetupController;
  onBack: () => void;
}) {
  const [pipeline, setPipeline] = useState<unknown>();
  const [error, setError] = useState('');
  const [exporting, setExporting] = useState(false);
  const { status, busy, run } = controller;
  useEffect(() => {
    let alive = true;
    Promise.all([camera.status(), camera.cameras(), camera.support()])
      .then(
        ([transport, cameras, support]) =>
          alive && setPipeline({ transport, cameras, support }),
      )
      .catch(failure => alive && setError(String(failure.message)));
    return () => {
      alive = false;
    };
  }, [status]);
  const exportReport = async () => {
    setExporting(true);
    setError('');
    try {
      const report = await setup.export();
      await Share.share({
        title: 'Virtual Camera Pro diagnostics',
        message: report,
      });
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setExporting(false);
    }
  };
  const restore = () =>
    Alert.alert(
      'Restore original guest camera files?',
      'This stops the virtual camera and uses guest root to restore the verified backups of camera files changed by Virtual Camera Pro. The guest camera provider may restart. Virtual camera use will require setup again. Media files and the physical phone system are not modified.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Restore guest camera',
          style: 'destructive',
          onPress: () => run('restore', true),
        },
      ],
    );
  const verify = () =>
    Alert.alert(
      'Verify camera delivery?',
      'Verification uses calibration frames and stops any active virtual camera. Activate your selected media again afterward.',
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Verify camera', onPress: () => run('verify', false) },
      ],
    );
  return (
    <Page
      title="Diagnostics"
      subtitle="Live guest checks, camera capabilities and component health."
    >
      <Action label="Back" secondary onPress={onBack} />
      <View style={ui.card}>
        <Text style={ui.heading}>{status?.title || 'Inspection pending'}</Text>
        <Text style={ui.body}>
          {status?.message || 'No setup result is available yet.'}
        </Text>
        {!!status?.errorCode && (
          <Text style={ui.error}>{status.errorCode}</Text>
        )}
        {!!status?.recovery && <Text style={ui.body}>{status.recovery}</Text>}
        {!!status?.lastVerifiedAt && (
          <Text style={ui.small}>
            Last verification: {verificationDate(status.lastVerifiedAt)}
          </Text>
        )}
      </View>
      <Action
        label="Recheck environment"
        disabled={busy}
        onPress={() => run('inspect')}
      />
      {status?.canVerify && (
        <Action
          testID="diagnostics-verify"
          label="Verify camera delivery"
          secondary
          disabled={busy}
          onPress={verify}
        />
      )}
      <RecoveryPreferences controller={controller} />
      {!!(error || controller.error) && (
        <Text accessibilityRole="alert" style={ui.error}>
          {error || controller.error}
        </Text>
      )}
      <Action
        testID="diagnostics-export"
        label={exporting ? 'Preparing report…' : 'Export sanitized diagnostics'}
        disabled={exporting || busy}
        secondary
        onPress={exportReport}
      />
      <Text style={ui.small}>
        Export removes private media references and sensitive identifiers.
        Review the report in your chosen sharing app before sending it.
      </Text>
      {status?.canRestore && (
        <Action
          testID="setup-restore"
          label="Restore original guest camera"
          secondary
          disabled={busy}
          onPress={restore}
        />
      )}
      <Report title="Guest and environment" value={status?.environment} />
      <Report title="Installed components" value={status?.components} />
      <Report title="Camera and frame pipeline" value={pipeline} />
    </Page>
  );
}

const s = StyleSheet.create({
  reportHeading: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    minHeight: 44,
  },
  reportTitle: { flex: 1 },
  reportScroll: { flexGrow: 0, maxHeight: 320 },
  reportContent: { paddingRight: 8, paddingBottom: 8 },
});
