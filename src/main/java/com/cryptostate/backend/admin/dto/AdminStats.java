package com.cryptostate.backend.admin.dto;

public record AdminStats(
    long totalUsers,
    long freeUsers,
    long proUsers,
    long activeUsers,
    long totalTransactions,
    long totalSyncs
) {}
