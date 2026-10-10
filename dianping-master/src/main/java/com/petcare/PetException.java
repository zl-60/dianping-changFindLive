package com.petcare;

public class PetException extends RuntimeException {
    final int status;
    public PetException(String message) { this(400, message); }
    public PetException(int status, String message) { super(message); this.status = status; }
}
