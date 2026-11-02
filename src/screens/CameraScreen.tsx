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
  onPress,
}: {
  label: string;
  selected?: boolean;
  disabled?: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ selected, disabled }}
      disabled={disabled}
      onPress={onPress}
      style={[s.choice, selected && s.selected, disabled && s.disabled]}
    >
      <Text style={[s.choiceText, selected && s.selectedText]}>{label}</Text>
    </Pressable>
  );
}
export function CameraScreen() {
  const {
    status,
    support,
    busy,
    error,
    run,
    cameras,
    activating,
    updateTransform,
  } = useCamera();
  const [adjustingSource, setAdjustingSource] = useState(false);
  const active = status?.phase === 'active';
  const selectedCameraExists = cameras.some(
    device => device.id === status?.cameraId,
  );
  const disabled =
    busy ||
    (!active && (!status?.uri || !support?.supported || !selectedCameraExists));
  const output = (
    rotation = status?.rotation ?? 0,
    scale = status?.scaleMode ?? 'fit',
    id = status?.cameraId ?? '0',
  ) => run(() => camera.output(rotation, scale, id));
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
        <View style={s.status}>
          <View style={[s.dot, active && s.dotActive]} />
          <View style={s.grow}>
            <Text style={s.statusTitle}>
              {activating
                ? 'Activating…'
                : status
                ? titles[status.phase]
                : 'Checking camera engine…'}
            </Text>
            <Text style={s.muted}>
              {active
                ? status?.backend === 'android_emulator_environment'
                  ? `Camera ${status.cameraId} · Selected media is active`
                  : `Camera ${status?.cameraId} → ${status?.virtualId} · ${
                      status?.streams ?? 0
                    } streams`
                : support?.message ?? 'Connecting to Android'}
            </Text>
          </View>
          <Text style={s.badge}>{active ? 'VIRTUAL' : 'REAL'}</Text>
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
          <Text numberOfLines={1} style={s.filename}>
            {status.name}
          </Text>
        )}
        <Text style={s.section}>
          02 <Text style={s.sectionTitle}>Camera output</Text>
        </Text>
        <View style={s.card}>
          <View style={s.setting}>
            <View>
              <Text style={s.label}>Output resolution</Text>
              <Text style={s.muted}>30 frames per second</Text>
            </View>
            <Text style={s.detail}>16:9</Text>
          </View>
          <View style={s.row}>
            {[
              [1280, 720, '720p'],
              [1920, 1080, '1080p'],
            ].map(([w, h, label]) => (
              <Choice
                key={label}
                label={String(label)}
                selected={status?.width === w}
                disabled={
                  busy ||
                  active ||
                  (support?.resolutions !== undefined &&
                    !support.resolutions.some(
                      size => size.width === w && size.height === h,
                    ))
                }
                onPress={() =>
                  run(() => camera.resolution(Number(w), Number(h)))
                }
              />
            ))}
          </View>
          <View style={s.divider} />
          <View style={s.setting}>
            <View>
              <Text style={s.label}>Loop video</Text>
              <Text style={s.muted}>Play continuously in the background</Text>
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
          <Text style={s.label}>Replace camera ID</Text>
          <View style={s.row}>
            {cameras.map(device => (
              <Choice
                key={device.id}
                label={`Camera ${device.id} · ${device.facing}`}
                selected={status?.cameraId === device.id}
                disabled={busy || active}
                onPress={() => output(undefined, undefined, device.id)}
              />
            ))}
          </View>
          <Text style={s.muted}>
            {cameras.length === 0
              ? 'Android has not reported any physical camera IDs.'
              : !selectedCameraExists
              ? 'Select one of the camera IDs available on this device.'
              : 'Routing changes apply when a camera client opens a new session.'}
          </Text>
        </View>
        {!!error && (
          <Text accessibilityRole="alert" style={s.error}>
            {error}
          </Text>
        )}
        {status?.phase === 'error' && (
          <Text style={s.error}>{status.message}</Text>
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
        <Text style={s.footnote}>
          {active
            ? 'The native system service continues after you leave this app. Close and reopen your camera app to use the new source.'
            : support?.supported
            ? 'Activation verifies the selected camera route, native frame delivery and actual YUV/JPEG capture before reporting Active.'
            : `${support?.code ?? 'CHECKING_SYSTEM'}: ${
                support?.recovery ??
                'Checking the platform broker, camera provider and CameraService routing hook…'
              }`}
        </Text>
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
