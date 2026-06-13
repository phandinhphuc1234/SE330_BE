import { Router } from "express";
import { requireAuth } from "../middlewares/auth.middleware";
import {
  cancelPayment,
  confirmPayment,
  createPayment,
  getPayment,
  listPayments,
} from "../controllers/payment.controller";

const router = Router();

// All payment endpoints require an authenticated user (Bearer access token).
router.use(requireAuth);

// POST   /api/payments               -> create a payment order (e.g. renew ebook loan fee)
// GET    /api/payments                -> list current user's payment orders
// GET    /api/payments/:paymentId     -> get a single payment order
// POST   /api/payments/:paymentId/confirm -> submit payment method & process payment
// POST   /api/payments/:paymentId/cancel  -> cancel a pending payment
router.post("/", createPayment);
router.get("/", listPayments);
router.get("/:paymentId", getPayment);
router.post("/:paymentId/confirm", confirmPayment);
router.post("/:paymentId/cancel", cancelPayment);

export default router;
