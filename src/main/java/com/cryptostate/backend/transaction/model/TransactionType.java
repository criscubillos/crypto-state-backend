package com.cryptostate.backend.transaction.model;

public enum TransactionType {
    SPOT_BUY,
    SPOT_SELL,
    FUTURES_LONG,
    FUTURES_SHORT,
    OPTIONS,
    EARN,
    LIQUIDITY_POOL_ADD,
    LIQUIDITY_POOL_REMOVE,
    TRANSFER_IN,
    TRANSFER_OUT,
    FEE,
    OTHER
}
