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
package com.alipay.sofa.ark.container;

import com.alipay.sofa.ark.api.ArkConfigs;
import com.alipay.sofa.ark.common.log.ArkLoggerFactory;
import com.alipay.sofa.ark.common.util.AssertUtils;
import com.alipay.sofa.ark.exception.ArkRuntimeException;
import com.alipay.sofa.ark.spi.argument.LaunchCommand;
import com.alipay.sofa.ark.loader.ExecutableArkBizJar;
import com.alipay.sofa.ark.loader.archive.JarFileArchive;
import com.alipay.sofa.ark.spi.archive.ExecutableArchive;
import com.alipay.sofa.ark.spi.constant.Constants;
import com.alipay.sofa.ark.spi.pipeline.PipelineContext;
import com.alipay.sofa.ark.container.service.ArkServiceContainer;
import com.alipay.sofa.ark.spi.pipeline.Pipeline;
import com.alipay.sofa.ark.bootstrap.ClasspathLauncher.ClassPathArchive;
import com.alipay.sofa.common.log.MultiAppLoggerSpaceManager;
import com.alipay.sofa.common.log.SpaceId;
import com.alipay.sofa.common.log.SpaceInfo;
import com.alipay.sofa.common.log.env.LogEnvUtils;
import com.alipay.sofa.common.log.factory.LogbackLoggerSpaceFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.alipay.sofa.common.log.Constants.LOGGING_PATH_DEFAULT;
import static com.alipay.sofa.common.log.Constants.LOG_ENCODING_PROP_KEY;
import static com.alipay.sofa.common.log.Constants.LOG_PATH;
import static com.alipay.sofa.common.log.Constants.UTF8_STR;

/**
 * Ark Container Entry
 *
 * @author ruoshan
 * @since 0.1.0
 */
public class ArkContainer {

    private ArkServiceContainer arkServiceContainer;

    private PipelineContext     pipelineContext;

    private AtomicBoolean       started           = new AtomicBoolean(false);

    private AtomicBoolean       stopped           = new AtomicBoolean(false);

    private long                start             = System.currentTimeMillis();

    /**
     * -Aclasspath or -Ajar is needed at lease. it specify the abstract executable ark archive,
     * default added by container itself
     */
    private static final int    MINIMUM_ARGS_SIZE = 1;

    public static Object main(String[] args) throws ArkRuntimeException {
        if (args.length < MINIMUM_ARGS_SIZE) {
            throw new ArkRuntimeException("Please provide suitable arguments to continue !");
        }

        try {
            LaunchCommand launchCommand = LaunchCommand.parse(args);
            if (launchCommand.isExecutedByCommandLine()) {
                ExecutableArkBizJar executableArchive = new ExecutableArkBizJar(new JarFileArchive(
                    new File(launchCommand.getExecutableArkBizJar().getFile())),
                    launchCommand.getExecutableArkBizJar());
                return new ArkContainer(executableArchive, launchCommand).start();
            } else {
                ClassPathArchive classPathArchive = new ClassPathArchive(
                    launchCommand.getEntryClassName(), launchCommand.getEntryMethodName(),
                    launchCommand.getEntryMethodDescriptor(), launchCommand.getClasspath());
                return new ArkContainer(classPathArchive, launchCommand).start();
            }
        } catch (IOException e) {
            throw new ArkRuntimeException(String.format("SOFAArk startup failed, commandline=%s",
                LaunchCommand.toString(args)), e);
        }
    }

    public ArkContainer(ExecutableArchive executableArchive) throws Exception {
        this(executableArchive, new LaunchCommand().setExecutableArkBizJar(executableArchive
            .getUrl()));
    }

    public ArkContainer(ExecutableArchive executableArchive, LaunchCommand launchCommand) {
        arkServiceContainer = new ArkServiceContainer(launchCommand.getLaunchArgs());
        pipelineContext = new PipelineContext();
        pipelineContext.setExecutableArchive(executableArchive);
        pipelineContext.setLaunchCommand(launchCommand);
    }

    /**
     * Start Ark Container
     *
     * @throws ArkRuntimeException
     * @since 0.1.0
     */
    public Object start() throws ArkRuntimeException {
        AssertUtils.assertNotNull(arkServiceContainer, "arkServiceContainer is null !");
        if (started.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    stop();
                }
            }));
            prepareArkConfig(pipelineContext.getExecutableArchive());
            reInitializeArkLogger();
            arkServiceContainer.start();
            Pipeline pipeline = arkServiceContainer.getService(Pipeline.class);
            pipeline.process(pipelineContext);

            System.out.println("Ark container started in " + (System.currentTimeMillis() - start) //NOPMD
                               + " ms.");
        }
        return this;
    }

    /**
     * Prepare to read ark conf
     * @param executableArchive
     * @throws ArkRuntimeException
     */
    public void prepareArkConfig(ExecutableArchive executableArchive) throws ArkRuntimeException {
        try {
            // Forbid to Monitoring and Management Using JMX, because it leads to conflict when setup multi spring boot app.
            ArkConfigs.setSystemProperty(Constants.SPRING_BOOT_ENDPOINTS_JMX_ENABLED,
                String.valueOf(false));
            // ignore thread class loader when loading classes and resource in log4j
            ArkConfigs.setSystemProperty(Constants.LOG4J_IGNORE_TCL, String.valueOf(true));
            // read ark conf file
            List<URL> urls = executableArchive.getProfileFiles(pipelineContext.getLaunchCommand()
                .getProfiles());
            ArkConfigs.init(urls);
        } catch (Throwable throwable) {
            throw new ArkRuntimeException(throwable);
        }
    }

    /**
     * reInitialize Ark Logger
     *
     * @throws ArkRuntimeException
     */
    public void reInitializeArkLogger() throws ArkRuntimeException {
        for (Map.Entry<SpaceId, SpaceInfo> entry : MultiAppLoggerSpaceManager.getSpacesMap()
            .entrySet()) {
            SpaceId spaceId = entry.getKey();
            SpaceInfo spaceInfo = entry.getValue();
            if (!ArkLoggerFactory.SOFA_ARK_LOGGER_SPACE.equals(spaceId.getSpaceName())) {
                continue;
            }
            LogbackLoggerSpaceFactory arkLoggerSpaceFactory = (LogbackLoggerSpaceFactory) spaceInfo
                .getAbstractLoggerSpaceFactory();
            Map<String, String> arkLogConfig = new HashMap<>();
            // set base logging.path
            arkLogConfig.put(LOG_PATH, ArkConfigs.getStringValue(LOG_PATH, LOGGING_PATH_DEFAULT));
            // set log file encoding
            arkLogConfig.put(LOG_ENCODING_PROP_KEY,
                ArkConfigs.getStringValue(LOG_ENCODING_PROP_KEY, UTF8_STR));
            // set other log config
            for (String key : ArkConfigs.keySet()) {
                if (LogEnvUtils.filterAllLogConfig(key)) {
                    arkLogConfig.put(key, ArkConfigs.getStringValue(key));
                }
            }
            arkLoggerSpaceFactory.reInitialize(arkLogConfig);
        }
    }

    /**
     * Whether Ark Container is started or not
     *
     * @return
     */
    public boolean isStarted() {
        return started.get();
    }

    /**
     * Stop Ark Container
     *
     * @throws ArkRuntimeException
     */
    public void stop() throws ArkRuntimeException {
        AssertUtils.assertNotNull(arkServiceContainer, "arkServiceContainer is null !");
        if (stopped.compareAndSet(false, true)) {
            arkServiceContainer.stop();
        }
    }

    /**
     * Whether Ark Container is running or not
     * @return
     */
    public boolean isRunning() {
        return isStarted() && !stopped.get();
    }

    /**
     * Get {@link ArkServiceContainer} of ark container
     *
     * @return
     */
    public ArkServiceContainer getArkServiceContainer() {
        return arkServiceContainer;
    }

    /**
     * Get {@link PipelineContext} of ark container
     *
     * @return
     */
    public PipelineContext getPipelineContext() {
        return pipelineContext;
    }
}