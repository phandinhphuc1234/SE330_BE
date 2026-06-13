import dotenv from "dotenv";

dotenv.config();

export const config = {
  port: Number(process.env.PORT ?? 8081),
  nodeEnv: process.env.NODE_ENV ?? "development",
  frontendUrl: process.env.FRONTEND_URL ?? "http://localhost:3000",
  jwtSecret: process.env.JWT_SECRET ?? "change-me-shared-secret",
  libraryApiUrl: process.env.LIBRARY_API_URL ?? "http://localhost:8080",
  paymentGatewaySuccessRate: Number(process.env.PAYMENT_GATEWAY_SUCCESS_RATE ?? 1),
  paymentReturnBaseUrl: process.env.PAYMENT_RETURN_BASE_URL ?? "http://localhost:3000/payment",
};
