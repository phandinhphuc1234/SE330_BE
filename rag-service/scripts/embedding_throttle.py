"""Local estimated-token admission control for offline embedding experiments.

UTF-8 bytes +32 per input are deliberately conservative reservation units,
NOT exact Gemini tokens or billing usage. No countTokens API, tokenizer model
download, credentials or shared-project quota lookup is performed here.
"""

import asyncio
from collections import deque
import math
import sys
import time


ESTIMATOR = "utf8_bytes_plus32_per_input_v1"
WINDOW_SECONDS = 61.0
WINDOW_UNITS = 20000
WINDOW_REQUESTS = 60
DOCUMENT_BATCH_SIZE = 8
DOCUMENT_BATCH_UNITS = 10000


def estimate_units(text):
    # 1. Count the exact provider-facing text, including task/title context.
    # Avoid the English chars/4 heuristic for Vietnamese; use a larger local
    # estimate, but never claim it is the model's measured token count.
    if not isinstance(text, str) or not text.strip():
        raise ValueError("Require nonblank embedding text for token reservation.")
    return len(text.encode("utf-8")) + 32


def document_batches(texts):
    # 2. Bound BOTH input count and estimated request size. Preserve text/order;
    # every returned batch is cached independently if the provider succeeds.
    batch, units = [], 0
    for text in texts:
        cost = estimate_units(text)
        if cost > DOCUMENT_BATCH_UNITS:
            raise ValueError("One embedding input exceeds the safe batch reservation.")
        if batch and (len(batch) == DOCUMENT_BATCH_SIZE or units + cost > DOCUMENT_BATCH_UNITS):
            yield batch
            batch, units = [], 0
        batch.append(text)
        units += cost
    if batch:
        yield batch


class TokenWindowLimiter:
    """Serialize weighted reservations in a rolling 61-second local window.

    Failed requests keep their reservation. Wait in chunks <=30 seconds; no
    retries are generated. Clock/sleep injection permits tests without delays.
    Other clients in the same Google project remain outside this local budget.
    """

    def __init__(self, *, units=WINDOW_UNITS, requests=WINDOW_REQUESTS,
                 window=WINDOW_SECONDS, clock=time.monotonic, sleep=asyncio.sleep,
                 log_wait=True):
        if (type(units) is not int or units <= 0 or type(requests) is not int or requests <= 0
                or isinstance(window, bool) or not math.isfinite(window) or window <= 0):
            raise ValueError("Invalid token-window limits.")
        self.units, self.requests, self.window = units, requests, window
        self.clock, self.sleep, self.log_wait = clock, sleep, log_wait
        self.events = deque()
        self.lock = asyncio.Lock()
        self.total_units = 0
        self.max_window_units = 0
        self.max_window_requests = 0
        self.wait_count = 0
        self.wait_seconds = 0.0

    async def acquire(self, cost):
        # 3. Admission happens before network I/O. Do not release units on a
        # failed API response: it may still have consumed provider quota.
        if type(cost) is not int or not 0 < cost <= self.units:
            raise ValueError("Request exceeds the estimated token-window budget.")
        async with self.lock:
            while True:
                now = self.clock()
                while self.events and now - self.events[0][0] >= self.window:
                    self.events.popleft()
                used = sum(value for _, value in self.events)
                if used + cost <= self.units and len(self.events) < self.requests:
                    self.events.append((now, cost))
                    self.total_units += cost
                    self.max_window_units = max(self.max_window_units, used + cost)
                    self.max_window_requests = max(self.max_window_requests, len(self.events))
                    return
                delay = min(30.0, max(.01, self.events[0][0] + self.window - now))
                self.wait_count += 1
                self.wait_seconds += delay
                if self.log_wait:
                    print(f"Embedding local token throttle: wait {delay:.2f}s; "
                          f"reserved units={used}/{self.units}; no API call during wait.",
                          file=sys.stderr, flush=True)
                await self.sleep(delay)

    def report(self):
        # 4. Public counters only. No source/query text or API key serialization.
        return {"estimator": ESTIMATOR, "exactGeminiTokenCount": False,
                "sharedProjectTrafficTracked": False, "windowSeconds": self.window,
                "reservationUnitsPerWindow": self.units, "requestsPerWindow": self.requests,
                "totalReservedUnits": self.total_units, "maxWindowReservedUnits": self.max_window_units,
                "maxWindowRequests": self.max_window_requests, "waitCount": self.wait_count,
                "scheduledWaitSeconds": round(self.wait_seconds, 3),
                "maxDocumentBatchInputs": DOCUMENT_BATCH_SIZE,
                "maxDocumentBatchReservedUnits": DOCUMENT_BATCH_UNITS}


# Flow: exact text -> conservative local reservation -> bounded batches ->
# rolling-window admission -> provider may run -> numeric-only diagnostics.
# Purpose: reduce TPM/RPM pressure without billing changes, extra API calls,
# misleading exact-token claims or retrying failed embedding submissions.
