/** Non-secret view of a BroadWorks connection as returned by the portal API. */
export interface ConnectionResponse {
  resourceId: string;
  displayName: string;
  hostname: string;
  port: number;
  loginType: string;
  username: string;
  usePrivateApplicationServerAddress: boolean;
  needsPassword: boolean;
}

/** Create/update payload. Password is optional: blank/absent leaves the stored secret unchanged. */
export interface ConnectionRequest {
  displayName: string;
  hostname: string;
  port: number;
  username: string;
  loginType?: string;
  usePrivateApplicationServerAddress: boolean;
  password?: string;
}
