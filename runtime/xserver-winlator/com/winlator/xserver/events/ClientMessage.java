package com.winlator.xserver.events;

import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Window;

import java.io.IOException;

/** Fixed 32-bit ClientMessage used for ICCCM WM_PROTOCOLS notifications. */
public final class ClientMessage extends Event {
    private final Window window;
    private final int type;
    private final int data0;
    private final int data1;

    public ClientMessage(Window window, int type, int data0, int data1) {
        super(33);
        this.window = window;
        this.type = type;
        this.data0 = data0;
        this.data1 = data1;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte)33); // ClientMessage
            outputStream.writeByte((byte)32); // data is five 32-bit values
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(window.id);
            outputStream.writeInt(type);
            outputStream.writeInt(data0);
            outputStream.writeInt(data1);
            outputStream.writePad(12);
        }
    }
}
