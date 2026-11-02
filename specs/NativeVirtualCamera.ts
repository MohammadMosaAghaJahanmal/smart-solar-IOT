import { TurboModule, TurboModuleRegistry } from 'react-native';
export interface Spec extends TurboModule {
  pickMedia(type: string): Promise<string>;
  selectMedia(uri: string, type: string): Promise<void>;
  activate(): Promise<boolean>;
  deactivate(): Promise<void>;
  setLoop(enabled: boolean): Promise<void>;
  setResolution(width: number, height: number): Promise<void>;
  setOutput(
    rotation: number,
    scaleMode: string,
    cameraId: string,
  ): Promise<void>;
  setSourceTransform(zoom: number, panX: number, panY: number): Promise<void>;
  getStatus(): Promise<string>;
  getCameras(): Promise<string>;
  checkSystemSupport(): Promise<string>;
}
export default TurboModuleRegistry.getEnforcing<Spec>('VirtualCamera');
