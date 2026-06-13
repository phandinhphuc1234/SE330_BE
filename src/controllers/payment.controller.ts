import { Request, Response } from "express";
import { fail, ok } from "../types/api.type";
import { ConfirmPaymentRequest, CreatePaymentRequest, PaymentStatus } from "../types/payment.type";
import {
  cancelPaymentOrder,
  confirmPaymentOrder,
  createPaymentOrder,
  getPaymentOrder,
  listPaymentOrders,
  PaymentErrors,
} from "../services/payment.service";

const VALID_PURPOSES = ["EBOOK_RENEWAL", "EBOOK_LOAN_FEE", "FINE"];
const VALID_METHODS = ["CARD", "WALLET", "BANK_TRANSFER"];

export function createPayment(req: Request, res: Response) {
  const userId = req.user!.id;
  const body = req.body as CreatePaymentRequest;

  if (!body || !VALID_PURPOSES.includes(body.purpose)) {
    return res.status(400).json(fail("Field 'purpose' is required and must be one of: " + VALID_PURPOSES.join(", "), "VALIDATION_ERROR"));
  }

  if (body.purpose === "FINE" && !(typeof body.amount === "number" && body.amount > 0)) {
    return res.status(400).json(fail("Field 'amount' is required and must be > 0 for purpose FINE.", "VALIDATION_ERROR"));
  }

  try {
    const order = createPaymentOrder(userId, body);
    return res.status(201).json(ok(order, "Payment order created."));
  } catch (err) {
    return handleServiceError(res, err);
  }
}

export function getPayment(req: Request, res: Response) {
  const userId = req.user!.id;
  const { paymentId } = req.params;

  try {
    const order = getPaymentOrder(paymentId, userId);
    return res.json(ok(order, "Payment order fetched."));
  } catch (err) {
    return handleServiceError(res, err);
  }
}

export function confirmPayment(req: Request, res: Response) {
  const userId = req.user!.id;
  const { paymentId } = req.params;
  const body = req.body as ConfirmPaymentRequest;

  if (!body || !VALID_METHODS.includes(body.method)) {
    return res.status(400).json(fail("Field 'method' is required and must be one of: " + VALID_METHODS.join(", "), "VALIDATION_ERROR"));
  }

  try {
    const order = confirmPaymentOrder(paymentId, userId, body);

    if (order.status === "FAILED") {
      return res.status(402).json(
        Object.assign(ok(order, "Payment failed."), { success: false, message: order.failureReason ?? "Payment failed.", code: "PAYMENT_DECLINED" }),
      );
    }

    return res.json(ok(order, order.status === "PAID" ? "Payment completed successfully." : "Payment processed."));
  } catch (err) {
    return handleServiceError(res, err);
  }
}

export function cancelPayment(req: Request, res: Response) {
  const userId = req.user!.id;
  const { paymentId } = req.params;

  try {
    const order = cancelPaymentOrder(paymentId, userId);
    return res.json(ok(order, "Payment order cancelled."));
  } catch (err) {
    return handleServiceError(res, err);
  }
}

export function listPayments(req: Request, res: Response) {
  const userId = req.user!.id;
  const status = req.query.status as PaymentStatus | undefined;

  const items = listPaymentOrders(userId, status);
  return res.json(ok(items, "Payment orders fetched."));
}

function handleServiceError(res: Response, err: unknown) {
  if (err instanceof PaymentErrors.PaymentNotFoundError) {
    return res.status(404).json(fail(err.message, "PAYMENT_NOT_FOUND"));
  }
  if (err instanceof PaymentErrors.PaymentStateError) {
    return res.status(409).json(fail(err.message, "PAYMENT_INVALID_STATE"));
  }

  console.error(err);
  return res.status(500).json(fail("Internal server error.", "INTERNAL_ERROR"));
}
