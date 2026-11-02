import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';
import { PanResponder } from 'react-native';
import { MediaPreview } from '../src/components/MediaPreview';

const transform = jest.fn();
const gesture = jest.fn();
const defaults = {
  uri: 'file:///source.mp4',
  type: 'video',
  rotation: 90,
  scaleMode: 'fill',
  loop: true,
  zoom: 1,
  panX: 0,
  panY: 0,
  onTransform: transform,
  onGesture: gesture,
};

beforeEach(() => {
  transform.mockClear();
  gesture.mockClear();
});

test('zoom buttons adjust output and reset clears both position axes', async () => {
  let renderer: TestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = TestRenderer.create(<MediaPreview {...defaults} />);
  });
  const button = (label: string) =>
    renderer!.root.findAllByProps({ accessibilityLabel: label })[0];
  expect(button('Zoom out').props.disabled).toBe(true);
  await act(async () => button('Zoom in').props.onPress());
  expect(transform).toHaveBeenLastCalledWith({ zoom: 1.25, panX: 0, panY: 0 });
  await act(async () => {
    renderer!.update(
      <MediaPreview {...defaults} zoom={8} panX={0.8} panY={-0.6} />,
    );
  });
  expect(button('Zoom in').props.disabled).toBe(true);
  await act(async () =>
    button('Reset source zoom and position').props.onPress(),
  );
  expect(transform).toHaveBeenLastCalledWith({ zoom: 1, panX: 0, panY: 0 });
  await act(async () => renderer!.unmount());
});

test('pinch and drag preserve positive right/down coordinates and bound the crop', async () => {
  let handlers: Parameters<typeof PanResponder.create>[0];
  const spy = jest.spyOn(PanResponder, 'create').mockImplementation(config => {
    handlers = config;
    return { panHandlers: {} };
  });
  let renderer: TestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = TestRenderer.create(<MediaPreview {...defaults} />);
  });
  await act(async () => {
    renderer!.root
      .findAllByProps({ testID: 'source-preview' })[0]
      .props.onLayout({
        nativeEvent: { layout: { width: 200, height: 100 } },
      });
  });
  const event = (points: number[][]) =>
    ({
      nativeEvent: {
        touches: points.map(([pageX, pageY]) => ({ pageX, pageY })),
      },
    } as any);
  await act(async () => {
    handlers!.onPanResponderGrant!(
      event([
        [50, 50],
        [150, 50],
      ]),
      {} as any,
    );
    handlers!.onPanResponderMove!(
      event([
        [0, 50],
        [200, 50],
      ]),
      {} as any,
    );
  });
  expect(transform).toHaveBeenLastCalledWith({ zoom: 2, panX: 0, panY: 0 });
  await act(async () => {
    handlers!.onPanResponderMove!(event([[100, 50]]), {} as any);
    handlers!.onPanResponderMove!(event([[150, 75]]), {} as any);
  });
  expect(transform).toHaveBeenLastCalledWith({ zoom: 2, panX: 0.5, panY: 0.5 });
  await act(async () => {
    handlers!.onPanResponderMove!(event([[2000, 2000]]), {} as any);
    handlers!.onPanResponderRelease!(event([]), {} as any);
  });
  expect(transform).toHaveBeenLastCalledWith({ zoom: 2, panX: 1, panY: 1 });
  expect(gesture.mock.calls).toEqual([[true], [false]]);
  await act(async () => renderer!.unmount());
  spy.mockRestore();
});
