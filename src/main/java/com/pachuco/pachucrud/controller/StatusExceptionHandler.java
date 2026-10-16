package com.pachuco.pachucrud.controller;

import com.pachuco.pachucrud.exception.NotFoundException;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.stereotype.Component;

/**
 * The one place exceptions thrown by gRPC controllers become statuses. Controllers
 * don't catch: transactions commit or roll back inside the services first, so the
 * call is closed exactly once with the real cause.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class StatusExceptionHandler implements GrpcExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(StatusExceptionHandler.class);

    @Override
    public StatusException handleException(Throwable exception) {
        if (exception instanceof StatusException e) {
            return e;
        }
        if (exception instanceof StatusRuntimeException e) {
            return e.getStatus().asException(e.getTrailers());
        }

        Status status = statusFor(exception);
        if (status == null) {
            log.error("Unhandled exception in gRPC call", exception);
            return Status.INTERNAL.withDescription(exception.getMessage())
                .withCause(exception).asException();
        }
        log.warn("gRPC call failed with {}: {}", status.getCode(), exception.getMessage());
        return status.withDescription(exception.getMessage()).asException();
    }

    private static Status statusFor(Throwable exception) {
        if (exception instanceof NotFoundException) return Status.NOT_FOUND;
        if (exception instanceof IllegalArgumentException) return Status.INVALID_ARGUMENT;
        if (exception instanceof IllegalStateException) return Status.FAILED_PRECONDITION;
        if (exception instanceof UnsupportedOperationException) return Status.UNIMPLEMENTED;
        if (isUniqueViolation(exception)) return Status.ALREADY_EXISTS;
        // Any other integrity violation (e.g. a check constraint) is a schema bug, not
        // the caller's fault, so it falls through to INTERNAL with its stack logged.
        return null;
    }

    private static boolean isUniqueViolation(Throwable exception) {
        if (exception instanceof DuplicateKeyException) return true;
        if (!(exception instanceof DataIntegrityViolationException)) return false;
        for (Throwable t = exception; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && cve.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE) {
                return true;
            }
        }
        return false;
    }
}
