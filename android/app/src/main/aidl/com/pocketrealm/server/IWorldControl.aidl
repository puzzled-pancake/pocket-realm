package com.pocketrealm.server;

/** Fixed app-private O09 control surface for the isolated :world process. */
interface IWorldControl {
    String status();
    String start();
    String createAccount(String username, String password);
    String save();
    String stop();
    String killForTest();
}
