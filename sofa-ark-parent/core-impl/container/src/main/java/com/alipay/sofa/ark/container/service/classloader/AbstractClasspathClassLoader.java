/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.ark.container.service.classloader;

import com.alipay.sofa.ark.bootstrap.UseFastConnectionExceptionsEnumeration;
import com.alipay.sofa.ark.common.log.ArkLoggerFactory;
import com.alipay.sofa.ark.common.util.StringUtils;
import com.alipay.sofa.ark.container.service.ArkServiceContainerHolder;
import com.alipay.sofa.ark.exception.ArkLoaderException;
import com.alipay.sofa.ark.loader.jar.Handler;
import com.alipay.sofa.ark.spi.service.classloader.ClassLoaderService;
import sun.misc.CompoundEnumeration;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.*;
import java.util.jar.JarFile;

/**
 *
 * Abstract Classpath ClassLoader, basic logic to load class/resource, sub class need to implement
 *
 * @author ruoshan
 * @since 0.1.0
 */
public abstract class AbstractClasspathClassLoader extends URLClassLoader {

    protected static final String CLASS_RESOURCE_SUFFIX = ".class";

    protected ClassLoaderService  classloaderService    = ArkServiceContainerHolder.getContainer()
                                                            .getService(ClassLoaderService.class);

    Map<String, URL> urlResourceMap = new HashMap<>();

    static {
        ClassLoader.registerAsParallelCapable();
    }

    public AbstractClasspathClassLoader(URL[] urls) {
        super(urls, null);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (StringUtils.isEmpty(name)) {
            return null;
        }
        Handler.setUseFastConnectionExceptions(true);
        try {
            definePackageIfNecessary(name);
            return loadClassInternal(name, resolve);
        } finally {
            Handler.setUseFastConnectionExceptions(false);
        }
    }

    /**
     * Define a package before a {@code findClass} call is made. This is necessary to
     * ensure that the appropriate manifest for nested JARs is associated with the
     * package.
     * @param className the class name being found
     */
    private void definePackageIfNecessary(String className) {
        int lastDot = className.lastIndexOf('.');
        if (lastDot >= 0) {
            String packageName = className.substring(0, lastDot);
            if (getPackage(packageName) == null) {
                try {
                    definePackage(className, packageName);
                } catch (IllegalArgumentException ex) {
                    // Tolerate race condition due to being parallel capable
                }
            }
        }
    }

    private void definePackage(final String className, final String packageName) {
        try {
            AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
                @Override
                public Object run() throws Exception {
                    StringBuilder pen = new StringBuilder(packageName.length() + 10);
                    StringBuilder cen = new StringBuilder(className.length() + 10);
                    String packageEntryName = pen.append(packageName.replace('.', '/')).append("/")
                        .toString();
                    String classEntryName = cen.append(className.replace('.', '/'))
                        .append(".class").toString();
                    for (URL url : getURLs()) {
                        try {
                            URLConnection connection = url.openConnection();
                            if (connection instanceof JarURLConnection) {
                                JarFile jarFile = ((JarURLConnection) connection).getJarFile();
                                if (jarFile.getEntry(classEntryName) != null
                                    && jarFile.getEntry(packageEntryName) != null
                                    && jarFile.getManifest() != null) {
                                    definePackage(packageName, jarFile.getManifest(), url);
                                    return null;
                                }
                            }
                        } catch (IOException ex) {
                            // Ignore
                        }
                    }
                    return null;
                }
            }, AccessController.getContext());
        } catch (java.security.PrivilegedActionException ex) {
            // Ignore
        }
    }

    /**
     * Real logic to load class，need to implement by Sub ClassLoader
     * @param name
     * @param resolve
     * @return
     * @throws ArkLoaderException
     */
    abstract protected Class<?> loadClassInternal(String name, boolean resolve)
                                                                               throws ArkLoaderException;

    @Override
    public URL getResource(String name) {
        Handler.setUseFastConnectionExceptions(true);
        URL url = null;
        try {
            if (urlResourceMap.containsKey(name)) {
                return urlResourceMap.get(name);
            }
            URL ret = preFindResource(name);
            if (ret != null) {
                return ret;
            }
            ret = getResourceInternal(name);
            url = ret != null ? ret : postFindResource(name);
            urlResourceMap.put(name, url);
            return url;
        } finally {
            Handler.setUseFastConnectionExceptions(false);
        }
    }

    /**
     * Real logic to get resource
     * @param name
     * @return
     */
    protected URL getResourceInternal(String name) {
        // 1. find jdk resource
        URL url = getJdkResource(name);

        // 2. find export resource
        if (url == null) {
            url = getExportResource(name);
        }

        // 3. get .class resource
        if (url == null) {
            url = getClassResource(name);
        }

        // 4. get local resource
        if (url == null) {
            url = getLocalResource(name);
        }

        return url;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        Handler.setUseFastConnectionExceptions(true);
        try {
            Enumeration<URL> ret = preFindResources(name);
            if (ret != null && ret.hasMoreElements()) {
                return ret;
            }
            ret = getResourcesInternal(name);
            if (ret != null && ret.hasMoreElements()) {
                return ret;
            }
            ret = postFindResources(name);
            return ret != null ? ret : new CompoundEnumeration<URL>(
                (Enumeration<URL>[]) new Enumeration<?>[] {});
        } finally {
            Handler.setUseFastConnectionExceptions(false);
        }
    }

    /**
     * Real logic to get resources
     * @param name
     * @return
     * @throws IOException
     */
    protected Enumeration<URL> getResourcesInternal(String name) throws IOException {
        List<Enumeration<URL>> enumerationList = new ArrayList<>();
        // 1. find jdk resources
        enumerationList.add(getJdkResources(name));

        // 2. find exported resources
        enumerationList.add(getExportResources(name));

        // 3. find local resources
        enumerationList.add(getLocalResources(name));

        return new CompoundEnumeration<>(
            enumerationList.toArray((Enumeration<URL>[]) new Enumeration<?>[0]));
    }

    /**
     * Whether to find class that exported by other classloader
     * @param className class name
     * @return
     */
    abstract boolean shouldFindExportedClass(String className);

    /**
     * Whether to find resource that exported by other classloader
     * @param resourceName
     * @return
     */
    abstract boolean shouldFindExportedResource(String resourceName);

    /**
     * Load JDK class
     * @param name class name
     * @return
     */
    protected Class<?> resolveJDKClass(String name) {
        try {
            return classloaderService.getJDKClassLoader().loadClass(name);
        } catch (ClassNotFoundException e) {
            // ignore
        }
        return null;
    }

    /**
     * Load export class
     * @param name
     * @return
     */
    protected Class<?> resolveExportClass(String name) {
        if (shouldFindExportedClass(name)) {
            ClassLoader importClassLoader = classloaderService.findExportClassLoader(name);
            if (importClassLoader != null) {
                try {
                    return importClassLoader.loadClass(name);
                } catch (ClassNotFoundException e) {
                    // just log when debug level
                    if (ArkLoggerFactory.getDefaultLogger().isDebugEnabled()) {
                        // log debug message
                        ArkLoggerFactory.getDefaultLogger().debug(
                            "Fail to load export class " + name, e);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Load ark spi class
     * @param name
     * @return
     */
    protected Class<?> resolveArkClass(String name) {
        if (classloaderService.isArkSpiClass(name) || classloaderService.isArkApiClass(name)
            || classloaderService.isArkLogClass(name)
            || classloaderService.isArkExceptionClass(name)) {
            try {
                return classloaderService.getArkClassLoader().loadClass(name);
            } catch (ClassNotFoundException e) {
                // ignore
            }
        }
        return null;
    }

    /**
     * Load classpath class
     * @param name
     * @return
     */
    protected Class<?> resolveLocalClass(String name) {
        try {
            return super.loadClass(name, false);
        } catch (ClassNotFoundException e) {
            // ignore
        }
        return null;
    }

    /**
     * Load Java Agent Class
     * @param name className
     * @return
     */
    protected Class<?> resolveJavaAgentClass(String name) {
        try {
            classloaderService.getAgentClassLoader().loadClass(name);
            return classloaderService.getSystemClassLoader().loadClass(name);
        } catch (ClassNotFoundException e) {
            // ignore
        }
        return null;
    }

    /**
     * Find export resource
     * @param resourceName
     * @return
     */
    protected URL getExportResource(String resourceName) {
        if (shouldFindExportedResource(resourceName)) {
            URL url;
            List<ClassLoader> exportResourceClassLoadersInOrder = classloaderService
                .findExportResourceClassLoadersInOrder(resourceName);
            if (exportResourceClassLoadersInOrder != null) {
                for (ClassLoader exportResourceClassLoader : exportResourceClassLoadersInOrder) {
                    url = exportResourceClassLoader.getResource(resourceName);
                    if (url != null) {
                        return url;
                    }
                }
            }

        }
        return null;
    }

    /**
     * Find jdk dir resource
     * @param resourceName
     * @return
     */
    protected URL getJdkResource(String resourceName) {
        return classloaderService.getJDKClassLoader().getResource(resourceName);
    }

    /**
     * Find .class resource
     * @param resourceName
     * @return
     */
    protected URL getClassResource(String resourceName) {
        if (resourceName.endsWith(CLASS_RESOURCE_SUFFIX)) {
            String className = transformClassName(resourceName);
            if (resolveArkClass(className) != null) {
                return classloaderService.getArkClassLoader().getResource(resourceName);
            }

            if (shouldFindExportedClass(className)) {
                ClassLoader classLoader = classloaderService.findExportClassLoader(className);
                return classLoader == null ? null : classLoader.getResource(resourceName);
            }
        }
        return null;
    }

    /**
     * Find local resource
     * @param resourceName
     * @return
     */
    protected URL getLocalResource(String resourceName) {
        return super.getResource(resourceName);
    }

    private String transformClassName(String name) {
        if (name.endsWith(CLASS_RESOURCE_SUFFIX)) {
            name = name.substring(0, name.length() - CLASS_RESOURCE_SUFFIX.length());
        }
        return name.replace("/", ".");
    }

    /**
     * Find export resources
     * @param resourceName
     * @return
     */
    @SuppressWarnings("unchecked")
    protected Enumeration<URL> getExportResources(String resourceName) throws IOException {
        if (shouldFindExportedResource(resourceName)) {
            List<ClassLoader> exportResourceClassLoadersInOrder = classloaderService
                .findExportResourceClassLoadersInOrder(resourceName);
            if (exportResourceClassLoadersInOrder != null) {
                List<Enumeration<URL>> enumerationList = new ArrayList<>();
                for (ClassLoader exportResourceClassLoader : exportResourceClassLoadersInOrder) {
                    enumerationList.add(((AbstractClasspathClassLoader) exportResourceClassLoader)
                        .getLocalResources(resourceName));
                }
                return new CompoundEnumeration<>(
                    enumerationList.toArray((Enumeration<URL>[]) new Enumeration<?>[0]));
            }
        }
        return Collections.emptyEnumeration();
    }

    protected Enumeration<URL> getLocalResources(String resourceName) throws IOException {
        return new UseFastConnectionExceptionsEnumeration(super.getResources(resourceName));
    }

    protected Enumeration<URL> getJdkResources(String resourceName) throws IOException {
        return new UseFastConnectionExceptionsEnumeration(classloaderService.getJDKClassLoader()
            .getResources(resourceName));
    }

    /**
     * invoked before {@link #loadClass(String, boolean)}
     *
     * @param className
     * @return
     * @throws ArkLoaderException
     */
    protected abstract Class<?> preLoadClass(String className) throws ArkLoaderException;

    /**
     * invoked after {@link #loadClass(String, boolean)}
     *
     * @param className
     * @return
     * @throws ArkLoaderException
     */
    protected abstract Class<?> postLoadClass(String className) throws ArkLoaderException;

    /**
     * invoked before {@link #getResource(String)}
     *
     * @param resourceName
     * @return
     */
    protected abstract URL preFindResource(String resourceName);

    /**
     * invoked after {@link #getResource(String)}
     *
     * @param resourceName
     * @return
     */
    protected abstract URL postFindResource(String resourceName);

    /**
     * invoked before {@link #getResources(String)}
     *
     * @param resourceName
     * @return
     */
    protected abstract Enumeration<URL> preFindResources(String resourceName) throws IOException;

    /**
     * invoked after {@link #getResources(String)}
     *
     * @param resourceName
     * @return
     */
    protected abstract Enumeration<URL> postFindResources(String resourceName) throws IOException;
}