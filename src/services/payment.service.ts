import { v4 as uuidv4 } from "uuid";
import {
  ConfirmPaymentRequest,
  CreatePaymentRequest,
  PaymentLineItem,
  PaymentOrder,
  PaymentPurpose,
  PaymentStatus,
} from "../types/payment.type";
import { config } from "../config/env";

const PAYMENT_TTL_MS = 15 * 60 * 1000; // 15 minutes to complete a payment
const DEFAULT_CURRENCY = "VND";

/** Base fee schedule (VND) by purpose. Replace with real pricing rules as needed. */
const PRICE_TABLE: Record<PaymentPurpose, number> = {
  EBOOK_RENEWAL: 10000, // fee to renew an ebook loan for 14 more days
  EBOOK_LOAN_FEE: 20000, // fee to borrow an ebook online
  FINE: 0, // FINE amount must be supplied explicitly (amount comes from circulation system)
};

const PURPOSE_LABEL: Record<PaymentPurpose, string> = {
  EBOOK_RENEWAL: "Ebook loan renewal fee (+14 days)",
  EBOOK_LOAN_FEE: "Ebook borrowing fee",
  FINE: "Library fine",
};

class PaymentNotFoundError extends Error {
  constructor(id: string) {
    super(`Payment order ${id} was not found.`);
    this.name = "PaymentNotFoundError";
  }
}

class PaymentStateError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PaymentStateError";
  }
}

// ─────────────────────────────────────────────
// In-memory store (replace with DB persistence for production)
// ─────────────────────────────────────────────
const store = new Map<string, PaymentOrder>();

function buildLineItems(purpose: PaymentPurpose, amount: number): PaymentLineItem[] {
  return [{ label: PURPOSE_LABEL[purpose], amount }];
}

function resolveAmount(purpose: PaymentPurpose, requested?: number): number {
  if (typeof requested === "number" && requested > 0) return requested;
  const base = PRICE_TABLE[purpose];
  if (!base) {
    throw new PaymentStateError(`Amount is required for purpose "${purpose}".`);
  }
  return base;
}

export function createPaymentOrder(userId: number, payload: CreatePaymentRequest): PaymentOrder {
  const amount = resolveAmount(payload.purpose, payload.amount);
  const now = new Date();
  const order: PaymentOrder = {
    paymentId: uuidv4(),
    userId,
    purpose: payload.purpose,
    loanId: payload.loanId ?? null,
    bookId: payload.bookId ?? null,
    bookTitle: payload.bookTitle ?? null,
    currency: payload.currency ?? DEFAULT_CURRENCY,
    amount,
    items: buildLineItems(payload.purpose, amount),
    status: "PENDING",
    method: null,
    createdAt: now.toISOString(),
    updatedAt: now.toISOString(),
    expiresAt: new Date(now.getTime() + PAYMENT_TTL_MS).toISOString(),
    paidAt: null,
    resultData: null,
    failureReason: null,
  };

  store.set(order.paymentId, order);
  return order;
}

export function getPaymentOrder(paymentId: string, userId: number): PaymentOrder {
  const order = store.get(paymentId);
  if (!order || order.userId !== userId) {
    throw new PaymentNotFoundError(paymentId);
  }

  maybeExpire(order);
  return order;
}

function maybeExpire(order: PaymentOrder) {
  if (order.status === "PENDING" && new Date(order.expiresAt).getTime() < Date.now()) {
    order.status = "EXPIRED";
    order.updatedAt = new Date().toISOString();
  }
}

/**
 * Simulates calling out to a payment gateway and settling the order.
 * Always succeeds unless PAYMENT_GATEWAY_SUCCESS_RATE < 1 (for testing failure flows)
 * or the order has already expired / been processed.
 */
export function confirmPaymentOrder(
  paymentId: string,
  userId: number,
  payload: ConfirmPaymentRequest,
): PaymentOrder {
  const order = getPaymentOrder(paymentId, userId);

  if (order.status === "PAID") {
    return order; // idempotent
  }

  if (order.status !== "PENDING") {
    throw new PaymentStateError(`Cannot confirm a payment in status "${order.status}".`);
  }

  const succeeds = Math.random() < clamp01(config.paymentGatewaySuccessRate);
  const now = new Date();

  order.method = payload.method;
  order.updatedAt = now.toISOString();

  if (!succeeds) {
    order.status = "FAILED";
    order.failureReason = "The payment gateway declined the transaction. Please try again.";
    return order;
  }

  order.status = "PAID";
  order.paidAt = now.toISOString();
  order.resultData = buildResultData(order);
  return order;
}

export function cancelPaymentOrder(paymentId: string, userId: number): PaymentOrder {
  const order = getPaymentOrder(paymentId, userId);

  if (order.status === "PAID") {
    throw new PaymentStateError("A completed payment cannot be cancelled.");
  }

  order.status = "CANCELLED";
  order.updatedAt = new Date().toISOString();
  return order;
}

export function listPaymentOrders(userId: number, status?: PaymentStatus): PaymentOrder[] {
  return Array.from(store.values())
    .filter((o) => o.userId === userId && (!status || o.status === status))
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
}

function buildResultData(order: PaymentOrder): Record<string, unknown> {
  switch (order.purpose) {
    case "EBOOK_RENEWAL": {
      const newExpiry = new Date(Date.now() + 14 * 24 * 60 * 60 * 1000);
      return {
        loanId: order.loanId,
        renewedUntil: newExpiry.toISOString(),
        message: "Ebook loan renewed for 14 more days.",
      };
    }
    case "EBOOK_LOAN_FEE":
      return {
        bookId: order.bookId,
        message: "Ebook borrowing fee paid. You may now access the ebook.",
      };
    case "FINE":
      return {
        message: "Fine settled.",
      };
    default:
      return {};
  }
}

function clamp01(n: number): number {
  if (Number.isNaN(n)) return 1;
  return Math.min(1, Math.max(0, n));
}

export const PaymentErrors = { PaymentNotFoundError, PaymentStateError };
