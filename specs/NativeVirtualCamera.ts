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
  setReplacementMode(mode: string): Promise<void>;
  getOverlayStatus(): Promise<string>;
  requestOverlayPermission(): Promise<string>;
  setOverlayVisible(visible: boolean): Promise<string>;
  getStatus(): Promise<string>;
  getCameras(): Promise<string>;
  checkSystemSupport(): Promise<string>;
  getSetupStatus(): Promise<string>;
  runSetup(action: string, approved: boolean): Promise<string>;
  requestSetupPermissions(): Promise<string>;
  clearMedia(): Promise<void>;
  setRecoveryPreferences(
    autoRecover: boolean,
    autoResume: boolean,
  ): Promise<void>;
  exportDiagnostics(): Promise<string>;
}
export default TurboModuleRegistry.getEnforcing<Spec>('VirtualCamera');
