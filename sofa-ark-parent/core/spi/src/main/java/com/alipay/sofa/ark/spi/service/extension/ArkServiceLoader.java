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
package com.alipay.sofa.ark.spi.service.extension;

import java.util.List;

/**
 * @author qilong.zql
 * @since 0.6.0
 */
public class ArkServiceLoader {
    private static ExtensionLoaderService extensionLoaderService;

    public static <T> T loadExtension(Class<T> interfaceType, String extensionName) {
        return extensionLoaderService.getExtensionContributor(interfaceType, extensionName);
    }

    public static <T> List<T> loadExtension(Class<T> interfaceType) {
        return extensionLoaderService.getExtensionContributor(interfaceType);
    }

    /**
     *
     * @param isolateSpace    isolate by biz or plugin
     * @param interfaceType
     * @param extensionName
     * @param <T>
     * @return
     */
    public static <T> T loadExtension(String isolateSpace, Class<T> interfaceType,
                                      String extensionName) {
        return extensionLoaderService.getExtensionContributor(isolateSpace, interfaceType,
            extensionName);
    }

    /**
     * isolate by biz or plugin
     * @param isolateSpace
     * @param interfaceType
     * @param <T>
     * @return
     */
    public static <T> List<T> loadExtension(String isolateSpace, Class<T> interfaceType) {
        return extensionLoaderService.getExtensionContributor(isolateSpace, interfaceType);
    }

    public static ExtensionLoaderService getExtensionLoaderService() {
        return extensionLoaderService;
    }

    public static void setExtensionLoaderService(ExtensionLoaderService extensionLoaderService) {
        ArkServiceLoader.extensionLoaderService = extensionLoaderService;
    }
}