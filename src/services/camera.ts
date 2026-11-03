import NativeVirtualCamera from '../../specs/NativeVirtualCamera';
import type {
  CameraStatus,
  MediaType,
  SystemSupport,
  CameraDevice,
  OverlayStatus,
  ReplacementMode,
} from '../types/camera';
export const camera = {
  async cameras(): Promise<CameraDevice[]> {
    return JSON.parse(await NativeVirtualCamera.getCameras());
  },
  async pick(type: MediaType) {
    const media = JSON.parse(await NativeVirtualCamera.pickMedia(type)) as {
      uri: string;
    };
    await NativeVirtualCamera.selectMedia(media.uri, type);
  },
  async status(): Promise<CameraStatus> {
    return JSON.parse(await NativeVirtualCamera.getStatus());
  },
  async support(): Promise<SystemSupport> {
    return JSON.parse(await NativeVirtualCamera.checkSystemSupport());
  },
  activate: () => NativeVirtualCamera.activate(),
  deactivate: () => NativeVirtualCamera.deactivate(),
  clear: () => NativeVirtualCamera.clearMedia(),
  loop: (enabled: boolean) => NativeVirtualCamera.setLoop(enabled),
  resolution: (width: number, height: number) =>
    NativeVirtualCamera.setResolution(width, height),
  output: (rotation: number, scale: string, id: string) =>
    NativeVirtualCamera.setOutput(rotation, scale, id),
  transform: (zoom: number, panX: number, panY: number) =>
    NativeVirtualCamera.setSourceTransform(zoom, panX, panY),
  replacementMode: (mode: ReplacementMode) =>
    NativeVirtualCamera.setReplacementMode(mode),
  async overlayStatus(): Promise<OverlayStatus> {
    return JSON.parse(await NativeVirtualCamera.getOverlayStatus());
  },
  async requestOverlayPermission(): Promise<OverlayStatus> {
    return JSON.parse(await NativeVirtualCamera.requestOverlayPermission());
  },
  async overlayVisible(visible: boolean): Promise<OverlayStatus> {
    return JSON.parse(await NativeVirtualCamera.setOverlayVisible(visible));
  },
};
