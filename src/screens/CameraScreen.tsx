import React, { useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MediaPreview } from '../components/MediaPreview';
import { useCamera } from '../hooks/useCamera';
import { camera } from '../services/camera';
import type { SetupStatus } from '../types/setup';
const titles = {
  ready: 'Virtual Camera Ready',
  activating: 'Activating…',
  active: 'Virtual Camera Active',
  stopped: 'Virtual Camera Stopped',
  integration_required: 'System Integration Required',
  error: 'Camera Engine Error',
};
function Choice({
  label,
  selected,
  disabled,
  sizeToContent,
  onPress,
}: {
  label: string;
  selected?: boolean;
  disabled?: boolean;
  sizeToContent?: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ selected, disabled }}
      disabled={disabled}
      onPress={onPress}
      style={[
        s.choice,
        sizeToContent && s.choiceContentWidth,
        selected && s.selected,
        disabled && s.disabled,
      ]}
    >
      <Text
        numberOfLines={sizeToContent ? 1 : undefined}
        style={[s.choiceText, selected && s.selectedText]}
      >
        {label}
      </Text>
    </Pressable>
  );
}
export function CameraScreen({
  setupStatus,
  onDiagnostics,
  onSetup,
}: {
  setupStatus?: SetupStatus;
  onDiagnostics?: () => void;
  onSetup?: () => void;
}) {
  const {
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
  } = useCamera();
  const [adjustingSource, setAdjustingSource] = useState(false);
  const active = status?.phase === 'active';
  const both = status?.replacementMode === 'both';
  const cameraForFacing = (facing: 'front' | 'back') =>
    cameras.find(
      device => device.facing === facing && device.id === status?.cameraId,
    ) ?? cameras.find(device => device.facing === facing);
  const front = cameraForFacing('front');
  const back = cameraForFacing('back');
  const bothSupported =
    support?.replacementModes?.includes('both') === true && !!front && !!back;
  const bothRouteExists =
    bothSupported &&
    !!support?.bothCameraIds?.length &&
    support.bothCameraIds.every(id => cameras.some(device => device.id === id));
  const selectedCameraExists = cameras.some(
    device => device.id === status?.cameraId,
  );
  const selectedCamera = cameras.find(device => device.id === status?.cameraId);
  const resolutions = both
    ? support?.bothResolutions ?? []
    : selectedCamera?.resolutions ?? support?.resolutions ?? [];
  const disabled =
    busy ||
    (!active &&
      (!status?.uri ||
        !support?.supported ||
        !(both ? bothRouteExists : selectedCameraExists)));
  const output = (
    rotation = status?.rotation ?? 0,
    scale = status?.scaleMode ?? 'fit',
    id = status?.cameraId ?? cameras[0]?.id ?? '',
  ) => run(() => camera.output(rotation, scale, id));
  const chooseCamera = (id: string) =>
    run(async () => {
      await camera.replacementMode('single');
      await camera.output(
        status?.rotation ?? 0,
        status?.scaleMode ?? 'fit',
        id,
      );
    });
  return (
    <SafeAreaView style={s.safe}>
      <StatusBar barStyle="light-content" backgroundColor="#0c121c" />
      <ScrollView
        contentContainerStyle={s.content}
        scrollEnabled={!adjustingSource}
      >
        <View style={s.header}>
          <View>
            <Text style={s.eyebrow}>YOUR SOURCE. EVERY FRAME.</Text>
            <Text style={s.title}>
              Virtual Camera <Text style={s.pro}>PRO</Text>
            </Text>
          </View>
          <Text style={s.logo}>◉</Text>
        </View>
        {!!setupStatus && (
          <View style={s.card}>
            <View style={s.setting}>
              <Text style={s.label}>
                Environment · {setupStatus.ready ? 'Ready' : setupStatus.title}
              </Text>
              {!!onDiagnostics && (
                <Pressable accessibilityRole="button" onPress={onDiagnostics}>
                  <Text style={s.selectedText}>Diagnose</Text>
                </Pressable>
              )}
            </View>
            <Text style={s.muted}>
              {setupStatus.profile?.name ?? setupStatus.message}
            </Text>
          </View>
        )}
        <View style={s.status}>
          <View style={[s.dot, active && s.dotActive]} />
          <View style={s.grow}>
            <Text style={s.statusTitle}>
              {status?.stage?.includes('recover') && !active
                ? 'Recovering camera…'
                : activating
                ? 'Activating…'
                : status
                ? titles[status.phase]
                : 'Checking camera engine…'}
            </Text>
            <Text style={s.muted}>
              {active
                ? both
                  ? 'Front and back cameras · Selected media is active'
                  : `Camera ${status.cameraId} · Selected media is active`
                : support?.message ?? 'Connecting to Android'}
            </Text>
          </View>
          <Text style={s.badge}>{active ? 'ACTIVE' : 'INACTIVE'}</Text>
        </View>
        <Text style={s.section}>
          01 <Text style={s.sectionTitle}>Choose your source</Text>
        </Text>
        <View style={s.row}>
          <Choice
            label="▧  Select Image"
            disabled={busy}
            onPress={() => run(() => camera.pick('image'))}
          />
          <Choice
            label="▷  Select Video"
            disabled={busy}
            onPress={() => run(() => camera.pick('video'))}
          />
        </View>
        <View style={s.preview}>
          {status?.uri ? (
            <MediaPreview
              uri={status.uri}
              type={status.type}
              rotation={status.rotation ?? 0}
              scaleMode={status.scaleMode ?? 'fit'}
              loop={status.loop}
              zoom={status.zoom ?? 1}
              panX={status.panX ?? 0}
              panY={status.panY ?? 0}
              disabled={busy}
              onTransform={updateTransform}
              onGesture={setAdjustingSource}
            />
          ) : (
            <View style={s.empty}>
              <Text style={s.emptyIcon}>▣</Text>
              <Text style={s.emptyTitle}>A new perspective starts here</Text>
              <Text style={s.muted}>
                Select an image or video from your gallery
              </Text>
            </View>
          )}
          <View style={s.previewLabel}>
            <Text style={s.previewLabelText}>SOURCE PREVIEW</Text>
            <Text style={s.previewLabelText}>
              {status?.uri ? status.type.toUpperCase() : 'NO MEDIA'}
            </Text>
          </View>
        </View>
        {!!status?.uri && (
          <View style={s.setting}>
            <Text numberOfLines={1} style={[s.filename, s.grow]}>
              {status.name}
            </Text>
            <Pressable
              testID="camera-clear"
              accessibilityLabel="Clear selection"
              accessibilityRole="button"
              accessibilityState={{ disabled: busy }}
              disabled={busy}
              onPress={() => run(camera.clear)}
            >
              <Text style={s.selectedText}>Clear selection</Text>
            </Pressable>
          </View>
        )}
        <Text style={s.section}>
          02 <Text style={s.sectionTitle}>Camera output</Text>
        </Text>
        <View style={s.card}>
          <View style={s.setting}>
            <View>
              <Text style={s.label}>Output resolution</Text>
              <Text style={s.muted}>
                {both
                  ? 'Verified sizes shared by both cameras'
                  : 'Available for the selected camera'}
              </Text>
            </View>
            <Text style={s.detail}>
              {status ? `${status.width} × ${status.height}` : 'Checking…'}
            </Text>
          </View>
          <ScrollView
            horizontal
            accessibilityLabel="Available output resolutions"
            style={s.resolutionScroll}
            contentContainerStyle={s.resolutionChoices}
            showsHorizontalScrollIndicator
          >
            {resolutions.map(({ width, height }) => (
              <Choice
                key={`${width}x${height}`}
                label={`${width} × ${height}`}
                sizeToContent
                selected={status?.width === width && status?.height === height}
                disabled={busy || active}
                onPress={() => run(() => camera.resolution(width, height))}
              />
            ))}
          </ScrollView>
          {resolutions.length === 0 && (
            <Text style={s.muted}>
              {both
                ? 'No shared output sizes have been verified for both cameras. Check Diagnostics.'
                : 'No verified output sizes have been reported for this camera. Check Diagnostics.'}
            </Text>
          )}
          <View style={s.divider} />
          <View style={s.setting}>
            <View>
              <Text style={s.label}>Loop video</Text>
              <Text style={s.muted}>Repeat the selected video</Text>
            </View>
            <Switch
              accessibilityLabel="Loop video"
              value={status?.loop ?? true}
              disabled={busy}
              onValueChange={v => run(() => camera.loop(v))}
              trackColor={{ false: '#384357', true: '#327e6d' }}
              thumbColor="#a1f4d2"
            />
          </View>
        </View>
        <View style={s.card}>
          <Text style={s.label}>Scaling</Text>
          <View style={s.row}>
            {(['fit', 'fill'] as const).map(v => (
              <Choice
                key={v}
                label={v === 'fit' ? 'Fit · letterbox' : 'Fill · crop'}
                selected={status?.scaleMode === v}
                disabled={busy}
                onPress={() => output(undefined, v)}
              />
            ))}
          </View>
          <Text style={s.label}>Rotation</Text>
          <View style={s.row}>
            {[0, 90, 180, 270].map(v => (
              <Choice
                key={v}
                label={`${v}°`}
                selected={status?.rotation === v}
                disabled={busy}
                onPress={() => output(v)}
              />
            ))}
          </View>
          <Text style={s.label}>Camera to replace</Text>
          <View style={s.row}>
            <Choice
              label="Front Camera Only"
              selected={!both && selectedCamera?.facing === 'front'}
              disabled={busy || active || !front}
              onPress={() => front && chooseCamera(front.id)}
            />
            <Choice
              label="Back Camera Only"
              selected={!both && selectedCamera?.facing === 'back'}
              disabled={busy || active || !back}
              onPress={() => back && chooseCamera(back.id)}
            />
          </View>
          <Choice
            label="Replace Both Cameras"
            selected={both}
            disabled={busy || active || !bothSupported}
            onPress={() => run(() => camera.replacementMode('both'))}
          />
          {cameras
            .filter(device => device.id !== front?.id && device.id !== back?.id)
            .map(device => (
              <Choice
                key={device.id}
                label={`Camera ${device.id} · ${device.facing}`}
                selected={!both && status?.cameraId === device.id}
                disabled={busy || active}
                onPress={() => chooseCamera(device.id)}
              />
            ))}
          <Text style={s.muted}>
            {active
              ? 'Deactivate to change camera replacement mode.'
              : cameras.length === 0
              ? 'Android has not reported any camera IDs in this environment.'
              : both && bothRouteExists
              ? 'The same source stays active when another app switches between front and back cameras.'
              : !selectedCameraExists
              ? 'Select one of the camera IDs available on this device.'
              : 'Routing changes apply when a camera client opens a new session.'}
          </Text>
          {!bothSupported && cameras.length > 0 && (
            <Text style={s.muted}>
              Both-camera replacement requires front and back cameras and a
              bridge that reports support for both.
            </Text>
          )}
        </View>
        <View style={s.card}>
          <Text style={s.label}>Reframe in other apps</Text>
          <Text style={s.muted}>
            Apps that send camera zoom or crop requests can adjust the virtual
            feed with their own controls. For other apps, use the optional
            floating controller inside VMOS to zoom, move and reset the actual
            camera frames.
          </Text>
          {overlay?.supported ? (
            overlay.permissionGranted ? (
              <Choice
                label={
                  overlay.visible
                    ? 'Hide Floating Controller'
                    : 'Show Floating Controller'
                }
                disabled={busy || (!overlay.visible && !status?.uri)}
                onPress={() =>
                  run(() => camera.overlayVisible(!overlay.visible))
                }
              />
            ) : (
              <Choice
                label="Allow Floating Controller"
                disabled={busy}
                onPress={() => run(camera.requestOverlayPermission)}
              />
            )
          ) : (
            <Text style={s.muted}>
              {overlay?.message ?? 'Checking floating controller availability…'}
            </Text>
          )}
          {!!overlay?.message && overlay.supported && (
            <Text style={s.muted}>{overlay.message}</Text>
          )}
        </View>
        {!!error && (
          <Text accessibilityRole="alert" style={s.error}>
            {error}
          </Text>
        )}
        {status?.phase === 'error' && (
          <Text style={s.error}>{status.message}</Text>
        )}
        {(status?.phase === 'error' || !!error) && (
          <Choice
            label="Retry camera check"
            disabled={busy}
            onPress={() => run(async () => undefined)}
          />
        )}
        <Pressable
          testID="camera-activation"
          accessibilityRole="button"
          accessibilityState={{
            disabled,
          }}
          disabled={disabled}
          onPress={() =>
            run(active ? camera.deactivate : camera.activate, !active)
          }
          style={[s.activate, active && s.stop, disabled && s.disabled]}
        >
          {busy ? (
            <View style={s.row}>
              <ActivityIndicator color="#0c211b" />
              {activating && <Text style={s.activateText}>Activating…</Text>}
            </View>
          ) : (
            <Text style={s.activateText}>
              {active
                ? '■  Deactivate Virtual Camera'
                : '◉  Activate Virtual Camera'}
            </Text>
          )}
        </Pressable>
        {activating && (
          <Choice
            label={cancelling ? 'Cancelling activation…' : 'Cancel activation'}
            disabled={cancelling}
            onPress={cancelActivation}
          />
        )}
        <Text style={s.footnote}>
          {active
            ? 'The integrated background service keeps supplying frames after you leave this screen. Reopen the other camera app to use a changed source.'
            : support?.supported
            ? 'Activation verifies the selected camera route, native frame delivery and actual YUV/JPEG capture before reporting Active.'
            : `${support?.code ?? 'CHECKING_SYSTEM'}: ${
                support?.recovery ??
                'Checking the platform broker, camera provider and CameraService routing hook…'
              }`}
        </Text>
        {(onDiagnostics || onSetup) && (
          <View style={s.row}>
            {!!onDiagnostics && (
              <Choice label="Diagnostics" onPress={onDiagnostics} />
            )}
            {!!onSetup && (
              <Choice label="Environment setup" onPress={onSetup} />
            )}
          </View>
        )}
        <View style={s.footer}>
          <Text style={s.footerText}>NATIVE FRAME ENGINE</Text>
          <Text style={s.footerText}>
            {support?.device
              ? `ANDROID ${support.device.androidRelease} · API ${support.device.androidSdk}`
              : 'ANDROID'}
          </Text>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}
const s = StyleSheet.create({
  safe: { flex: 1, backgroundColor: '#0c121c' },
  content: {
    padding: 24,
    gap: 18,
    maxWidth: 700,
    width: '100%',
    alignSelf: 'center',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 14,
  },
  eyebrow: {
    fontSize: 10,
    letterSpacing: 2,
    color: '#8a9bb1',
    marginBottom: 12,
  },
  title: {
    fontSize: 27,
    fontWeight: '700',
    color: '#f0f4fa',
    letterSpacing: -1,
  },
  pro: { fontSize: 12, color: '#9cf0ce', letterSpacing: 1 },
  logo: { color: '#9cf0ce', fontSize: 40 },
  status: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    backgroundColor: '#172230',
    borderColor: '#263547',
    borderWidth: 1,
    padding: 15,
    borderRadius: 16,
  },
  grow: { flex: 1, gap: 6 },
  dot: { width: 7, height: 7, borderRadius: 4, backgroundColor: '#d7ad67' },
  dotActive: { backgroundColor: '#9cf0ce' },
  statusTitle: { color: '#eef4fa', fontSize: 13, fontWeight: '600' },
  muted: { color: '#90a1b6', fontSize: 11, lineHeight: 17 },
  badge: { fontSize: 9, fontWeight: '700', color: '#b5c3d5', letterSpacing: 1 },
  section: { fontSize: 11, color: '#6b8d83', letterSpacing: 1, marginTop: 8 },
  sectionTitle: { fontSize: 14, letterSpacing: 0, color: '#d8e2ee' },
  row: { flexDirection: 'row', gap: 10 },
  resolutionScroll: { flexGrow: 0, maxHeight: 60 },
  resolutionChoices: { flexDirection: 'row', gap: 10, paddingBottom: 8 },
  choice: {
    flex: 1,
    minHeight: 43,
    borderRadius: 11,
    backgroundColor: '#1a2534',
    borderWidth: 1,
    borderColor: '#2a384a',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 8,
  },
  selected: { backgroundColor: '#213c36', borderColor: '#548c79' },
  choiceText: { fontSize: 12, color: '#b8c6d7', fontWeight: '600' },
  choiceContentWidth: {
    flex: 0,
    flexShrink: 0,
    minWidth: 124,
    paddingHorizontal: 16,
  },
  selectedText: { color: '#a1f4d2' },
  disabled: { opacity: 0.45 },
  preview: {
    borderRadius: 20,
    overflow: 'hidden',
    borderWidth: 1,
    borderColor: '#263446',
  },
  empty: {
    height: 260,
    backgroundColor: '#101a27',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
  },
  emptyIcon: { fontSize: 40, color: '#456353' },
  emptyTitle: { fontSize: 15, color: '#c9d5e5' },
  previewLabel: {
    padding: 13,
    backgroundColor: '#172230',
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  previewLabelText: { fontSize: 9, letterSpacing: 1.3, color: '#8b9fb8' },
  filename: { color: '#99b4aa', fontSize: 11, marginTop: -8 },
  card: {
    padding: 17,
    borderRadius: 17,
    backgroundColor: '#131e2c',
    borderWidth: 1,
    borderColor: '#233246',
    gap: 15,
  },
  setting: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  label: { color: '#d9e3f0', fontSize: 13, fontWeight: '500' },
  detail: { fontSize: 11, color: '#719089' },
  divider: { height: 1, backgroundColor: '#263446' },
  error: { color: '#ffb3a9', fontSize: 13, lineHeight: 20 },
  activate: {
    backgroundColor: '#a1f4d2',
    minHeight: 56,
    borderRadius: 15,
    justifyContent: 'center',
    alignItems: 'center',
    marginTop: 5,
  },
  stop: { backgroundColor: '#efbdab' },
  activateText: { color: '#10291f', fontWeight: '700', fontSize: 15 },
  footnote: {
    fontSize: 11,
    lineHeight: 18,
    textAlign: 'center',
    color: '#899cb4',
  },
  footer: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 18,
    borderTopWidth: 1,
    borderColor: '#1f2c3e',
  },
  footerText: { fontSize: 8, letterSpacing: 1.5, color: '#61778b' },
});
