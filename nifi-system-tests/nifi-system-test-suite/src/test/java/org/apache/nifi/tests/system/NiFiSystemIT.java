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
package org.apache.nifi.tests.system;

import org.apache.nifi.processor.Relationship;
import org.apache.nifi.toolkit.cli.impl.client.nifi.NiFiClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.NiFiClientConfig;
import org.apache.nifi.toolkit.cli.impl.client.nifi.NiFiClientException;
import org.apache.nifi.toolkit.cli.impl.client.nifi.impl.JerseyNiFiClient;
import org.apache.nifi.web.api.entity.ClusteSummaryEntity;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.junit.rules.Timeout;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class NiFiSystemIT {
    public static final int CLIENT_API_PORT = 5671;
    public static final String NIFI_GROUP_ID = "org.apache.nifi";
    public static final String TEST_EXTENSIONS_ARTIFACT_ID = "nifi-system-test-extensions-nar";
    public static final String TEST_PROCESSORS_PACKAGE = "org.apache.nifi.processors.tests.system";
    public static final String TEST_CS_PACKAGE = "org.apache.nifi.cs.tests.system";

    private static final Pattern FRAMEWORK_NAR_PATTERN = Pattern.compile("nifi-framework-nar-(.*?)\\.nar");
    private static final File LIB_DIR = new File("target/nifi-lib-assembly/lib");
    private static volatile String nifiFrameworkVersion = null;

    protected static final Relationship REL_SUCCESS = new Relationship.Builder()
        .name("success")
        .description("Convenience Relationship for use in tests")
        .build();

    @Rule
    public TestName name = new TestName();
    @Rule
    public Timeout defaultTimeout = new Timeout(2, TimeUnit.MINUTES);

    private NiFiClient nifiClient;
    private NiFiClientUtil clientUtil;
    private static final AtomicReference<NiFiInstance> nifiRef = new AtomicReference<>();

    @Before
    public void setup() throws IOException {
        Thread.currentThread().setContextClassLoader(ClassLoader.getSystemClassLoader());
        setupClient();

        if (nifiRef.get() == null) {
            final NiFiInstance instance = getInstanceFactory().createInstance();
            nifiRef.set(instance);
            instance.createEnvironment();
            instance.start();

            Thread.currentThread().setContextClassLoader(ClassLoader.getSystemClassLoader());

            if (instance.isClustered()) {
                waitForAllNodesConnected();
            }
        }
    }

    @AfterClass
    public static void cleanup() {
        final NiFiInstance nifi = nifiRef.get();
        nifiRef.set(null);
        if (nifi != null) {
            nifi.stop();
        }
    }

    @After
    public void teardown() throws IOException, NiFiClientException {
        try {
            if (isDestroyFlowAfterEachTest()) {
                destroyFlow();
            }
        } finally {
            if (nifiClient != null) {
                nifiClient.close();
            }
        }
    }

    protected void destroyFlow() throws NiFiClientException, IOException {
        getClientUtil().stopProcessGroupComponents("root");
        getClientUtil().disableControllerServices("root");
        getClientUtil().stopTransmitting("root");
        getClientUtil().deleteAll("root");
    }

    protected void waitForAllNodesConnected() {
        waitForAllNodesConnected(getNumberOfNodes(true));
    }

    protected void waitForAllNodesConnected(final int expectedNumberOfNodes) {
        waitForAllNodesConnected(expectedNumberOfNodes, 100L);
    }

    protected void waitForAllNodesConnected(final int expectedNumberOfNodes, final long sleepMillis) {
        final NiFiClient client = getNifiClient();

        final long maxTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60);
        while (true) {
            try {
                final ClusteSummaryEntity clusterSummary = client.getFlowClient().getClusterSummary();
                final int connectedNodeCount = clusterSummary.getClusterSummary().getConnectedNodeCount();
                if (connectedNodeCount == expectedNumberOfNodes) {
                    return;
                }

                if (System.currentTimeMillis() > maxTime) {
                    throw new RuntimeException("Waited up to 60 seconds for both nodes to connect but only " + connectedNodeCount + " nodes connected");
                }
            } catch (final Exception e) {
                e.printStackTrace();
            }

            try {
                Thread.sleep(sleepMillis);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    protected void setupClient() {
        nifiClient = createClient();
        clientUtil = new NiFiClientUtil(nifiClient, getNiFiVersion());
    }

    protected NiFiClientUtil getClientUtil() {
        return clientUtil;
    }

    protected NiFiClient createClient() {
        final NiFiClientConfig clientConfig = new NiFiClientConfig.Builder()
            .baseUrl("http://localhost:" + getClientApiPort())
            .connectTimeout(30000)
            .readTimeout(30000)
            .build();

        return new JerseyNiFiClient.Builder()
            .config(clientConfig)
            .build();
    }

    protected int getClientApiPort() {
        return CLIENT_API_PORT;
    }


    protected String getTestName() {
        return name.getMethodName();
    }

    protected NiFiClient getNifiClient() {
        Thread.currentThread().setContextClassLoader(ClassLoader.getSystemClassLoader());
        return nifiClient;
    }

    protected String getNiFiVersion() {
        final String knownVersion = nifiFrameworkVersion;
        if (knownVersion != null) {
            return knownVersion;
        }

        final File[] files = LIB_DIR.listFiles();
        for (final File file : files) {
            final String filename = file.getName();
            final Matcher matcher = FRAMEWORK_NAR_PATTERN.matcher(filename);
            if (matcher.matches()) {
                final String version = matcher.group(1);
                nifiFrameworkVersion = version;
                return version;
            }
        }

        throw new IllegalStateException("Could not determine version of NiFi");
    }

    protected NiFiInstance getNiFiInstance() {
        return nifiRef.get();
    }

    protected int getNumberOfNodes() {
        return getNumberOfNodes(true);
    }

    protected int getNumberOfNodes(final boolean includeOnlyAutoStartInstances) {
        final NiFiInstance instance = nifiRef.get();
        if (instance == null) {
            return 1;
        }

        return instance.getNumberOfNodes(includeOnlyAutoStartInstances);
    }

    protected NiFiInstanceFactory getInstanceFactory() {
        return new SpawnedStandaloneNiFiInstanceFactory(
            new InstanceConfiguration.Builder()
                .bootstrapConfig("src/test/resources/conf/default/bootstrap.conf")
                .instanceDirectory("target/standalone-instance")
                .build());
    }

    protected boolean isDestroyFlowAfterEachTest() {
        return true;
    }

    protected void waitFor(final BooleanSupplier condition) throws InterruptedException {
        while (!condition.getAsBoolean()) {
            Thread.sleep(10L);
        }
    }
}
