export type MediaType = 'image' | 'video';
export interface SourceTransform {
  zoom: number;
  panX: number;
  panY: number;
}
export type Phase =
  | 'ready'
  | 'activating'
  | 'active'
  | 'stopped'
  | 'integration_required'
  | 'error';
export interface CameraStatus {
  phase: Phase;
  message: string;
  uri: string;
  type: MediaType;
  name: string;
  loop: boolean;
  width: number;
  height: number;
  rotation: number;
  scaleMode: 'fit' | 'fill';
  zoom?: number;
  panX?: number;
  panY?: number;
  cameraId: string;
  virtualId?: string;
  frames?: number;
  streams?: number;
  stage?: string;
  recovery?: string;
  code?: string;
  backend?: string;
  proof?: {
    previewFrames: number;
    jpegBytes: number;
    producerFrames?: number;
    backend?: string;
    sourceVerified?: boolean;
    sourceSha256?: string;
  };
}
export interface SystemSupport {
  supported: boolean;
  message: string;
  protocol?: number;
  code?: string;
  component?: string;
  recovery?: string;
  backend?: string;
  resolutions?: { width: number; height: number }[];
  device?: {
    androidSdk: number;
    androidRelease: string;
    manufacturer: string;
    model: string;
    supportedAbis: string[];
    controllerMinSdk: number;
    controllerInstallCompatible: boolean;
    referenceBackendSdk: number;
    referenceBackendApiMatches: boolean;
  };
  backendSupport?: {
    backend: string;
    verification: string;
    androidSdks: number[];
    architectures?: string[];
    physicalDeviceVerified: boolean;
    perFirmwarePortRequired?: boolean;
  }[];
}
export interface CameraDevice {
  id: string;
  facing: 'front' | 'back' | 'external';
  hardwareLevel?: number;
  sensorOrientation?: number;
}
