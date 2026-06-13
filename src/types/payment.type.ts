export type PaymentPurpose = "EBOOK_RENEWAL" | "EBOOK_LOAN_FEE" | "FINE";

export type PaymentStatus = "PENDING" | "PAID" | "FAILED" | "CANCELLED" | "EXPIRED";

export type PaymentMethod = "CARD" | "WALLET" | "BANK_TRANSFER";

/** A single line item shown on the payment summary. */
export type PaymentLineItem = {
  label: string;
  amount: number;
};

/**
 * A payment session/order created for the user to pay an ebook-related fee
 * (renewal fee, loan fee, fine, ...). This is the core record returned to
 * the frontend's standalone /payment tab.
 */
export type PaymentOrder = {
  paymentId: string;
  userId: number;
  purpose: PaymentPurpose;
  /** Related ebook loan, if any (e.g. the loan being renewed) */
  loanId?: number | null;
  bookId?: number | null;
  bookTitle?: string | null;
  currency: string;
  amount: number;
  items: PaymentLineItem[];
  status: PaymentStatus;
  method?: PaymentMethod | null;
  createdAt: string;
  updatedAt: string;
  expiresAt: string;
  paidAt?: string | null;
  /** Set once status === PAID, e.g. new expiry date after renewal */
  resultData?: Record<string, unknown> | null;
  failureReason?: string | null;
};

export type CreatePaymentRequest = {
  purpose: PaymentPurpose;
  loanId?: number;
  bookId?: number;
  bookTitle?: string;
  /** Optional override; if omitted, amount is derived server-side from `purpose` */
  amount?: number;
  currency?: string;
};

export type ConfirmPaymentRequest = {
  method: PaymentMethod;
  /** Mock card/wallet details — never stored, only used to simulate success/failure */
  cardNumber?: string;
  cardHolder?: string;
  expiry?: string;
  cvv?: string;
};
