package com.ayna.warehouse.shell;

public interface ScannerDevice {
    void open();

    void close();

    boolean isAlive();

    void recover();
}