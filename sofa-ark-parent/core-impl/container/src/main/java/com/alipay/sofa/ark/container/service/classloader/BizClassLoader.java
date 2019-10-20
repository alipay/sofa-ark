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

import com.alipay.sofa.ark.api.ArkConfigs;
import com.alipay.sofa.ark.common.log.ArkLoggerFactory;
import com.alipay.sofa.ark.container.service.ArkServiceContainerHolder;
import com.alipay.sofa.ark.exception.ArkLoaderException;
import com.alipay.sofa.ark.spi.constant.Constants;
import com.alipay.sofa.ark.spi.model.Biz;
import com.alipay.sofa.ark.spi.service.biz.BizManagerService;
import com.alipay.sofa.ark.spi.service.classloader.ClassLoaderHook;
import com.alipay.sofa.ark.spi.service.extension.ArkServiceLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.alipay.sofa.ark.spi.constant.Constants.BIZ_CLASS_LOADER_HOOK;

/**
 * Ark Biz ClassLoader
 *
 * @author ruoshan
 * @since 0.1.0
 */
public class BizClassLoader extends AbstractClasspathClassLoader {

    private String               bizIdentity;
    private BizManagerService    bizManagerService = ArkServiceContainerHolder.getContainer()
                                                       .getService(BizManagerService.class);
    private ClassLoaderHook<Biz> bizClassLoaderHook;
    private AtomicBoolean        isHookLoaded      = new AtomicBoolean(false);
    private AtomicBoolean        skipLoadHook      = new AtomicBoolean(false);

    private static final Logger  LOGGER            = ArkLoggerFactory.getDefaultLogger();

    public BizClassLoader(String bizIdentity, URL[] urls) {
        super(urls);
        this.bizIdentity = bizIdentity;
    }

    @Override
    protected Class<?> loadClassInternal(String name, boolean resolve) throws ArkLoaderException {
        Class<?> clazz = null;

        // 0. sun reflect related class throw exception directly
        if (classloaderService.isSunReflectClass(name)) {
            throw new ArkLoaderException(
                String
                    .format(
                        "[ArkBiz Loader] %s : can not load class: %s, this class can only be loaded by sun.reflect.DelegatingClassLoader",
                        bizIdentity, name));
        }

        // 1. findLoadedClass
        if (clazz == null) {
            clazz = findLoadedClass(name);
        }

        // 2. JDK related class
        if (clazz == null) {
            clazz = resolveJDKClass(name);
        }

        // 3. Ark Spi class
        if (clazz == null) {
            clazz = resolveArkClass(name);
        }

        // 4. pre find class
        if (clazz == null) {
            clazz = preLoadClass(name);
        }

        // 5. Plugin Export class
        if (clazz == null) {
            clazz = resolveExportClass(name);
        }

        // 6. Biz classpath class
        if (clazz == null) {
            clazz = resolveLocalClass(name);
        }

        // 7. Java Agent ClassLoader for agent problem
        if (clazz == null) {
            clazz = resolveJavaAgentClass(name);
        }

        // 8. post find class
        if (clazz == null) {
            clazz = postLoadClass(name);
        }

        if (clazz != null) {
            if (resolve) {
                super.resolveClass(clazz);
            }
            return clazz;
        }

        throw new ArkLoaderException(String.format("[ArkBiz Loader] %s : can not load class: %s",
            bizIdentity, name));
    }

    @Override
    boolean shouldFindExportedClass(String className) {
        return !classloaderService.isDeniedImportClass(bizIdentity, className);
    }

    @Override
    boolean shouldFindExportedResource(String resourceName) {
        return !classloaderService.isDeniedImportResource(bizIdentity, resourceName);
    }

    private void loadBizClassLoaderHook() {
        if (!skipLoadHook.get()) {
            synchronized (this) {
                if (isHookLoaded.compareAndSet(false, true)) {
                    // get current biz hook spi
                    bizClassLoaderHook = ArkServiceLoader.loadExtension(bizIdentity,
                        ClassLoaderHook.class, BIZ_CLASS_LOADER_HOOK);
                    try {
                        // get master biz hook spi
                        if (bizClassLoaderHook == null && isShouldHookByMaster()) {
                            String masterBizIdentity = bizManagerService.getMasterBiz()
                                .getIdentity();
                            bizClassLoaderHook = ArkServiceLoader.loadExtension(masterBizIdentity,
                                ClassLoaderHook.class, BIZ_CLASS_LOADER_HOOK);
                        }
                    } catch (Throwable t) {
                        LOGGER.error("Failed to load bizClassLoaderHook extension.", t);
                        // just compatible test case,because in normal scene,master biz must be exist
                        bizClassLoaderHook = null;
                    } finally {
                        skipLoadHook.set(true);
                    }
                }
            }
        }
    }

    @Override
    protected Class<?> preLoadClass(String className) throws ArkLoaderException {
        try {
            loadBizClassLoaderHook();
            return bizClassLoaderHook == null ? null : bizClassLoaderHook.preFindClass(className,
                classloaderService, bizManagerService.getBizByIdentity(bizIdentity));
        } catch (Throwable throwable) {
            throw new ArkLoaderException(String.format(
                "Pre find class %s occurs an error via biz ClassLoaderHook: %s.", className,
                bizClassLoaderHook), throwable);
        }
    }

    @Override
    protected Class<?> postLoadClass(String className) throws ArkLoaderException {
        try {
            loadBizClassLoaderHook();
            return bizClassLoaderHook == null ? null : bizClassLoaderHook.postFindClass(className,
                classloaderService, bizManagerService.getBizByIdentity(bizIdentity));
        } catch (Throwable throwable) {
            throw new ArkLoaderException(String.format(
                "Post find class %s occurs an error via biz ClassLoaderHook: %s.", className,
                bizClassLoaderHook), throwable);
        }
    }

    @Override
    protected URL preFindResource(String resourceName) {
        loadBizClassLoaderHook();
        return bizClassLoaderHook == null ? null : bizClassLoaderHook.preFindResource(resourceName,
            classloaderService, bizManagerService.getBizByIdentity(bizIdentity));
    }

    @Override
    protected URL postFindResource(String resourceName) {
        loadBizClassLoaderHook();
        return bizClassLoaderHook == null ? null : bizClassLoaderHook.postFindResource(
            resourceName, classloaderService, bizManagerService.getBizByIdentity(bizIdentity));
    }

    @Override
    protected Enumeration<URL> preFindResources(String resourceName) throws IOException {
        loadBizClassLoaderHook();
        return bizClassLoaderHook == null ? null : bizClassLoaderHook.preFindResources(
            resourceName, classloaderService, bizManagerService.getBizByIdentity(bizIdentity));
    }

    @Override
    protected Enumeration<URL> postFindResources(String resourceName) throws IOException {
        loadBizClassLoaderHook();
        return bizClassLoaderHook == null ? null : bizClassLoaderHook.postFindResources(
            resourceName, classloaderService, bizManagerService.getBizByIdentity(bizIdentity));
    }

    /**
     * Getter method for property <tt>bizIdentity</tt>.
     *
     * @return property value of bizIdentity
     */
    public String getBizIdentity() {
        return bizIdentity;
    }

    /**
     * validate is master biz or not
     * @return
     */
    private boolean isMasterBiz() {
        return bizIdentity.equals(bizManagerService.getMasterBiz().getIdentity());
    }

    private boolean isShouldHookByMaster() {
        return !isMasterBiz()
               && ArkConfigs.getBooleanValue(Constants.IS_DELEGATE_MASTER_HOOK, false);
    }
}