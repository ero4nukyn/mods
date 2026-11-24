package vrsupport;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import necraft.xr.XrBackend;
import necraft.xr.XrFrame;

import org.lwjgl.PointerBuffer;
import org.lwjgl.openxr.*;
import org.lwjgl.opengl.WGL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.openxr.KHROpenGLEnable.*;
import static org.lwjgl.openxr.XR10.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * VR через OpenXR (LWJGL 3.3.4) с графикой OpenGL. Пока только Windows: окно игры отдаёт свой контекст (WGL) рантайму.
 *
 * <p>Порядок кадра: {@link #running()} разбирает события сессии, {@link #beginFrame()} ждёт кадр и читает позы и кнопки,
 * {@link #beginEye} / {@link #endEye} берут и возвращают картинку глаза, {@link #endFrame()} отдаёт кадр шлему.
 * Любая ошибка бросает исключение - игра ловит его и выключает VR, оставаясь в обычном окне.
 */
final class OpenXrBackend implements XrBackend {
    private static final long GL_SRGB8_ALPHA8 = 0x8C43L, GL_RGBA8 = 0x8058L;

    private final java.util.function.Consumer<String> log;

    private XrInstance instance;
    private XrSession session;
    private long systemId;
    private XrSpace appSpace, viewSpace;
    private final XrSpace[] gripSpace = new XrSpace[2], aimSpace = new XrSpace[2];
    private XrActionSet actionSet;
    private XrAction stickX, stickY, trigger, squeeze, primary, secondary, stickClick, menu, gripPose, aimPose, haptic;
    private final long[] handPath = new long[2];

    private final XrSwapchain[] swapchain = new XrSwapchain[2];
    private final int[][] fbos = new int[2][];
    private final int[] depth = new int[2];
    private final int[] width = new int[2], height = new int[2];
    private final double[][] eyePos = new double[2][3], eyeQuat = new double[2][4];
    private final double[][] eyeFov = new double[2][4];

    private boolean sessionRunning, frameOpen, shouldRender, lost;
    private int eyesDone;
    private long displayTime;
    private final XrFrame frame = new XrFrame();

    OpenXrBackend(java.util.function.Consumer<String> log) {
        this.log = log;
    }

    @Override
    public String name() {
        return "OpenXR";
    }

    private static void check(int r, String what) {
        if (r < 0) throw new IllegalStateException(what + " (код OpenXR " + r + ")");
    }

    @Override
    public boolean start(long window) {
        if (Platform.get() != Platform.WINDOWS) {
            log.accept("VR пока работает только в Windows");
            return false;
        }
        try {
            return init();
        } catch (Throwable t) {
            log.accept("не удалось запустить VR: " + t);
            stop();
            return false;
        }
    }

    private long path(String s) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            check(xrStringToPath(instance, s, p), "xrStringToPath " + s);
            return p.get(0);
        }
    }

    private boolean init() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer n = stack.mallocInt(1);
            check(xrEnumerateInstanceExtensionProperties((ByteBuffer) null, n, null), "список расширений");
            XrExtensionProperties.Buffer props = XrExtensionProperties.calloc(n.get(0), stack);
            for (XrExtensionProperties p : props) p.type$Default();
            check(xrEnumerateInstanceExtensionProperties((ByteBuffer) null, n, props), "список расширений");
            boolean gl = false;
            for (XrExtensionProperties p : props) {
                if (XR_KHR_OPENGL_ENABLE_EXTENSION_NAME.equals(p.extensionNameString())) gl = true;
            }
            if (!gl) {
                log.accept("рантайм OpenXR не поддерживает OpenGL (XR_KHR_opengl_enable)");
                return false;
            }

            PointerBuffer ext = stack.mallocPointer(1);
            ext.put(0, stack.UTF8(XR_KHR_OPENGL_ENABLE_EXTENSION_NAME));
            XrApplicationInfo app = XrApplicationInfo.calloc(stack);
            app.applicationName(stack.UTF8("Necraft"));
            app.applicationVersion(1);
            app.engineName(stack.UTF8("Necraft"));
            app.engineVersion(1);
            app.apiVersion(XR_CURRENT_API_VERSION);
            XrInstanceCreateInfo ci = XrInstanceCreateInfo.calloc(stack).type$Default().createFlags(0).applicationInfo(app).enabledExtensionNames(ext);
            PointerBuffer pp = stack.mallocPointer(1);
            check(xrCreateInstance(ci, pp), "xrCreateInstance");
            instance = new XrInstance(pp.get(0), ci);

            XrSystemGetInfo gi = XrSystemGetInfo.calloc(stack).type$Default().formFactor(XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY);
            LongBuffer sys = stack.mallocLong(1);
            int r = xrGetSystem(instance, gi, sys);
            if (r != XR_SUCCESS) {
                log.accept("шлем не найден или рантайм OpenXR не запущен (код " + r + ")");
                destroy();
                return false;
            }
            systemId = sys.get(0);

            XrGraphicsRequirementsOpenGLKHR req = XrGraphicsRequirementsOpenGLKHR.calloc(stack).type$Default();
            check(xrGetOpenGLGraphicsRequirementsKHR(instance, systemId, req), "требования к OpenGL");

            XrGraphicsBindingOpenGLWin32KHR gb = XrGraphicsBindingOpenGLWin32KHR.calloc(stack).type$Default()
                    .hDC(WGL.wglGetCurrentDC()).hGLRC(WGL.wglGetCurrentContext());
            XrSessionCreateInfo sci = XrSessionCreateInfo.calloc(stack).type$Default().next(gb.address()).createFlags(0).systemId(systemId);
            check(xrCreateSession(instance, sci, pp), "xrCreateSession");
            session = new XrSession(pp.get(0), instance);

            XrReferenceSpaceCreateInfo rs = XrReferenceSpaceCreateInfo.calloc(stack).type$Default().referenceSpaceType(XR_REFERENCE_SPACE_TYPE_LOCAL);
            rs.poseInReferenceSpace().orientation().w(1f);
            check(xrCreateReferenceSpace(session, rs, pp), "пространство LOCAL");
            appSpace = new XrSpace(pp.get(0), session);
            rs.referenceSpaceType(XR_REFERENCE_SPACE_TYPE_VIEW);
            check(xrCreateReferenceSpace(session, rs, pp), "пространство VIEW");
            viewSpace = new XrSpace(pp.get(0), session);

            makeSwapchains(stack);
            makeActions(stack);
        }
        log.accept("VR готов: " + width[0] + "x" + height[0] + " на глаз");
        return true;
    }

    private void makeSwapchains(MemoryStack stack) {
        IntBuffer n = stack.mallocInt(1);
        check(xrEnumerateViewConfigurationViews(instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, n, null), "виды шлема");
        if (n.get(0) != 2) throw new IllegalStateException("нужен стереорежим, а видов: " + n.get(0));
        XrViewConfigurationView.Buffer vcv = XrViewConfigurationView.calloc(2, stack);
        for (XrViewConfigurationView v : vcv) v.type$Default();
        check(xrEnumerateViewConfigurationViews(instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, n, vcv), "виды шлема");

        check(xrEnumerateSwapchainFormats(session, n, null), "форматы картинки");
        LongBuffer formats = stack.mallocLong(n.get(0));
        check(xrEnumerateSwapchainFormats(session, n, formats), "форматы картинки");
        long chosen = formats.get(0);
        boolean found = false;
        for (long want : new long[]{GL_SRGB8_ALPHA8, GL_RGBA8}) {
            for (int i = 0; i < formats.capacity() && !found; i++) {
                if (formats.get(i) == want) {
                    chosen = want;
                    found = true;
                }
            }
            if (found) break;
        }

        PointerBuffer pp = stack.mallocPointer(1);
        for (int eye = 0; eye < 2; eye++) {
            width[eye] = vcv.get(eye).recommendedImageRectWidth();
            height[eye] = vcv.get(eye).recommendedImageRectHeight();
            XrSwapchainCreateInfo sc = XrSwapchainCreateInfo.calloc(stack).type$Default().createFlags(0)
                    .usageFlags(XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT)
                    .format(chosen).sampleCount(1).width(width[eye]).height(height[eye]).faceCount(1).arraySize(1).mipCount(1);
            check(xrCreateSwapchain(session, sc, pp), "xrCreateSwapchain");
            swapchain[eye] = new XrSwapchain(pp.get(0), session);

            check(xrEnumerateSwapchainImages(swapchain[eye], n, null), "картинки глаза");
            XrSwapchainImageOpenGLKHR.Buffer imgs = XrSwapchainImageOpenGLKHR.calloc(n.get(0), stack);
            for (XrSwapchainImageOpenGLKHR im : imgs) im.type$Default();
            check(xrEnumerateSwapchainImages(swapchain[eye], n, XrSwapchainImageBaseHeader.create(imgs.address(), imgs.capacity())), "картинки глаза");

            depth[eye] = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, depth[eye]);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width[eye], height[eye]);
            fbos[eye] = new int[imgs.capacity()];
            for (int k = 0; k < imgs.capacity(); k++) {
                int fbo = glGenFramebuffers();
                glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, imgs.get(k).image(), 0);
                glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth[eye]);
                if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("буфер кадра глаза не собрался");
                fbos[eye][k] = fbo;
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        }
    }

    private XrAction action(MemoryStack stack, String name, String title, int type, LongBuffer hands) {
        XrActionCreateInfo ai = XrActionCreateInfo.calloc(stack).type$Default().actionName(stack.UTF8(name)).actionType(type)
                .localizedActionName(stack.UTF8(title)).countSubactionPaths(2).subactionPaths(hands);
        PointerBuffer pp = stack.mallocPointer(1);
        check(xrCreateAction(actionSet, ai, pp), "действие " + name);
        return new XrAction(pp.get(0), actionSet);
    }

    private void makeActions(MemoryStack stack) {
        handPath[0] = path("/user/hand/left");
        handPath[1] = path("/user/hand/right");
        LongBuffer hands = stack.longs(handPath[0], handPath[1]);
        XrActionSetCreateInfo asci = XrActionSetCreateInfo.calloc(stack).type$Default().actionSetName(stack.UTF8("gameplay"))
                .localizedActionSetName(stack.UTF8("Gameplay")).priority(0);
        PointerBuffer pp = stack.mallocPointer(1);
        check(xrCreateActionSet(instance, asci, pp), "набор действий");
        actionSet = new XrActionSet(pp.get(0), instance);

        stickX = action(stack, "stick_x", "Stick X", XR_ACTION_TYPE_FLOAT_INPUT, hands);
        stickY = action(stack, "stick_y", "Stick Y", XR_ACTION_TYPE_FLOAT_INPUT, hands);
        trigger = action(stack, "trigger", "Trigger", XR_ACTION_TYPE_FLOAT_INPUT, hands);
        squeeze = action(stack, "squeeze", "Grip", XR_ACTION_TYPE_FLOAT_INPUT, hands);
        primary = action(stack, "primary", "Lower button", XR_ACTION_TYPE_BOOLEAN_INPUT, hands);
        secondary = action(stack, "secondary", "Upper button", XR_ACTION_TYPE_BOOLEAN_INPUT, hands);
        stickClick = action(stack, "stick_click", "Stick click", XR_ACTION_TYPE_BOOLEAN_INPUT, hands);
        menu = action(stack, "menu", "Menu", XR_ACTION_TYPE_BOOLEAN_INPUT, hands);
        gripPose = action(stack, "grip_pose", "Grip pose", XR_ACTION_TYPE_POSE_INPUT, hands);
        aimPose = action(stack, "aim_pose", "Aim pose", XR_ACTION_TYPE_POSE_INPUT, hands);
        haptic = action(stack, "haptic", "Haptic", XR_ACTION_TYPE_VIBRATION_OUTPUT, hands);

        // привязки: у каждого семейства контроллеров свои имена кнопок; профиль, который рантайм не знает, просто отклоняется
        //                                    хват-значение  стик   нижняя(Л) верхняя(Л) нижняя(П) верхняя(П) меню(Л) меню(П)
        bind("/interaction_profiles/oculus/touch_controller", stack, true, true, "x", "y", "a", "b", true, false);
        bind("/interaction_profiles/valve/index_controller", stack, true, true, "a", "b", "a", "b", false, false);
        bind("/interaction_profiles/hp/mixed_reality_controller", stack, true, true, "x", "y", "a", "b", true, false);
        bind("/interaction_profiles/microsoft/motion_controller", stack, false, true, null, null, null, null, true, true);
        bind("/interaction_profiles/htc/vive_controller", stack, false, false, null, null, null, null, true, true);

        PointerBuffer sets = stack.pointers(actionSet.address());
        check(xrAttachSessionActionSets(session, XrSessionActionSetsAttachInfo.calloc(stack).type$Default().actionSets(sets)), "подключение действий");

        for (int h = 0; h < 2; h++) {
            XrActionSpaceCreateInfo si = XrActionSpaceCreateInfo.calloc(stack).type$Default().action(gripPose).subactionPath(handPath[h]);
            si.poseInActionSpace().orientation().w(1f);
            check(xrCreateActionSpace(session, si, pp), "поза рукояти");
            gripSpace[h] = new XrSpace(pp.get(0), session);
            si.action(aimPose);
            check(xrCreateActionSpace(session, si, pp), "поза указки");
            aimSpace[h] = new XrSpace(pp.get(0), session);
        }
    }

    /**
     * Привязки одного профиля. squeezeValue - хват отдаёт значение (иначе нажатие); stick - есть стик (иначе тачпад, как у Vive);
     * lowL/upL и lowR/upR - имена нижней и верхней кнопки левого и правого контроллера (null - нет); menuL/menuR - есть кнопка меню.
     */
    private void bind(String profile, MemoryStack stack, boolean squeezeValue, boolean stick, String lowL, String upL, String lowR, String upR,
                      boolean menuL, boolean menuR) {
        try {
            List<Object[]> list = new ArrayList<>();                   // {действие, путь}
            for (int h = 0; h < 2; h++) {
                String base = h == 0 ? "/user/hand/left" : "/user/hand/right";
                String low = h == 0 ? lowL : lowR, up = h == 0 ? upL : upR;
                String pad = stick ? "/input/thumbstick" : "/input/trackpad";
                list.add(new Object[]{stickX, base + pad + "/x"});
                list.add(new Object[]{stickY, base + pad + "/y"});
                list.add(new Object[]{stickClick, base + pad + "/click"});
                list.add(new Object[]{trigger, base + "/input/trigger/value"});
                list.add(new Object[]{squeeze, base + (squeezeValue ? "/input/squeeze/value" : "/input/squeeze/click")});
                if (low != null) list.add(new Object[]{primary, base + "/input/" + low + "/click"});
                if (up != null) list.add(new Object[]{secondary, base + "/input/" + up + "/click"});
                if (h == 0 ? menuL : menuR) list.add(new Object[]{menu, base + "/input/menu/click"});
                list.add(new Object[]{gripPose, base + "/input/grip/pose"});
                list.add(new Object[]{aimPose, base + "/input/aim/pose"});
                list.add(new Object[]{haptic, base + "/output/haptic"});
            }
            XrActionSuggestedBinding.Buffer b = XrActionSuggestedBinding.calloc(list.size(), stack);
            for (int i = 0; i < list.size(); i++) b.get(i).action((XrAction) list.get(i)[0]).binding(path((String) list.get(i)[1]));
            XrInteractionProfileSuggestedBinding ib = XrInteractionProfileSuggestedBinding.calloc(stack).type$Default()
                    .interactionProfile(path(profile)).suggestedBindings(b);
            int r = xrSuggestInteractionProfileBindings(instance, ib);
            if (r < 0) log.accept("профиль " + profile + " отклонён (код " + r + ")");
        } catch (RuntimeException e) {
            log.accept("профиль " + profile + " пропущен: " + e.getMessage());
        }
    }

    @Override
    public boolean running() {
        if (instance == null || session == null) return false;
        pollEvents();
        return sessionRunning && !lost;
    }

    private void pollEvents() {
        try (MemoryStack stack = stackPush()) {
            XrEventDataBuffer ev = XrEventDataBuffer.calloc(stack);
            while (true) {
                ev.type$Default();
                ev.next(NULL);
                if (xrPollEvent(instance, ev) != XR_SUCCESS) break;
                if (ev.type() == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                    int state = XrEventDataSessionStateChanged.create(ev.address()).state();
                    if (state == XR_SESSION_STATE_READY && !sessionRunning) {
                        XrSessionBeginInfo bi = XrSessionBeginInfo.calloc(stack).type$Default().primaryViewConfigurationType(XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO);
                        check(xrBeginSession(session, bi), "xrBeginSession");
                        sessionRunning = true;
                    } else if (state == XR_SESSION_STATE_STOPPING && sessionRunning) {
                        xrEndSession(session);
                        sessionRunning = false;
                    } else if (state == XR_SESSION_STATE_EXITING || state == XR_SESSION_STATE_LOSS_PENDING) {
                        sessionRunning = false;
                        lost = true;
                    }
                }
            }
        }
    }

    private float floatState(MemoryStack stack, XrAction a, int hand) {
        XrActionStateGetInfo gi = XrActionStateGetInfo.calloc(stack).type$Default().action(a).subactionPath(handPath[hand]);
        XrActionStateFloat st = XrActionStateFloat.calloc(stack).type$Default();
        if (xrGetActionStateFloat(session, gi, st) < 0) return 0f;
        return st.isActive() ? st.currentState() : 0f;
    }

    private boolean boolState(MemoryStack stack, XrAction a, int hand) {
        XrActionStateGetInfo gi = XrActionStateGetInfo.calloc(stack).type$Default().action(a).subactionPath(handPath[hand]);
        XrActionStateBoolean st = XrActionStateBoolean.calloc(stack).type$Default();
        if (xrGetActionStateBoolean(session, gi, st) < 0) return false;
        return st.isActive() && st.currentState();
    }

    private static final long BOTH = XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;

    private boolean locate(MemoryStack stack, XrSpace space, double[] pos, double[] quat) {
        XrSpaceLocation loc = XrSpaceLocation.calloc(stack).type$Default();
        if (xrLocateSpace(space, appSpace, displayTime, loc) < 0) return false;
        if ((loc.locationFlags() & BOTH) != BOTH) return false;
        XrPosef p = loc.pose();
        pos[0] = p.position$().x();
        pos[1] = p.position$().y();
        pos[2] = p.position$().z();
        quat[0] = p.orientation().x();
        quat[1] = p.orientation().y();
        quat[2] = p.orientation().z();
        quat[3] = p.orientation().w();
        return true;
    }

    @Override
    public XrFrame beginFrame() {
        if (!sessionRunning) return null;
        try (MemoryStack stack = stackPush()) {
            XrFrameState fs = XrFrameState.calloc(stack).type$Default();
            check(xrWaitFrame(session, XrFrameWaitInfo.calloc(stack).type$Default(), fs), "xrWaitFrame");
            displayTime = fs.predictedDisplayTime();
            shouldRender = fs.shouldRender();
            check(xrBeginFrame(session, XrFrameBeginInfo.calloc(stack).type$Default()), "xrBeginFrame");
            frameOpen = true;
            eyesDone = 0;
            frame.shouldRender = shouldRender;
            if (!shouldRender) return frame;

            XrViewLocateInfo vli = XrViewLocateInfo.calloc(stack).type$Default().viewConfigurationType(XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO)
                    .displayTime(displayTime).space(appSpace);
            XrViewState vs = XrViewState.calloc(stack).type$Default();
            IntBuffer cnt = stack.mallocInt(1);
            XrView.Buffer views = XrView.calloc(2, stack);
            for (XrView v : views) v.type$Default();
            check(xrLocateViews(session, vli, vs, cnt, views), "xrLocateViews");
            long ok = XR_VIEW_STATE_POSITION_VALID_BIT | XR_VIEW_STATE_ORIENTATION_VALID_BIT;
            if ((vs.viewStateFlags() & ok) != ok || cnt.get(0) != 2) {
                frame.shouldRender = false;
                return frame;
            }
            for (int i = 0; i < 2; i++) {
                XrView v = views.get(i);
                XrPosef p = v.pose();
                XrFovf fov = v.fov();
                eyePos[i][0] = p.position$().x();
                eyePos[i][1] = p.position$().y();
                eyePos[i][2] = p.position$().z();
                eyeQuat[i][0] = p.orientation().x();
                eyeQuat[i][1] = p.orientation().y();
                eyeQuat[i][2] = p.orientation().z();
                eyeQuat[i][3] = p.orientation().w();
                eyeFov[i][0] = fov.angleLeft();
                eyeFov[i][1] = fov.angleRight();
                eyeFov[i][2] = fov.angleUp();
                eyeFov[i][3] = fov.angleDown();
                XrFrame.Eye e = frame.eyes[i];
                System.arraycopy(eyePos[i], 0, e.pos, 0, 3);
                System.arraycopy(eyeQuat[i], 0, e.quat, 0, 4);
                e.left = eyeFov[i][0];
                e.right = eyeFov[i][1];
                e.up = eyeFov[i][2];
                e.down = eyeFov[i][3];
                e.width = width[i];
                e.height = height[i];
            }
            if (!locate(stack, viewSpace, frame.headPos, frame.headQuat)) {
                for (int k = 0; k < 3; k++) frame.headPos[k] = (eyePos[0][k] + eyePos[1][k]) / 2;      // запасной вариант: между глаз
                System.arraycopy(eyeQuat[0], 0, frame.headQuat, 0, 4);
            }

            XrActiveActionSet.Buffer active = XrActiveActionSet.calloc(1, stack);
            active.actionSet(actionSet);
            active.subactionPath(XR_NULL_PATH);
            xrSyncActions(session, XrActionsSyncInfo.calloc(stack).type$Default().activeActionSets(active));
            for (int h = 0; h < 2; h++) {
                XrFrame.Hand hd = frame.hands[h];
                boolean g = locate(stack, gripSpace[h], hd.gripPos, hd.gripQuat);
                boolean a = locate(stack, aimSpace[h], hd.aimPos, hd.aimQuat);
                hd.tracked = g && a;
                hd.stickX = floatState(stack, stickX, h);
                hd.stickY = floatState(stack, stickY, h);
                hd.trigger = floatState(stack, trigger, h);
                hd.grip = floatState(stack, squeeze, h);
                hd.primary = boolState(stack, primary, h);
                hd.secondary = boolState(stack, secondary, h);
                hd.stickClick = boolState(stack, stickClick, h);
                hd.menu = boolState(stack, menu, h);
            }
            return frame;
        }
    }

    @Override
    public int beginEye(int eye) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer idx = stack.mallocInt(1);
            if (xrAcquireSwapchainImage(swapchain[eye], null, idx) < 0) return 0;
            XrSwapchainImageWaitInfo wi = XrSwapchainImageWaitInfo.calloc(stack).type$Default().timeout(XR_INFINITE_DURATION);
            if (xrWaitSwapchainImage(swapchain[eye], wi) < 0) return 0;
            return fbos[eye][idx.get(0)];
        }
    }

    @Override
    public void endEye(int eye) {
        xrReleaseSwapchainImage(swapchain[eye], null);
        eyesDone++;
    }

    @Override
    public void endFrame() {
        if (!frameOpen) return;
        frameOpen = false;
        try (MemoryStack stack = stackPush()) {
            XrFrameEndInfo ei = XrFrameEndInfo.calloc(stack).type$Default().displayTime(displayTime).environmentBlendMode(XR_ENVIRONMENT_BLEND_MODE_OPAQUE);
            if (shouldRender && frame.shouldRender && eyesDone == 2) {
                XrCompositionLayerProjectionView.Buffer pv = XrCompositionLayerProjectionView.calloc(2, stack);
                for (int i = 0; i < 2; i++) {
                    XrCompositionLayerProjectionView v = pv.get(i);
                    v.type$Default();
                    v.pose().position$().set((float) eyePos[i][0], (float) eyePos[i][1], (float) eyePos[i][2]);
                    v.pose().orientation().set((float) eyeQuat[i][0], (float) eyeQuat[i][1], (float) eyeQuat[i][2], (float) eyeQuat[i][3]);
                    v.fov().set((float) eyeFov[i][0], (float) eyeFov[i][1], (float) eyeFov[i][2], (float) eyeFov[i][3]);
                    v.subImage().swapchain(swapchain[i]);
                    v.subImage().imageRect().offset().set(0, 0);
                    v.subImage().imageRect().extent().set(width[i], height[i]);
                    v.subImage().imageArrayIndex(0);
                }
                XrCompositionLayerProjection lp = XrCompositionLayerProjection.calloc(stack).type$Default().layerFlags(0).space(appSpace).views(pv);
                ei.layerCount(1).layers(stack.pointers(lp.address()));
            }
            int r = xrEndFrame(session, ei);
            if (r < 0) log.accept("xrEndFrame: код " + r);
        }
    }

    @Override
    public void haptic(int hand, float amplitude, double seconds) {
        if (session == null || !sessionRunning) return;
        try (MemoryStack stack = stackPush()) {
            XrHapticVibration hv = XrHapticVibration.calloc(stack).type$Default().duration(Math.max(1L, (long) (seconds * 1e9)))
                    .frequency(XR_FREQUENCY_UNSPECIFIED).amplitude(Math.max(0f, Math.min(1f, amplitude)));
            XrHapticActionInfo hi = XrHapticActionInfo.calloc(stack).type$Default().action(haptic).subactionPath(handPath[hand]);
            xrApplyHapticFeedback(session, hi, XrHapticBaseHeader.create(hv.address()));
        }
    }

    @Override
    public void stop() {
        try {
            if (session != null && sessionRunning) xrEndSession(session);
        } catch (Throwable ignored) {
            // закрываем в любом случае
        }
        sessionRunning = false;
        destroy();
    }

    private void destroy() {
        try {
            for (int i = 0; i < 2; i++) {
                if (gripSpace[i] != null) xrDestroySpace(gripSpace[i]);
                if (aimSpace[i] != null) xrDestroySpace(aimSpace[i]);
                if (swapchain[i] != null) xrDestroySwapchain(swapchain[i]);
                if (fbos[i] != null) for (int f : fbos[i]) glDeleteFramebuffers(f);
                if (depth[i] != 0) glDeleteRenderbuffers(depth[i]);
                gripSpace[i] = aimSpace[i] = null;
                swapchain[i] = null;
                fbos[i] = null;
                depth[i] = 0;
            }
            if (viewSpace != null) xrDestroySpace(viewSpace);
            if (appSpace != null) xrDestroySpace(appSpace);
            if (actionSet != null) xrDestroyActionSet(actionSet);
            if (session != null) xrDestroySession(session);
            if (instance != null) xrDestroyInstance(instance);
        } catch (Throwable ignored) {
            // освобождение не должно ронять игру
        }
        viewSpace = appSpace = null;
        actionSet = null;
        session = null;
        instance = null;
    }
}
