import React from 'react';
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
import type { SetupController } from '../hooks/useSetup';

export function Page({
  title,
  subtitle,
  children,
}: React.PropsWithChildren<{ title: string; subtitle: string }>) {
  return (
    <SafeAreaView style={ui.safe}>
      <StatusBar barStyle="light-content" backgroundColor="#0c121c" />
      <ScrollView contentContainerStyle={ui.content}>
        <Text style={ui.brand}>VIRTUAL CAMERA PRO</Text>
        <Text style={ui.title}>{title}</Text>
        <Text style={ui.body}>{subtitle}</Text>
        {children}
      </ScrollView>
    </SafeAreaView>
  );
}

export function Action({
  label,
  onPress,
  disabled = false,
  secondary = false,
  testID,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  secondary?: boolean;
  testID?: string;
}) {
  return (
    <Pressable
      testID={testID}
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={[ui.button, secondary && ui.secondary, disabled && ui.disabled]}
    >
      <Text style={[ui.buttonText, secondary && ui.secondaryText]}>
        {label}
      </Text>
    </Pressable>
  );
}

export function Progress() {
  return (
    <View style={ui.row}>
      <ActivityIndicator color="#a1f4d2" />
      <Text style={ui.body}>Checking actual guest components…</Text>
    </View>
  );
}

export function RecoveryPreferences({
  controller,
}: {
  controller: SetupController;
}) {
  const preferences = controller.status?.preferences;
  if (!preferences) {
    return null;
  }
  return (
    <View style={ui.card}>
      <Text style={ui.heading}>Recovery preferences</Text>
      <View style={ui.row}>
        <View style={ui.grow}>
          <Text style={ui.label}>Automatic recovery</Text>
          <Text style={ui.small}>
            Recover the internal service after interruptions when Android
            permits. System file changes still require approval.
          </Text>
        </View>
        <Switch
          accessibilityLabel="Automatic recovery"
          value={preferences.autoRecover}
          disabled={controller.busy}
          onValueChange={value =>
            controller.preferences(value, preferences.autoResume)
          }
          trackColor={{ true: '#327e6d', false: '#384357' }}
        />
      </View>
      <View style={ui.row}>
        <View style={ui.grow}>
          <Text style={ui.label}>Resume my active camera</Text>
          <Text style={ui.small}>
            After recovery, verify and resume previously active media when
            permitted. A camera you deactivated stays off.
          </Text>
        </View>
        <Switch
          accessibilityLabel="Resume my active camera"
          value={preferences.autoResume}
          disabled={controller.busy}
          onValueChange={value =>
            controller.preferences(preferences.autoRecover, value)
          }
          trackColor={{ true: '#327e6d', false: '#384357' }}
        />
      </View>
    </View>
  );
}

export const ui = StyleSheet.create({
  safe: { flex: 1, backgroundColor: '#0c121c' },
  content: {
    padding: 24,
    paddingBottom: 40,
    gap: 18,
    maxWidth: 700,
    width: '100%',
    alignSelf: 'center',
  },
  brand: { color: '#9cf0ce', fontSize: 10, letterSpacing: 2, marginTop: 12 },
  title: { color: '#f0f4fa', fontSize: 28, fontWeight: '700' },
  body: { color: '#a8b8ca', fontSize: 14, lineHeight: 22 },
  small: { color: '#90a1b6', fontSize: 12, lineHeight: 19 },
  label: { color: '#e1eaf4', fontSize: 14, fontWeight: '600' },
  heading: { color: '#e1eaf4', fontSize: 17, fontWeight: '600' },
  card: {
    backgroundColor: '#131e2c',
    borderWidth: 1,
    borderColor: '#263446',
    borderRadius: 17,
    padding: 18,
    gap: 16,
  },
  row: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  grow: { flex: 1, gap: 6 },
  button: {
    backgroundColor: '#a1f4d2',
    padding: 17,
    borderRadius: 13,
    alignItems: 'center',
  },
  buttonText: { color: '#10291f', fontWeight: '700', fontSize: 14 },
  secondary: {
    backgroundColor: '#1a2534',
    borderWidth: 1,
    borderColor: '#34455c',
  },
  secondaryText: { color: '#d4e1ef' },
  disabled: { opacity: 0.45 },
  error: { color: '#ffb3a9', fontSize: 13, lineHeight: 21 },
  complete: { color: '#a1f4d2' },
});
