package com.errdeck.core.fingerprint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.errdeck.core.event.StackFrame;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppFramesTest {

    private final AppFrames appFrames = new AppFrames(List.of("com.livebroadcast", "com.copage"));

    @Test
    void 자바_프레임은_패키지_목록과_그_하위_패키지를_앱으로_본다() {
        assertTrue(appFrames.isApp(java("com.livebroadcast.FanService")));
        assertTrue(appFrames.isApp(java("com.livebroadcast.interaction.payment.PaymentService")));
        assertTrue(appFrames.isApp(java("com.copage.Doc")));
        assertFalse(appFrames.isApp(java("com.livebroadcastx.Other")));
        assertFalse(appFrames.isApp(java("org.springframework.web.servlet.DispatcherServlet")));
    }

    @Test
    void 브라우저_프레임은_node_modules_밖의_파일을_앱으로_본다() {
        assertTrue(appFrames.isApp(browser("src/collab/client.ts")));
        assertFalse(appFrames.isApp(browser("node_modules/vue/dist/vue.runtime.esm-bundler.js")));
        assertFalse(appFrames.isApp(browser("webpack:///./node_modules/axios/lib/core/Axios.js")));
        assertFalse(appFrames.isApp(browser(null)));
    }

    @Test
    void SDK가_정한_in_app_을_먼저_따른다() {
        assertFalse(appFrames.isApp(new StackFrame("com.livebroadcast.FanService", "f", null, 1, null, false)));
        assertTrue(appFrames.isApp(new StackFrame("org.example.Lib", "f", null, 1, null, true)));
    }

    private static StackFrame java(String className) {
        return new StackFrame(className, "f", "F.java", 1, null, null);
    }

    private static StackFrame browser(String filename) {
        return new StackFrame(null, "f", filename, 1, 10, null);
    }
}
