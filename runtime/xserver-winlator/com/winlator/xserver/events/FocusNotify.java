package com.winlator.xserver.events;

import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Window;

import java.io.IOException;

/** Core X11 FocusIn/FocusOut event used to synchronize Wine's Win32
 * foreground window with the embedded Java window manager. */
public final class FocusNotify extends Event {
    public static final int FOCUS_IN = 9;
    public static final int FOCUS_OUT = 10;
    private static final byte NOTIFY_NONLINEAR = 3;
    private static final byte NOTIFY_NORMAL = 0;

    private final Window event;

    public FocusNotify(boolean focused, Window event) {
        super(focused ? FOCUS_IN : FOCUS_OUT);
        this.event = event;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte(NOTIFY_NONLINEAR);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(event.id);
            outputStream.writeByte(NOTIFY_NORMAL);
            outputStream.writePad(23);
        }
    }
}
