package com.flummidill.dc_whitelist_linker;


import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class DatabaseWorker {

    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r);
        thread.setName("WhitelistLinker-DB");
        return thread;
    });

    public ExecutorService getDatabaseExecutor() {
        return databaseExecutor;
    }

    public void shutdown() {
        databaseExecutor.shutdown();
    }
}