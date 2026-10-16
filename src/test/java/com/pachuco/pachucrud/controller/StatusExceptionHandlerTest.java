package com.pachuco.pachucrud.controller;

import com.pachuco.pachucrud.exception.NotFoundException;
import io.grpc.Status;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusExceptionHandlerTest {

    private final StatusExceptionHandler handler = new StatusExceptionHandler();

    private Status.Code codeFor(Throwable t) {
        return handler.handleException(t).getStatus().getCode();
    }

    private static DataIntegrityViolationException integrityViolation(ConstraintKind kind) {
        var cause = new ConstraintViolationException("violation", new SQLException(), "sql", kind, "c");
        return new DataIntegrityViolationException("could not execute statement", cause);
    }

    @Test
    void mapsDomainExceptions() {
        assertEquals(Status.Code.NOT_FOUND, codeFor(new NotFoundException("no user")));
        assertEquals(Status.Code.INVALID_ARGUMENT, codeFor(new IllegalArgumentException("bad")));
        assertEquals(Status.Code.FAILED_PRECONDITION, codeFor(new IllegalStateException("wrong stage")));
        assertEquals(Status.Code.UNIMPLEMENTED, codeFor(new UnsupportedOperationException("off")));
    }

    @Test
    void uniqueViolationIsAlreadyExists() {
        assertEquals(Status.Code.ALREADY_EXISTS, codeFor(integrityViolation(ConstraintKind.UNIQUE)));
    }

    @Test
    void checkViolationIsInternalNotCallerError() {
        assertEquals(Status.Code.INTERNAL, codeFor(integrityViolation(ConstraintKind.CHECK)));
    }

    @Test
    void unknownExceptionIsInternalWithMessage() {
        var status = handler.handleException(new RuntimeException("boom")).getStatus();

        assertEquals(Status.Code.INTERNAL, status.getCode());
        assertEquals("boom", status.getDescription());
    }

    @Test
    void passesThroughExplicitStatuses() {
        assertEquals(Status.Code.PERMISSION_DENIED,
            codeFor(Status.PERMISSION_DENIED.asRuntimeException()));
    }
}
