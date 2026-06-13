import { NextFunction, Request, Response } from "express";
import jwt from "jsonwebtoken";
import { config } from "../config/env";
import { fail } from "../types/api.type";

export type AuthenticatedUser = {
  id: number;
  email?: string;
  role?: string;
  [key: string]: unknown;
};

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Express {
    interface Request {
      user?: AuthenticatedUser;
    }
  }
}

/** Mock user used in development when no real auth backend is available. */
const DEV_MOCK_USER: AuthenticatedUser = {
  id: 1,
  email: "dev@example.com",
  role: "MEMBER",
};

/**
 * Verifies the `Authorization: Bearer <token>` header using the same
 * JWT secret as the main Athenaeum/SE330 API, so the access token issued
 * at login can be reused on this payment service.
 *
 * In development (NODE_ENV !== "production") this middleware is lenient
 * so the payment flow can be exercised standalone, without the main
 * auth backend running:
 *  - No Authorization header at all -> falls back to a mock user.
 *  - Token present but invalid/unsigned -> decoded without signature
 *    check; if it has a `sub`/`id`/`userId` claim, that is used,
 *    otherwise falls back to the mock user.
 *
 * In production, a valid signed token is always required.
 */
export function requireAuth(req: Request, res: Response, next: NextFunction) {
  const header = req.headers.authorization;

  if (!header || !header.startsWith("Bearer ")) {
    if (config.nodeEnv !== "production") {
      req.user = DEV_MOCK_USER;
      return next();
    }
    return res.status(401).json(fail("Missing or invalid Authorization header.", "UNAUTHORIZED"));
  }

  const token = header.slice("Bearer ".length).trim();

  try {
    const payload = jwt.verify(token, config.jwtSecret) as jwt.JwtPayload;
    req.user = normalizeUser(payload);
    return next();
  } catch (err) {
    if (config.nodeEnv !== "production") {
      const decoded = jwt.decode(token) as jwt.JwtPayload | null;
      if (decoded && (decoded.sub || decoded.id || decoded.userId)) {
        req.user = normalizeUser(decoded);
        return next();
      }
      req.user = DEV_MOCK_USER;
      return next();
    }

    return res.status(401).json(fail("Invalid or expired token.", "UNAUTHORIZED"));
  }
}

function normalizeUser(payload: jwt.JwtPayload): AuthenticatedUser {
  const rawId = payload.sub ?? payload.id ?? payload.userId ?? payload.uid;
  const id = typeof rawId === "string" ? Number(rawId) : (rawId as number) ?? 0;

  return {
    id,
    email: (payload.email as string) ?? undefined,
    role: (payload.role as string) ?? undefined,
    ...payload,
  };
}
