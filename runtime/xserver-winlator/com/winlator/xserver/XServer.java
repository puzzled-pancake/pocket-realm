package com.winlator.xserver;

import android.os.Build;

import com.winlator.XServerDisplayActivity;
import com.winlator.contentdialog.DebugDialog;
import com.winlator.core.CursorLocker;
import com.winlator.renderer.GLRenderer;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.extensions.BigReqExtension;
import com.winlator.xserver.extensions.DRI3Extension;
import com.winlator.xserver.extensions.Extension;
import com.winlator.xserver.extensions.GLXExtension;
import com.winlator.xserver.extensions.PresentExtension;
import com.winlator.xserver.extensions.SyncExtension;
import com.winlator.xserver.extensions.XComposite;

import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.concurrent.locks.ReentrantLock;

public class XServer {
    public enum Lockable {WINDOW_MANAGER, PIXMAP_MANAGER, DRAWABLE_MANAGER, GRAPHIC_CONTEXT_MANAGER, INPUT_DEVICE, CURSOR_MANAGER, SHMSEGMENT_MANAGER}
    public static final short VERSION = 11;
    public static final String VENDOR_NAME = "Elbrus Technologies, LLC";
    public static final Charset LATIN1_CHARSET = Charset.forName("latin1");
    public final XServerDisplayActivity activity;
    private final Extension[] extensions;
    public final ScreenInfo screenInfo;
    public final PixmapManager pixmapManager;
    public final ResourceIDs resourceIDs = new ResourceIDs(128);
    public final GraphicsContextManager graphicsContextManager = new GraphicsContextManager();
    public final SelectionManager selectionManager;
    public final DrawableManager drawableManager;
    public final WindowManager windowManager;
    public final CursorManager cursorManager;
    public final Keyboard keyboard = Keyboard.createKeyboard(this);
    public final Pointer pointer = new Pointer(this);
    public final InputDeviceManager inputDeviceManager;
    public final GrabManager grabManager;
    public final CursorLocker cursorLocker;
    private SHMSegmentManager shmSegmentManager;
    private GLRenderer renderer;
    private WinHandler winHandler;
    private final EnumMap<Lockable, ReentrantLock> locks = new EnumMap<>(Lockable.class);
    private boolean relativeMouseMovement = false;
    private final boolean glxEnabled;

    public XServer(XServerDisplayActivity activity, ScreenInfo screenInfo) {
        this(activity, screenInfo,
            java.util.Arrays.asList(Build.SUPPORTED_ABIS).contains("x86_64"));
    }

    public XServer(XServerDisplayActivity activity, ScreenInfo screenInfo, boolean enableGlx) {
        this.activity = activity;
        this.screenInfo = screenInfo;
        this.glxEnabled = enableGlx;
        cursorLocker = new CursorLocker(this);
        for (Lockable lockable : Lockable.values()) locks.put(lockable, new ReentrantLock());

        pixmapManager = new PixmapManager();
        drawableManager = new DrawableManager(this);
        cursorManager = new CursorManager(drawableManager);
        windowManager = new WindowManager(screenInfo, drawableManager);
        selectionManager = new SelectionManager(windowManager);
        inputDeviceManager = new InputDeviceManager(this);
        grabManager = new GrabManager(this);

        DesktopHelper.attachTo(this);
        extensions = setupExtensions(enableGlx);
    }

    public boolean isGlxEnabled() {
        return glxEnabled;
    }

    public boolean isRelativeMouseMovement() {
        return relativeMouseMovement;
    }

    public void setRelativeMouseMovement(boolean relativeMouseMovement) {
        cursorLocker.setEnabled(!relativeMouseMovement);
        this.relativeMouseMovement = relativeMouseMovement;
    }

    public GLRenderer getRenderer() {
        return renderer;
    }

    public void setRenderer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public void setWinHandler(WinHandler winHandler) {
        this.winHandler = winHandler;
    }

    public SHMSegmentManager getSHMSegmentManager() {
        return shmSegmentManager;
    }

    public void setSHMSegmentManager(SHMSegmentManager shmSegmentManager) {
        this.shmSegmentManager = shmSegmentManager;
    }

    private class SingleXLock implements XLock {
        private final ReentrantLock lock;

        private SingleXLock(Lockable lockable) {
            this.lock = locks.get(lockable);
            lock.lock();
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }

    private class MultiXLock implements XLock {
        private final Lockable[] lockables;

        private MultiXLock(Lockable[] lockables) {
            this.lockables = lockables;
            for (Lockable lockable : lockables) locks.get(lockable).lock();
        }

        @Override
        public void close() {
            for (int i = lockables.length - 1; i >= 0; i--) {
                locks.get(lockables[i]).unlock();
            }
        }
    }

    public XLock lock(Lockable lockable) {
        return new SingleXLock(lockable);
    }

    public XLock lock(Lockable... lockables) {
        return new MultiXLock(lockables);
    }

    public XLock lockAll() {
        return new MultiXLock(Lockable.values());
    }

    public Extension getExtensionByName(String name) {
        for (Extension extension : extensions) if (extension.getName().equals(name)) return extension;
        return null;
    }

    public void injectPointerMove(int x, int y) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(x, y);
        }
    }

    public void injectPointerMoveDelta(int dx, int dy) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            // External relative sources (captured mouse and controller stick)
            // must not accumulate an invisible off-screen position. Without
            // this clamp, reversing at an edge spends many samples traversing
            // hidden coordinates and then appears to jump back onto the game.
            pointer.setPosition(
                clampInjectedPointerCoordinate(pointer.getX(), dx, screenInfo.width),
                clampInjectedPointerCoordinate(pointer.getY(), dy, screenInfo.height)
            );
        }
    }

    public static int clampInjectedPointerCoordinate(int current, int delta, int extent) {
        if (extent <= 0) return 0;
        long candidate = (long)current + (long)delta;
        return (int)Math.max(0L, Math.min((long)extent - 1L, candidate));
    }

    public void injectPointerButtonPress(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, true);
        }
    }

    public void injectPointerButtonRelease(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, false);
        }
    }

    public void injectKeyPress(XKeycode xKeycode) {
        injectKeyPress(xKeycode, 0);
    }

    public void injectKeyPress(XKeycode xKeycode, int keysym) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyPress(xKeycode.id, keysym);
        }
    }

    public void injectKeyRelease(XKeycode xKeycode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyRelease(xKeycode.id);
        }
    }

    private Extension[] setupExtensions(boolean enableGlx) {
        // DXVK keeps the DRI3/Present set with GLX disabled. Explicit Gladio
        // and Mesa VirGL selections enable the pinned GLX opcode; VirGL needs
        // its Fake-GLX negotiation bases even though frames travel over V0.
        // Still omitted:
        //   - MITSHMExtension : the separate SysV service component is not
        //     hosted by Pocket Realm. DRI3 uses the restored static fd mapper.
        // ARM Turnip/DXVK uses Winlator's source-matched DRI3/Present path.
        // libwinlator provides SCM_RIGHTS and native buffer mapping; keep the
        // original Winlator wire opcodes so the XCB Vulkan WSI sees them.
        // Advertising an extension with a no-op/absent implementation would
        // make Wine attempt GLX/SHM and fail at runtime; we do not advertise
        // what we do not implement.
        // Kept:
        //   - BigReq (always safe, pure protocol)
        //   - Sync  (pure-Java, counters only)
        //   - XComposite (window management references composited windows)
        //   - GLX on x86 only (native Gladio renderer, required by WineD3D)
        // Preserve Winlator's wire opcodes even when optional extensions are
        // omitted. In particular the matching Gladio client intentionally
        // sends GLX on -106; compacting this array had incorrectly moved GLX
        // to -103 and made the server index past the end of the array.
        final byte first = Extension.START_MAJOR_OPCODE;
        if (!enableGlx) {
            return new Extension[]{
                new BigReqExtension(this, first),
                new DRI3Extension(this, (byte)(first - 2)),
                new PresentExtension(this, (byte)(first - 3)),
                new SyncExtension(this, (byte)(first - 4)),
                new XComposite(this, (byte)(first - 5))
            };
        }
        return new Extension[]{
            new BigReqExtension(this, first),
            new DRI3Extension(this, (byte)(first - 2)),
            new PresentExtension(this, (byte)(first - 3)),
            new SyncExtension(this, (byte)(first - 4)),
            new XComposite(this, (byte)(first - 5)),
            new GLXExtension(this, (byte)(first - 6))
        };
    }

    public <T extends Extension> T getExtension(byte opcode) {
        for (Extension extension : extensions) {
            if (extension.getMajorOpcode() == opcode) return (T)extension;
        }
        return null;
    }

    public void debugPrint(String line) {
        DebugDialog debugDialog = activity.getDebugDialog();
        if (debugDialog != null) debugDialog.call("xserver:"+line);
    }
}
