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
package com.alipay.sofa.ark.api;

import com.alipay.sofa.ark.exception.ArkRuntimeException;
import com.alipay.sofa.ark.spi.configurator.ArkConfigHook;
import com.alipay.sofa.ark.spi.configurator.ArkConfigListener;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author qilong.zql
 * @author GengZhang
 * @since 0.6.0
 */
public class ArkConfigs {

    /**
     * Global Configuration
     */
    private final static ConcurrentMap<String, Object>                  CFG          = new ConcurrentHashMap<String, Object>();

    /**
     * Configuration Listener
     */
    private final static ConcurrentMap<String, List<ArkConfigListener>> CFG_LISTENER = new ConcurrentHashMap<String, List<ArkConfigListener>>();

    /**
     * Configuration Hook
     */
    private final static ConcurrentMap<String, List<ArkConfigHook>>     CFG_HOOK     = new ConcurrentHashMap<String, List<ArkConfigHook>>();

    /**
     * executed only once
     */
    public static void init(List<URL> confFiles) {
        try {
            // load file configs
            for (URL url : confFiles) {
                loadConfigFile(url.getFile());
            }

            // load system properties
            CFG.putAll(new HashMap(System.getProperties())); // 注意部分属性可能被覆盖为字符串
        } catch (Exception e) {
            throw new ArkRuntimeException("Catch Exception when load ArkConfigs", e);
        }
    }

    /**
     * load conf file
     *
     * @param fileName conf file name
     * @throws IOException loading exception
     */
    private static void loadConfigFile(String fileName) throws IOException {
        Properties properties = new Properties();
        properties.load(new FileInputStream(new File(fileName)));
        for (Object key : properties.keySet()) {
            CFG.put((String) key, properties.get(key));
        }
    }

    /**
     * configure system property
     *
     * @param key
     * @param value
     */
    public static void setSystemProperty(String key, String value) {
        System.setProperty(key, value);
    }

    /**
     * clear system property
     *
     * @param key
     */
    public static void clearProperty(String key) {
        System.clearProperty(key);
    }

}