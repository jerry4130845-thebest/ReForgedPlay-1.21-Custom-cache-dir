package com.replaymod.render.utils;

import com.replaymod.core.ReplayMod;
import com.replaymod.render.rendering.Frame;
import com.replaymod.render.rendering.FrameConsumer;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** A private LWJGL universe from actual parent resources, with no temporary/embedded JAR requirement. */
public class Lwjgl3Loader extends URLClassLoader {
    static { registerAsParallelCapable(); }
    private static Lwjgl3Loader instance;
    private static RuntimeException initializationFailure;
    private final Set<String> implClasses = new CopyOnWriteArraySet<>();
    private final LwjglNativeWorkspace workspace;

    private Lwjgl3Loader(Path selectedTempRoot) throws IOException, ReflectiveOperationException {
        super(new URL[0], Lwjgl3Loader.class.getClassLoader());
        // SharedLibraryLoader can mutate JVM properties and fall back to temp/cwd/home. Never enter it.
        for (String property : new String[] { "org.lwjgl.libname", "org.lwjgl.librarypath", "org.lwjgl.tinyexr",
                "org.lwjgl.system.SharedLibraryExtractPath", "org.lwjgl.system.SharedLibraryExtractDirectory",
                "org.lwjgl.system.SharedLibraryExtractForce", "org.lwjgl.system.bundledLibrary.nameMapper",
                "org.lwjgl.system.bundledLibrary.pathMapper" }) {
            if (System.getProperty(property) != null)
                throw new IOException("EXR isolated native loading conflicts with JVM property " + property + "; leave parent configuration unchanged");
        }
        workspace = LwjglNativeWorkspace.open(selectedTempRoot, getParent());
        // Verified Configuration has set(T), erased to Object. Initialize it BEFORE Library/Platform/MemoryUtil.
        Class<?> config = Class.forName("org.lwjgl.system.Configuration", true, this);
        Method set = config.getMethod("set", Object.class);
        set.invoke(config.getField("LIBRARY_NAME").get(null), workspace.coreLibrary().toString());
        set.invoke(config.getField("LIBRARY_PATH").get(null), workspace.directory().toString());
        set.invoke(config.getField("SHARED_LIBRARY_EXTRACT_PATH").get(null), workspace.directory().toString());
        set.invoke(config.getField("SHARED_LIBRARY_EXTRACT_FORCE").get(null), false);
        set.invoke(config.getField("BUNDLED_LIBRARY_NAME_MAPPER").get(null), "default");
        set.invoke(config.getField("BUNDLED_LIBRARY_PATH_MAPPER").get(null), "default");
        // Core's system allocator requires no third native/allocator module from the game's classpath.
        set.invoke(config.getField("MEMORY_ALLOCATOR").get(null), "system");
    }

    private boolean canBeSharedWithMc(String name) {
        if (name.startsWith("org.lwjgl.")) return false;
        for (String impl : implClasses) if (name.equals(impl) || name.startsWith(impl + "$")) return false;
        return true;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Even if a prepared file becomes unavailable, LWJGL must fail EXR, not silently extract elsewhere.
        if (name.equals("org.lwjgl.system.SharedLibraryLoader") || name.startsWith("org.lwjgl.system.SharedLibraryLoader$"))
            throw new ClassNotFoundException("EXR prohibits SharedLibraryLoader extraction/fallback: " + name);
        if (canBeSharedWithMc(name)) return super.loadClass(name, resolve);
        synchronized (getClassLoadingLock(name)) {
            Class<?> cls = findLoadedClass(name);
            if (cls == null) cls = findClass(name);
            if (resolve) resolveClass(cls);
            return cls;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/') + ".class";
        URL resource = getParent().getResource(path);
        if (resource == null) throw new ClassNotFoundException("EXR parent class resource missing: " + path);
        try {
            if (name.startsWith("org.lwjgl.")) workspace.verifyClassResource(path, resource);
            try (InputStream in = resource.openStream()) {
                byte[] bytes = in.readNBytes(4 * 1024 * 1024 + 1);
                if (bytes.length > 4 * 1024 * 1024) throw new IOException("EXR class resource too large: " + resource);
                // Preserve the actual resource origin for diagnostics; no copied JAR or cache classpath.
                return defineClass(name, bytes, 0, bytes.length, new CodeSource(resource, (java.security.cert.Certificate[]) null));
            }
        } catch (IOException failure) { throw new ClassNotFoundException("EXR cannot define " + name + " from " + resource, failure); }
    }

    private static boolean nativeName(String name) {
        return name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib");
    }

    @Override
    public URL getResource(String name) {
        if (!nativeName(name)) return super.getResource(name);
        try { return workspace.nativeResource(name); }
        catch (IOException failure) {
            // Must not return null/the original JAR URL: Library would try an external extraction/system fallback.
            throw new IllegalStateException("EXR native resource unavailable in its process workspace: " + name, failure);
        }
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (nativeName(name)) return Collections.enumeration(Collections.singleton(workspace.nativeResource(name)));
        return super.getResources(name);
    }

    public static synchronized Lwjgl3Loader instance() {
        if (initializationFailure != null) throw initializationFailure;
        if (instance == null) try {
            // Exactly one settings snapshot for this process-lifetime native task, not the replay cache.
            instance = new Lwjgl3Loader(ReplayMod.instance.folders.snapshotNativeTempRoot());
        } catch (IOException | ReflectiveOperationException | LinkageError failure) {
            initializationFailure = new IllegalStateException("EXR isolated LWJGL initialization failed (other render formats are unaffected)", failure);
            throw initializationFailure;
        }
        return instance;
    }

    @SuppressWarnings("unchecked")
    public static <P extends Frame> FrameConsumer<P> createFrameConsumer(
            Class<? extends FrameConsumer<P>> implClass, Class<?>[] parameterTypes, Object[] args) {
        try {
            Lwjgl3Loader loader = instance();
            // TinyEXR 3.3.1 generated mallocStack calls Struct.wrap(Class,long). MC core 3.3.3
            // removed that ABI. Refuse this EXR consumer before it can crash/swallow an export failure;
            // never replace core from a local cache or rewrite the binding to fake compatibility.
            try {
                Class.forName("org.lwjgl.system.Struct", false, loader).getDeclaredMethod("wrap", Class.class, long.class);
            } catch (NoSuchMethodException incompatible) {
                throw new IllegalStateException("EXR unavailable: visible TinyEXR 3.3.1 requires Struct.wrap(Class,long), absent from MC LWJGL core 3.3.3; dependencies unchanged", incompatible);
            }
            loader.implClasses.add(implClass.getName());
            Class<?> realClass = Class.forName(implClass.getName(), true, loader);
            Constructor<?> constructor = realClass.getConstructor(parameterTypes);
            return (FrameConsumer<P>) constructor.newInstance(args);
        } catch (ReflectiveOperationException failure) { throw new RuntimeException("EXR consumer initialization failed", failure); }
    }
}
