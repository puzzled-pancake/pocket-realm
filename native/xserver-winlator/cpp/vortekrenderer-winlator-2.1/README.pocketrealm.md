# Winlator Vortek 2.1 system-driver baseline

`include/` and `src/` are a source-matched import of
`app/src/main/cpp/vortekrenderer` from `brunodev85/winlator-app` commit
`ca3d735a60d653a787daf16d14fafef28d9c2c23` (LGPL-2.1).

The Android build deliberately does not compile the imported `src/main.c`.
That file supports Winlator's optional AdrenoTools loader. Pocket Realm instead
compiles `../src/vortek_system_main.c`, whose only loader target is Android's
public `libvulkan.so`. All guest/server protocol, serializer, request-handler,
Vulkan-object and X-window swapchain behaviour remains source-matched.

Pocket Realm carries only behaviour-transparent host-side maintenance in this
tree: bounded/exact extra-data reads and partial-init cleanup in `vk_context`,
null/partial-allocation cleanup in `xwindow_swapchain`, the Vulkan-required
all-bits memory-property match, and bounded per-context memory-route diagnostics.
It deliberately does not add opaque handle translation or stricter generated
decoding because the pinned guest ICD depends on Winlator 2.1's original wire
semantics.

The app owns every created native context in an exact-once process registry in
addition to Winlator's connection tag. Component close drains residual contexts
before UI teardown, and the server rings monitor the guest control socket with
a progressive wait capped at 2 ms. A dead guest therefore terminates its request
worker even if the Java connection callback is missed; it cannot poll at the
upstream permanent 100 microsecond interval during a later Turnip session.

The matching guest is built from `brunodev85/Vortek` commit
`ab7329c4b445a4abd9b9af91b8148e1ca41464fa` by
`tools/build_vortek_guest.py`. The only guest adaptations are the app-private
socket/RUNPATH and a 32-event process-scoped recvmsg/fstat/mmap diagnostic
envelope. `tools/stage_renderer_packages.py` fails closed unless that reviewed
guest size/SHA and its provenance match. Do not change the native wire protocol
independently of that guest payload.
