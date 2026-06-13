import express from "express";
import cors from "cors";
import { config } from "./config/env";
import { ok } from "./types/api.type";
import paymentRoutes from "./routes/payment.routes";

export const app = express();

app.use(
  cors({
    origin: config.frontendUrl === "*" ? true : config.frontendUrl.split(",").map((o) => o.trim()),
    credentials: true,
  }),
);
app.use(express.json());

app.get("/api/health", (_req, res) => {
  res.json(ok({ status: "UP", service: "se330-payment-be" }, "Payment service is healthy."));
});

app.use("/api/payments", paymentRoutes);

// 404 handler
app.use((req, res) => {
  res.status(404).json({
    success: false,
    message: `Route not found: ${req.method} ${req.originalUrl}`,
    code: "NOT_FOUND",
    timestamp: new Date().toISOString(),
  });
});
