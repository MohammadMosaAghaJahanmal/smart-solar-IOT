package com.virtualcamerapro.ipc;

/** Private, same-UID setup endpoint. Long operations return a progress snapshot. */
interface IGuestSetup {
    String getSnapshot();
    String execute(String action, boolean approved);
}
