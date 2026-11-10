package com.meshtastic.client;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.utils.AppPreferences;
import javafx.application.Platform;
import javafx.scene.text.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Synthetic map fixtures must never open the developer's database or download tiles. */
public abstract class TraceMapTestSupport {
    @TempDir
    protected Path tempHome;
    private String previousLanguage;

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
        // JavaFX loads its font DLL lazily. Load it before user.home points at a
        // per-test TempDir, otherwise Windows keeps that directory locked at cleanup.
        fx(() -> new Text("Map trace font warmup").getLayoutBounds());
    }

    @BeforeEach
    void isolateMapServices() {
        previousLanguage = I18n.getLanguageTag();
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
        AppPreferences.setMapOfflineMode(true);
        AppPreferences.setMapTileDirectory(tempHome.resolve("tiles").toString());
        I18n.setLanguageTagForTests("en");
    }

    @AfterEach
    void closeMapServices() {
        try {
            TestEnvironmentSupport.resetSingletons();
        } finally {
            I18n.setLanguageTagForTests(previousLanguage);
        }
    }

    protected static <T> T fx(Callable<T> action) {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        try {
            return task.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("Map assertion failed on the JavaFX thread", e);
        }
    }

    @SuppressWarnings("unchecked")
    protected static <T> T field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot inspect " + name, e);
        }
    }

    protected static void field(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot set " + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    protected static <T> T invoke(Object target, String name, Class<?>[] parameters, Object... values) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return (T) method.invoke(target, values);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot invoke " + name, e);
        }
    }
}
