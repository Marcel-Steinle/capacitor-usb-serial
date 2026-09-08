import type { Plugin, PluginListenerHandle } from '@capacitor/core';

// ---------------------------------------------------------------------------
// Error codes and enum/union types  (Task 5)
// ---------------------------------------------------------------------------

/**
 * Stable error codes carried on every rejection. Branch on `error.code`.
 */
export type UsbSerialErrorCode =
  | 'NO_DEVICE'
  | 'NEEDS_PERMISSION'
  | 'PERMISSION_DENIED'
  | 'PORT_NOT_OPEN'
  | 'INVALID_PARAMS'
  | 'INVALID_STATE'
  | 'IO_ERROR'
  | 'DEVICE_DISCONNECTED'
  | 'UNSUPPORTED_OPERATION'
  | 'UNSUPPORTED_PLATFORM';

/** Driver class names recognized by usb-serial-for-android. */
export type DriverType =
  | 'FtdiSerialDriver'
  | 'ProlificSerialDriver'
  | 'Cp21xxSerialDriver'
  | 'Ch34xSerialDriver'
  | 'CdcAcmSerialDriver';

export type Parity = 'none' | 'odd' | 'even' | 'mark' | 'space';
export type StopBits = 1 | 1.5 | 2;
export type DataBits = 5 | 6 | 7 | 8;
export type FlowControl = 'none' | 'rts_cts' | 'dtr_dsr' | 'xon_xoff' | 'xon_xoff_inline';

/** State of a port's SerialInputOutputManager stream. */
export type StreamState = 'stopped' | 'starting' | 'running' | 'stopping';

/** Software flow-control characters (mirrors UsbSerialPort.CHAR_XON/CHAR_XOFF). */
export const CHAR_XON = 17;
export const CHAR_XOFF = 19;

// ---------------------------------------------------------------------------
// Option / result / event interfaces  (Task 6)
// ---------------------------------------------------------------------------

export interface SerialDeviceInfo {
  /** Opaque handle valid for the lifetime of a single attachment. */
  deviceId: string;
  vendorId: number;
  productId: number;
  deviceName: string;
  /** USB serial-number string, or null if unavailable / permission not yet granted. */
  serialNumber: string | null;
  driverType: DriverType;
  portCount: number;
  hasPermission: boolean;
}

export interface ListDevicesResult {
  devices: SerialDeviceInfo[];
}

export interface BulkDeviceInfo {
  deviceId: string;
  vendorId: number;
  productId: number;
  deviceName: string;
  serialNumber: string | null;
  hasPermission: boolean;
  interfaceNumber: number;
  interfaceClass: number;
  interfaceSubclass: number;
  interfaceProtocol: number;
  inEndpointAddress: number;
  outEndpointAddress: number;
  inMaxPacketSize: number;
  outMaxPacketSize: number;
}

export interface ListBulkDevicesResult {
  devices: BulkDeviceInfo[];
}
export interface OpenBulkOptions {
  deviceId: string;
  interfaceNumber?: number;
}
export interface OpenBulkResult {
  bulkId: string;
}
export interface BulkRef {
  bulkId: string;
}
export interface BulkInfo extends BulkRef {
  deviceId: string;
  interfaceNumber: number;
  inEndpointAddress: number;
  outEndpointAddress: number;
  inMaxPacketSize: number;
  outMaxPacketSize: number;
}
export interface BulkReadOptions extends BulkRef {
  length?: number;
  timeout?: number;
}
export interface BulkWriteOptions extends BulkRef {
  data: string;
  timeout?: number;
}
export interface StartBulkReadingOptions extends BulkRef {
  bufferSize?: number;
  timeout?: number;
}
export interface BulkDataEvent extends BulkRef {
  data: string;
}
export interface BulkErrorEvent extends BulkRef {
  message: string;
}

export interface RegisterDriverOptions {
  vendorId: number;
  productId: number;
  driverType: DriverType;
  /** When true, only custom mappings are used; otherwise they augment the defaults. */
  replaceDefaults?: boolean;
}

export interface DeviceRef {
  deviceId: string;
}

export interface PermissionResult {
  granted: boolean;
}

export interface RequestPermissionResult extends PermissionResult {
  /**
   * Present (true) when this call was coalesced onto a permission request already
   * pending for the same device: only one system dialog is shown, and every pending
   * call settles with the same result. Absent on the first (dialog-triggering) call.
   */
  coalesced?: boolean;
}

export interface OpenOptions {
  deviceId: string;
  /** Defaults to 0. */
  portNum?: number;
}

export interface OpenResult {
  portId: string;
}

export interface PortRef {
  portId: string;
}

export interface IsOpenResult {
  isOpen: boolean;
}

export interface PortInfo {
  deviceId: string;
  portNum: number;
  serialNumber: string | null;
  driverType: DriverType;
}

export interface SerialParameters {
  portId: string;
  baudRate: number;
  dataBits: DataBits;
  stopBits: StopBits;
  parity: Parity;
}

export interface ReadOptions {
  portId: string;
  /** Max bytes to read, 1..1048576 (1 MiB); defaults to an internal buffer size. */
  length?: number;
  /** Milliseconds; defaults to an internal value. A timeout yields an empty result. */
  timeout?: number;
}

export interface ReadResult {
  /** Base64-encoded bytes; empty string when the read timed out with no data. */
  data: string;
}

export interface WriteOptions {
  portId: string;
  /** Base64-encoded bytes. */
  data: string;
  timeout?: number;
}

export interface WriteResult {
  bytesWritten: number;
}

export interface WriteAsyncOptions {
  portId: string;
  /** Base64-encoded bytes. */
  data: string;
}

export interface StartReadingOptions {
  portId: string;
  readTimeout?: number;
  writeTimeout?: number;
  readBufferSize?: number;
  writeBufferSize?: number;
  readQueueBufferCount?: number;
  threadPriority?: number;
}

export interface StreamConfig {
  readTimeout: number;
  writeTimeout: number;
  readBufferSize: number;
  writeBufferSize: number;
  readQueueBufferCount: number;
}

export interface StreamStateResult {
  state: StreamState;
}

export interface ControlLinesState {
  rts: boolean;
  cts: boolean;
  dtr: boolean;
  dsr: boolean;
  cd: boolean;
  ri: boolean;
}

/** Which control lines a driver supports; unsupported lines are omitted/false. */
export type SupportedControlLines = Partial<ControlLinesState>;

export interface SetLineOptions {
  portId: string;
  value: boolean;
}

export interface BooleanResult {
  value: boolean;
}

export interface SetFlowControlOptions {
  portId: string;
  mode: FlowControl;
}

export interface FlowControlResult {
  mode: FlowControl;
}

export interface SupportedFlowControlResult {
  modes: FlowControl[];
}

export interface PurgeHwBuffersOptions {
  portId: string;
  purgeWrite: boolean;
  purgeRead: boolean;
}

export interface SetReadQueueOptions {
  portId: string;
  bufferCount: number;
  bufferSize: number;
}

export interface ReadQueueConfig {
  bufferCount: number;
  bufferSize: number;
}

// --- Event payloads ---

export interface DataEvent {
  portId: string;
  /** Base64-encoded bytes. */
  data: string;
}

export interface ErrorEvent {
  portId: string;
  message: string;
}

export interface AttachedEvent {
  deviceId: string;
  vendorId: number;
  productId: number;
  deviceName: string;
}

export interface DetachedEvent {
  deviceId: string;
}

// ---------------------------------------------------------------------------
// Plugin interface  (Task 7)
// ---------------------------------------------------------------------------

export interface UsbSerialPlugin extends Plugin {
  // Discovery & prober
  listDevices(): Promise<ListDevicesResult>;
  listBulkDevices(): Promise<ListBulkDevicesResult>;
  registerDriver(options: RegisterDriverOptions): Promise<void>;

  // Permission
  /**
   * Shows the system USB permission dialog and resolves with the user's choice.
   * Concurrent calls for the same device coalesce onto one dialog; coalesced calls
   * resolve with `coalesced: true`. Rejects with `NO_DEVICE` if the device detaches
   * (or the plugin is destroyed) while the request is pending.
   */
  requestPermission(options: DeviceRef): Promise<RequestPermissionResult>;
  hasPermission(options: DeviceRef): Promise<PermissionResult>;

  // Port lifecycle
  open(options: OpenOptions): Promise<OpenResult>;
  close(options: PortRef): Promise<void>;
  isOpen(options: PortRef): Promise<IsOpenResult>;
  getPortInfo(options: PortRef): Promise<PortInfo>;

  // Raw USB bulk interfaces (independent of usb-serial-for-android drivers)
  openBulk(options: OpenBulkOptions): Promise<OpenBulkResult>;
  closeBulk(options: BulkRef): Promise<void>;
  isBulkOpen(options: BulkRef): Promise<IsOpenResult>;
  getBulkInfo(options: BulkRef): Promise<BulkInfo>;
  bulkRead(options: BulkReadOptions): Promise<ReadResult>;
  bulkWrite(options: BulkWriteOptions): Promise<WriteResult>;
  startBulkReading(options: StartBulkReadingOptions): Promise<void>;
  stopBulkReading(options: BulkRef): Promise<void>;
  getBulkStreamState(options: BulkRef): Promise<StreamStateResult>;

  // Parameters
  setParameters(options: SerialParameters): Promise<void>;

  // I/O
  read(options: ReadOptions): Promise<ReadResult>;
  /**
   * While a stream is RUNNING on the port, the bytes are enqueued onto the stream's
   * async write queue and `bytesWritten` confirms acceptance, not transmission. With
   * no running stream the write is synchronous and `bytesWritten` reflects delivery
   * to the driver.
   */
  write(options: WriteOptions): Promise<WriteResult>;
  /**
   * Fire-and-forget write: resolves on acceptance, never confirms transmission.
   * Uses the stream's queue while one is RUNNING, the port executor otherwise.
   */
  writeAsync(options: WriteAsyncOptions): Promise<void>;

  // Streaming
  startReading(options: StartReadingOptions): Promise<void>;
  stopReading(options: PortRef): Promise<void>;
  getStreamState(options: PortRef): Promise<StreamStateResult>;
  getStreamConfig(options: PortRef): Promise<StreamConfig>;

  // Control lines
  getControlLines(options: PortRef): Promise<ControlLinesState>;
  getSupportedControlLines(options: PortRef): Promise<SupportedControlLines>;
  getCD(options: PortRef): Promise<BooleanResult>;
  getCTS(options: PortRef): Promise<BooleanResult>;
  getDSR(options: PortRef): Promise<BooleanResult>;
  getDTR(options: PortRef): Promise<BooleanResult>;
  getRI(options: PortRef): Promise<BooleanResult>;
  getRTS(options: PortRef): Promise<BooleanResult>;
  setDTR(options: SetLineOptions): Promise<void>;
  setRTS(options: SetLineOptions): Promise<void>;

  // Flow control
  setFlowControl(options: SetFlowControlOptions): Promise<void>;
  getFlowControl(options: PortRef): Promise<FlowControlResult>;
  getSupportedFlowControl(options: PortRef): Promise<SupportedFlowControlResult>;
  getXON(options: PortRef): Promise<BooleanResult>;

  // Maintenance
  purgeHwBuffers(options: PurgeHwBuffersOptions): Promise<void>;
  setBreak(options: SetLineOptions): Promise<void>;
  setReadQueue(options: SetReadQueueOptions): Promise<void>;
  getReadQueueConfig(options: PortRef): Promise<ReadQueueConfig>;

  // Events
  addListener(
    eventName: 'bulkData',
    listenerFunc: (event: BulkDataEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'bulkError',
    listenerFunc: (event: BulkErrorEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'data',
    listenerFunc: (event: DataEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'error',
    listenerFunc: (event: ErrorEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'attached',
    listenerFunc: (event: AttachedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'detached',
    listenerFunc: (event: DetachedEvent) => void,
  ): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}
