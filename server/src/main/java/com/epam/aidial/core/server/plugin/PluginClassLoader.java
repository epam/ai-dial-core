package com.epam.aidial.core.server.plugin;

import com.epam.aidial.core.plugin.SharedPackages;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

final class PluginClassLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    private final List<String> jarRoots;

    PluginClassLoader(URL[] jars, ClassLoader shared) {
        super(jars, new SharedClassLoader(shared));
        jarRoots = Arrays.stream(jars).map(jar -> "jar:" + jar.toExternalForm() + "!/").toList();
    }

    private boolean isDirectoryResource(URL resource) {
        return resource != null && jarRoots.stream().anyMatch(resource.toExternalForm()::startsWith);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // URLClassLoader follows Manifest Class-Path. Do not define a class found in an unlisted jar.
        if (!isDirectoryResource(super.findResource(name.replace('.', '/') + ".class"))) {
            throw new ClassNotFoundException(name);
        }
        return super.findClass(name);
    }

    @Override
    public URL findResource(String name) {
        URL resource = super.findResource(name);
        return isDirectoryResource(resource) ? resource : null;
    }

    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        return Collections.enumeration(Collections.list(super.findResources(name)).stream().filter(this::isDirectoryResource).toList());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (SharedPackages.isSharedClass(name)) {
                    // Missing Shared definitions must not fall back to a bundled class.
                    loaded = getParent().loadClass(name);
                } else {
                    try {
                        loaded = ClassLoader.getPlatformClassLoader().loadClass(name);
                    } catch (ClassNotFoundException e) {
                        loaded = findClass(name);
                    }
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    @Override
    public URL getResource(String name) {
        if (name.startsWith("META-INF/services/")) {
            return findResource(name);
        }
        if (SharedPackages.isSharedResource(name)) {
            return getParent().getResource(name);
        }
        URL platform = ClassLoader.getPlatformClassLoader().getResource(name);
        return platform == null ? findResource(name) : platform;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (name.startsWith("META-INF/services/")) {
            return findResources(name);
        }
        if (SharedPackages.isSharedResource(name)) {
            return getParent().getResources(name);
        }
        var resources = Collections.list(ClassLoader.getPlatformClassLoader().getResources(name));
        resources.addAll(Collections.list(findResources(name)));
        return Collections.enumeration(resources);
    }
}
