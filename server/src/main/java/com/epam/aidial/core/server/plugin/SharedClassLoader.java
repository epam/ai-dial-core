package com.epam.aidial.core.server.plugin;

import com.epam.aidial.core.plugin.SharedPackages;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;

/** A filtering view, not a child of Shared: its parent cannot bypass the published boundary. */
final class SharedClassLoader extends ClassLoader {

    private final ClassLoader shared;

    SharedClassLoader(ClassLoader shared) {
        super(ClassLoader.getPlatformClassLoader());
        this.shared = shared;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        try {
            return getParent().loadClass(name);
        } catch (ClassNotFoundException e) {
            if (SharedPackages.isSharedClass(name)) {
                return shared.loadClass(name);
            }
            throw e;
        }
    }

    @Override
    public URL getResource(String name) {
        if (name.startsWith("META-INF/services/")) {
            return null;
        }
        return SharedPackages.isSharedResource(name) ? shared.getResource(name) : getParent().getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (name.startsWith("META-INF/services/")) {
            return Collections.emptyEnumeration();
        }
        return SharedPackages.isSharedResource(name) ? shared.getResources(name) : getParent().getResources(name);
    }
}
