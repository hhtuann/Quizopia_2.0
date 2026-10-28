package com.quizopia.identity.application.account;

/** Clean 400-mappable signal that a candidate display name failed validation. */
public class DisplayNameInvalidException extends RuntimeException {
    public DisplayNameInvalidException() {
        super("Display name is invalid");
    }
}
