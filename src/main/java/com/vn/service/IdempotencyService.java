package com.vn.service;

import java.util.function.Supplier;

public interface IdempotencyService {
    // Generic type
    <T> T execute(Long actorId,
                  String httpMethod,
                  String normalizedPath,
                  String idempotencyKey,
                  Object requestBody,
                  Class<T> responseType,
                  Supplier<T> action);
}
