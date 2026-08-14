/** Non-secret view of a BroadWorks connection as returned by the portal API. */
export interface ConnectionResponse {
  resourceId: string;
  displayName: string;
  hostname: string;
  port: number;
  username: string;
  needsPassword: boolean;
}

/** Create/update payload. Password is optional: blank/absent leaves the stored secret unchanged. */
export interface ConnectionRequest {
  displayName: string;
  hostname: string;
  port: number;
  username: string;
  password?: string;
}

/**
 * Payload for testing a connection without saving it. When the password is absent/blank the stored
 * secret of the connection identified by `resourceId` is used (for verifying a saved connection or
 * one whose host/port/username is being edited).
 */
export interface VerifyConnectionRequest {
  hostname: string;
  port: number;
  username: string;
  password?: string;
  resourceId?: string;
}

/** Outcome of a connection verification. A failed login is `success: false` with a safe message. */
export interface VerifyResult {
  success: boolean;
  message: string;
}
