package com.cryptostate.backend.auth.model;

public enum Plan {
    FREE,
    PRO;

    public boolean includes(Plan required) {
        return this.ordinal() >= required.ordinal();
    }
}
