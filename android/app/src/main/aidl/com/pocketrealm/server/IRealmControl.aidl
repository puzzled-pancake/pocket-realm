package com.pocketrealm.server;

/** Fixed app-private O09 control surface for the isolated :realm process. */
interface IRealmControl {
    String status();
    String start();
    String stop();
    String killForTest();
}
