import React, { useMemo, useRef, useState } from 'react';
import {
  PanResponder,
  Pressable,
  requireNativeComponent,
  StyleSheet,
  Text,
  View,
  type GestureResponderEvent,
  type ViewProps,
} from 'react-native';
import type { SourceTransform } from '../types/camera';

interface PreviewProperties extends SourceTransform {
  uri: string;
  mediaType: string;
  rotation: number;
  scaleMode: string;
  loop: boolean;
}
const NativePreview = requireNativeComponent<ViewProps & PreviewProperties>(
  'MediaPreview',
);
const clamp = (value: number, minimum: number, maximum: number) =>
  Math.min(maximum, Math.max(minimum, value));
function touches(event: GestureResponderEvent) {
  const points = event.nativeEvent.touches;
  const first = points[0];
  const second = points[1];
  if (!first) {
    return { x: 0, y: 0, distance: 0, count: 0 };
  }
  return {
    x: second ? (first.pageX + second.pageX) / 2 : first.pageX,
    y: second ? (first.pageY + second.pageY) / 2 : first.pageY,
    distance: second
      ? Math.hypot(first.pageX - second.pageX, first.pageY - second.pageY)
      : 0,
    count: second ? 2 : 1,
  };
}
export function MediaPreview({
  uri,
  type,
  rotation,
  scaleMode,
  loop,
  zoom,
  panX,
  panY,
  disabled,
  onTransform,
  onGesture,
}: {
  uri: string;
  type: string;
  rotation: number;
  scaleMode: string;
  loop: boolean;
  zoom: number;
  panX: number;
  panY: number;
  disabled?: boolean;
  onTransform: (value: SourceTransform) => void;
  onGesture: (active: boolean) => void;
}) {
  const [size, setSize] = useState({ width: 1, height: 1 });
  const current = useRef({ zoom, panX, panY });
  const options = useRef({ disabled, onTransform, onGesture, size });
  current.current = { zoom, panX, panY };
  options.current = { disabled, onTransform, onGesture, size };
  const origin = useRef({
    ...current.current,
    x: 0,
    y: 0,
    distance: 0,
    count: 0,
  });
  const responder = useMemo(() => {
    const begin = (event: GestureResponderEvent) => {
      origin.current = { ...current.current, ...touches(event) };
    };
    const end = () => options.current.onGesture(false);
    return PanResponder.create({
      onStartShouldSetPanResponder: () => false,
      onMoveShouldSetPanResponderCapture: (event, state) =>
        !options.current.disabled &&
        (event.nativeEvent.touches.length > 1 ||
          Math.abs(state.dx) + Math.abs(state.dy) > 4),
      onPanResponderGrant: event => {
        begin(event);
        options.current.onGesture(true);
      },
      onPanResponderMove: event => {
        const point = touches(event);
        if (point.count !== origin.current.count) {
          begin(event);
          return;
        }
        const start = origin.current;
        const nextZoom = clamp(
          start.distance > 0
            ? (start.zoom * point.distance) / start.distance
            : start.zoom,
          1,
          8,
        );
        const overflow = Math.max(0.25, nextZoom - 1);
        const next = {
          zoom: nextZoom,
          panX: clamp(
            start.panX +
              (2 * (point.x - start.x)) /
                (options.current.size.width * overflow),
            -1,
            1,
          ),
          panY: clamp(
            start.panY +
              (2 * (point.y - start.y)) /
                (options.current.size.height * overflow),
            -1,
            1,
          ),
        };
        current.current = next;
        options.current.onTransform(next);
      },
      onPanResponderRelease: end,
      onPanResponderTerminate: end,
      onPanResponderTerminationRequest: () => false,
    });
  }, []);
  const changeZoom = (amount: number) =>
    onTransform({ zoom: clamp(zoom + amount, 1, 8), panX, panY });
  return (
    <View>
      <View
        testID="source-preview"
        style={styles.frame}
        onLayout={event => setSize(event.nativeEvent.layout)}
        {...responder.panHandlers}
      >
        <NativePreview
          pointerEvents="none"
          style={StyleSheet.absoluteFill}
          uri={uri}
          mediaType={type}
          rotation={rotation}
          scaleMode={scaleMode}
          loop={loop}
          zoom={zoom}
          panX={panX}
          panY={panY}
        />
      </View>
      <View style={styles.controls}>
        <Text style={styles.hint}>Pinch to zoom · Drag to reframe</Text>
        <View style={styles.buttons}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Zoom out"
            accessibilityState={{ disabled: disabled || zoom <= 1 }}
            disabled={disabled || zoom <= 1}
            onPress={() => changeZoom(-0.25)}
            style={styles.button}
          >
            <Text style={styles.buttonText}>−</Text>
          </Pressable>
          <Text accessibilityLiveRegion="polite" style={styles.zoom}>
            {zoom.toFixed(2)}×
          </Text>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Zoom in"
            accessibilityState={{ disabled: disabled || zoom >= 8 }}
            disabled={disabled || zoom >= 8}
            onPress={() => changeZoom(0.25)}
            style={styles.button}
          >
            <Text style={styles.buttonText}>+</Text>
          </Pressable>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Reset source zoom and position"
            disabled={disabled}
            onPress={() => onTransform({ zoom: 1, panX: 0, panY: 0 })}
            style={styles.reset}
          >
            <Text style={styles.resetText}>Reset</Text>
          </Pressable>
        </View>
      </View>
    </View>
  );
}
const styles = StyleSheet.create({
  frame: {
    width: '100%',
    aspectRatio: 16 / 9,
    backgroundColor: '#090e15',
    overflow: 'hidden',
  },
  controls: { paddingHorizontal: 13, paddingVertical: 10, gap: 8 },
  hint: { color: '#90a1b6', fontSize: 11 },
  buttons: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  button: {
    width: 44,
    height: 44,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 9,
    backgroundColor: '#1a2534',
  },
  buttonText: { color: '#a1f4d2', fontSize: 23 },
  zoom: { color: '#d9e3f0', minWidth: 52, textAlign: 'center', fontSize: 13 },
  reset: { marginLeft: 'auto', padding: 12 },
  resetText: { color: '#a1f4d2', fontSize: 12 },
});
