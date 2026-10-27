package com.showticket.utils;

public interface ILock {

    boolean tryLock(long timeoutSec);
    void unlock();

}
