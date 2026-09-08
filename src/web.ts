import { WebPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';

import type {
  UsbSerialPlugin,
  ListDevicesResult,
  RegisterDriverOptions,
  DeviceRef,
  PermissionResult,
  RequestPermissionResult,
  OpenOptions,
  OpenResult,
  PortRef,
  IsOpenResult,
  PortInfo,
  SerialParameters,
  ReadOptions,
  ReadResult,
  WriteOptions,
  WriteResult,
  WriteAsyncOptions,
  StartReadingOptions,
  StreamConfig,
  StreamStateResult,
  ControlLinesState,
  SupportedControlLines,
  SetLineOptions,
  BooleanResult,
  SetFlowControlOptions,
  FlowControlResult,
  SupportedFlowControlResult,
  PurgeHwBuffersOptions,
  SetReadQueueOptions,
  ReadQueueConfig,
  ListBulkDevicesResult,
  OpenBulkOptions,
  OpenBulkResult,
  BulkRef,
  BulkInfo,
  BulkReadOptions,
  BulkWriteOptions,
  StartBulkReadingOptions,
} from './definitions';

/** Error carrying a stable `code`, matching the native rejection contract. */
class UsbSerialError extends Error {
  code: string;
  constructor(message: string, code: string) {
    super(message);
    this.name = 'UsbSerialError';
    this.code = code;
  }
}

function unsupported(): Promise<never> {
  return Promise.reject(
    new UsbSerialError('USB serial is only available on Android.', 'UNSUPPORTED_PLATFORM'),
  );
}

/**
 * Web (and any non-Android) stub. Every method rejects with UNSUPPORTED_PLATFORM;
 * addListener returns a no-op subscription so shared code does not throw.
 */
export class WebUsbSerial extends WebPlugin implements UsbSerialPlugin {
  listDevices(): Promise<ListDevicesResult> {
    return unsupported();
  }
  listBulkDevices(): Promise<ListBulkDevicesResult> {
    return unsupported();
  }
  registerDriver(_options: RegisterDriverOptions): Promise<void> {
    return unsupported();
  }
  requestPermission(_options: DeviceRef): Promise<RequestPermissionResult> {
    return unsupported();
  }
  hasPermission(_options: DeviceRef): Promise<PermissionResult> {
    return unsupported();
  }
  open(_options: OpenOptions): Promise<OpenResult> {
    return unsupported();
  }
  close(_options: PortRef): Promise<void> {
    return unsupported();
  }
  isOpen(_options: PortRef): Promise<IsOpenResult> {
    return unsupported();
  }
  getPortInfo(_options: PortRef): Promise<PortInfo> {
    return unsupported();
  }
  openBulk(_options: OpenBulkOptions): Promise<OpenBulkResult> {
    return unsupported();
  }
  closeBulk(_options: BulkRef): Promise<void> {
    return unsupported();
  }
  isBulkOpen(_options: BulkRef): Promise<IsOpenResult> {
    return unsupported();
  }
  getBulkInfo(_options: BulkRef): Promise<BulkInfo> {
    return unsupported();
  }
  bulkRead(_options: BulkReadOptions): Promise<ReadResult> {
    return unsupported();
  }
  bulkWrite(_options: BulkWriteOptions): Promise<WriteResult> {
    return unsupported();
  }
  startBulkReading(_options: StartBulkReadingOptions): Promise<void> {
    return unsupported();
  }
  stopBulkReading(_options: BulkRef): Promise<void> {
    return unsupported();
  }
  getBulkStreamState(_options: BulkRef): Promise<StreamStateResult> {
    return unsupported();
  }
  setParameters(_options: SerialParameters): Promise<void> {
    return unsupported();
  }
  read(_options: ReadOptions): Promise<ReadResult> {
    return unsupported();
  }
  write(_options: WriteOptions): Promise<WriteResult> {
    return unsupported();
  }
  writeAsync(_options: WriteAsyncOptions): Promise<void> {
    return unsupported();
  }
  startReading(_options: StartReadingOptions): Promise<void> {
    return unsupported();
  }
  stopReading(_options: PortRef): Promise<void> {
    return unsupported();
  }
  getStreamState(_options: PortRef): Promise<StreamStateResult> {
    return unsupported();
  }
  getStreamConfig(_options: PortRef): Promise<StreamConfig> {
    return unsupported();
  }
  getControlLines(_options: PortRef): Promise<ControlLinesState> {
    return unsupported();
  }
  getSupportedControlLines(_options: PortRef): Promise<SupportedControlLines> {
    return unsupported();
  }
  getCD(_options: PortRef): Promise<BooleanResult> {
    return unsupported();
  }
  getCTS(_options: PortRef): Promise<BooleanResult> {
    return unsupported();
  }
  getDSR(_options: PortRef): Promise<BooleanResult> {
    return unsupported();
  }
  getDTR(_options: PortRef): Promise<BooleanResult> {
    return unsupported();
  }
  getRI(_options: PortRef): Promise<BooleanResult> {
    return unsupported();
  }
  getRTS(_options: PortRef): Promise<BooleanResult> {
    return unsupported();
  }
  setDTR(_options: SetLineOptions): Promise<void> {
    return unsupported();
  }
  setRTS(_options: SetLineOptions): Promise<void> {
    return unsupported();
  }
  setFlowControl(_options: SetFlowControlOptions): Promise<void> {
    return unsupported();
  }
  getFlowControl(_options: PortRef): Promise<FlowControlResult> {
    return unsupported();
  }
  getSupportedFlowControl(_options: PortRef): Promise<SupportedFlowControlResult> {
    return unsupported();
  }
  getXON(_options: PortRef): Promise<BooleanResult> {
    return unsupported();
  }
  purgeHwBuffers(_options: PurgeHwBuffersOptions): Promise<void> {
    return unsupported();
  }
  setBreak(_options: SetLineOptions): Promise<void> {
    return unsupported();
  }
  setReadQueue(_options: SetReadQueueOptions): Promise<void> {
    return unsupported();
  }
  getReadQueueConfig(_options: PortRef): Promise<ReadQueueConfig> {
    return unsupported();
  }

  /** No-op subscription so cross-platform code can register listeners safely. */
  async addListener(
    _eventName: string,
    _listenerFunc: (...args: any[]) => void,
  ): Promise<PluginListenerHandle> {
    return { remove: async () => undefined };
  }

  async removeAllListeners(): Promise<void> {
    return undefined;
  }
}
