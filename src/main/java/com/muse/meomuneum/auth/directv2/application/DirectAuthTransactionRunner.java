package com.muse.meomuneum.auth.directv2.application;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DirectAuthTransactionRunner {
    private final TransactionTemplate transactions;

    public DirectAuthTransactionRunner(PlatformTransactionManager manager) {
        transactions = new TransactionTemplate(manager);
    }

    public <T> T run(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }
}
