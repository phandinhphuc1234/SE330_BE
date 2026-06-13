/**
 * Shared response envelope — MUST mirror `ApiResponse<T>` in the frontend
 * (src/types/api.type.ts) so the FE service layer can parse responses
 * from this payment service without any changes.
 */
export type ApiResponse<T> = {
  success: boolean;
  message: string;
  code?: string;
  data?: T;
  meta?: unknown;
  timestamp: string;
  traceId?: string;
};

export function ok<T>(data: T, message = "OK", meta?: unknown): ApiResponse<T> {
  return {
    success: true,
    message,
    data,
    meta,
    timestamp: new Date().toISOString(),
  };
}

export function fail(message: string, code?: string, traceId?: string): ApiResponse<never> {
  return {
    success: false,
    message,
    code,
    timestamp: new Date().toISOString(),
    traceId,
  };
}
