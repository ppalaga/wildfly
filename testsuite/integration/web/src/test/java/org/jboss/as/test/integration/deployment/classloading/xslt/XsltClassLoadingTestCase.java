/*
 * JBoss, Home of Professional Open Source
 * Copyright 2017, Red Hat Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.test.integration.deployment.classloading.xslt;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

import javax.xml.transform.TransformerException;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.junit.InSequence;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.as.arquillian.api.ServerSetupTask;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.Operation;
import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.protocol.StreamUtils;
import org.jboss.as.test.integration.security.common.Utils;
import org.jboss.as.test.module.util.TestModule;
import org.jboss.as.test.shared.FileUtils;
import org.jboss.as.test.shared.TestSuiteEnvironment;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * @author <a href="https://github.com/ppalaga">Peter Palaga</a>
 */
@RunWith(Arquillian.class)
@ServerSetup(XsltClassLoadingTestCase.DeploymentsSetupTask.class)
public class XsltClassLoadingTestCase {
    static class DeploymentsSetupTask implements ServerSetupTask {
        private TestModule customSaxon6Module;
        private TestModule customXalanModule;
        private TestModule testModule;

        @Override
        public void setup(ManagementClient managementClient, String containerId) throws Exception {
            testModule = new TestModule(DEPENDENCY_MODULE_NAME);
            testModule.addResource(DependencyModule.class.getSimpleName() + ".jar") //
                    .addClass(DependencyModule.class);
            testModule.create();

            customXalanModule = new TestModule(CUSTOM_XALAN_MODULE_NAME, "javax.api");
            for (File lib : XALAN_LIB_PATHS) {
                customXalanModule.addJavaArchive(lib);
            }
            customXalanModule.create();

            customSaxon6Module = new TestModule(CUSTOM_SAXON6_MODULE_NAME, "javax.api");
            for (File lib : SAXON6_LIB_PATHS) {
                customSaxon6Module.addJavaArchive(lib);
            }
            customSaxon6Module.create();

            final ModelNode composite = new ModelNode();
            composite.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.COMPOSITE);
            composite.get(ModelDescriptionConstants.OPERATION_HEADERS)
                    .get(ModelDescriptionConstants.ROLLBACK_ON_RUNTIME_FAILURE).set(false);

            final ModelNode nested = composite.get(ModelDescriptionConstants.STEPS).setEmptyList().add();
            nested.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.COMPOSITE);
            nested.get(ModelDescriptionConstants.OP_ADDR).setEmptyList();

            final ModelNode steps = nested.get(ModelDescriptionConstants.STEPS).setEmptyList();

            for (int i = 0; i < APPS.length; i++) {
                WebArchive app = APPS[i];
                final ModelNode deployOne = steps.add();
                deployOne.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.ADD);
                PathAddress path = PathAddress.pathAddress(
                        PathElement.pathElement(ModelDescriptionConstants.DEPLOYMENT, app.getName() + ".war"));
                deployOne.get(ModelDescriptionConstants.OP_ADDR).set(path.toModelNode());
                deployOne.get(ModelDescriptionConstants.ENABLED).set(true);
                deployOne.get(ModelDescriptionConstants.CONTENT).add().get(ModelDescriptionConstants.INPUT_STREAM_INDEX)
                        .set(i);
            }

            final OperationBuilder operationBuilder = OperationBuilder.create(composite, true);
            final Path warsDir = Paths.get("target/wars").toAbsolutePath();
            Files.createDirectories(warsDir);
            for (WebArchive app : APPS) {
                operationBuilder.addInputStream(app.as(ZipExporter.class).exportAsInputStream());
                app.as(ZipExporter.class).exportTo(warsDir.resolve(app.getName() + ".zip").toFile());
            }

            final ModelControllerClient client = managementClient.getControllerClient();
            final Operation operation = operationBuilder.build();
            try {
                final ModelNode overallResult = client.execute(operation);
                Assert.assertTrue(overallResult.asString(), ModelDescriptionConstants.SUCCESS
                        .equals(overallResult.get(ModelDescriptionConstants.OUTCOME).asString()));
            } finally {
                StreamUtils.safeClose(operation);
            }
        }

        @Override
        public void tearDown(ManagementClient managementClient, String containerId) throws Exception {
            testModule.remove();
            // customXalanModule.remove();
            // customSaxon6Module.remove();

            final ModelNode composite = new ModelNode();
            composite.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.COMPOSITE);
            composite.get(ModelDescriptionConstants.OPERATION_HEADERS)
                    .get(ModelDescriptionConstants.ROLLBACK_ON_RUNTIME_FAILURE).set(false);

            final ModelNode nested = composite.get(ModelDescriptionConstants.STEPS).setEmptyList().add();
            nested.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.COMPOSITE);
            nested.get(ModelDescriptionConstants.OP_ADDR).setEmptyList();

            final ModelNode steps = nested.get(ModelDescriptionConstants.STEPS).setEmptyList();

            for (int i = 0; i < APPS.length; i++) {
                WebArchive app = APPS[i];
                final ModelNode deployOne = steps.add();
                deployOne.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.REMOVE);
                PathAddress path = PathAddress.pathAddress(
                        PathElement.pathElement(ModelDescriptionConstants.DEPLOYMENT, app.getName() + ".war"));
                deployOne.get(ModelDescriptionConstants.OP_ADDR).set(path.toModelNode());
            }

            final OperationBuilder operationBuilder = OperationBuilder.create(composite, true);
            final ModelControllerClient client = managementClient.getControllerClient();
            final Operation operation = operationBuilder.build();
            try {
                final ModelNode overallResult = client.execute(operation);
                Assert.assertTrue(overallResult.asString(), ModelDescriptionConstants.SUCCESS
                        .equals(overallResult.get(ModelDescriptionConstants.OUTCOME).asString()));
            } finally {
                StreamUtils.safeClose(operation);
            }
        }

    }

    private static final WebArchive APP_DEPENDING_ON_CUSTOM_SAXON6_MODULE;

    private static final WebArchive APP_DEPENDING_ON_CUSTOM_XALAN_MODULE;

    private static final WebArchive APP_DEPENDING_ON_WF_XALAN_MODULE;

    private static final WebArchive APP_WITH_OWN_SAXON6;

    private static final WebArchive APP_WITH_OWN_XALAN;
    private static final WebArchive[] APPS;
    private static final String CUSTOM_SAXON6_MODULE_NAME;
    private static final String CUSTOM_XALAN_MODULE_NAME;

    private static final WebArchive DEFAULT_APP;
    private static final String DEPENDENCY_MODULE_NAME;

    private static Logger log = Logger.getLogger(XsltClassLoadingTestCase.class);

    private static final String[][] SAXON6_LIB_GAVS = new String[][] { //
            { "saxon", "saxon", "6.5.3" }, //
    };

    private static final File[] SAXON6_LIB_PATHS;
    private static final String WF_XALAN_MODULE_NAME = "org.apache.xalan";
    private static final String[][] XALAN_LIB_GAVS = new String[][] { //
            { "xml-apis", "xml-apis", "1.3.04" }, //
            { "xalan", "xalan", "2.7.1" }, //
            { "xalan", "serializer", "2.7.1" }, //
            { "xerces", "xercesImpl", "2.9.0" } //
    };
    private static final File[] XALAN_LIB_PATHS;
    static {
        /*
         * Let's add some random suffix to the resource names here so that we do not to cleanup after unexpected
         * failures
         */
        Random rnd = new Random();
        DEPENDENCY_MODULE_NAME = DependencyModule.class.getName() + "_" + rnd.nextInt(Integer.MAX_VALUE);
        CUSTOM_XALAN_MODULE_NAME = XsltClassLoadingTestCase.class.getName() + ".xalan_"
                + rnd.nextInt(Integer.MAX_VALUE);
        CUSTOM_SAXON6_MODULE_NAME = XsltClassLoadingTestCase.class.getName() + ".saxon6_"
                + rnd.nextInt(Integer.MAX_VALUE);

        XALAN_LIB_PATHS = gavsToFiles(XALAN_LIB_GAVS, rnd);
        SAXON6_LIB_PATHS = gavsToFiles(SAXON6_LIB_GAVS, rnd);

        final String appNamePrefix = XsltClassLoadingTestCase.class.getSimpleName();

        APP_WITH_OWN_XALAN = defaultApp(appNamePrefix + "-own-xalan-" + rnd.nextInt(Integer.MAX_VALUE))
                .addAsLibraries(XALAN_LIB_PATHS);

        DEFAULT_APP = defaultApp(appNamePrefix + "-default-" + rnd.nextInt(Integer.MAX_VALUE));
        APP_WITH_OWN_SAXON6 = defaultApp(appNamePrefix + "-own-saxon-" + rnd.nextInt(Integer.MAX_VALUE))
                .addAsLibraries(SAXON6_LIB_PATHS);

        APP_DEPENDING_ON_WF_XALAN_MODULE = defaultApp(
                appNamePrefix + "-depending-on-wf-xalan-" + rnd.nextInt(Integer.MAX_VALUE),
                new StringAsset("<jboss-deployment-structure><deployment><dependencies>\n" //
                        + "<module name=\"" + DEPENDENCY_MODULE_NAME + "\"/>\n" //
                        + "<module name=\"" + WF_XALAN_MODULE_NAME + "\" services=\"import\"/>\n" //
                        + "</dependencies></deployment></jboss-deployment-structure>"));

        APP_DEPENDING_ON_CUSTOM_XALAN_MODULE = defaultApp(
                appNamePrefix + "-depending-on-custom-xalan-" + rnd.nextInt(Integer.MAX_VALUE),
                new StringAsset("<jboss-deployment-structure><deployment><dependencies>\n" //
                        + "<module name=\"" + DEPENDENCY_MODULE_NAME + "\"/>\n" //
                        + "<module name=\"" + CUSTOM_XALAN_MODULE_NAME + "\" services=\"import\"/>\n" //
                        + "</dependencies></deployment></jboss-deployment-structure>"));

        APP_DEPENDING_ON_CUSTOM_SAXON6_MODULE = defaultApp(
                appNamePrefix + "-depending-on-custom-saxon6-" + rnd.nextInt(Integer.MAX_VALUE),
                new StringAsset("<jboss-deployment-structure><deployment><dependencies>\n" //
                        + "<module name=\"" + DEPENDENCY_MODULE_NAME + "\"/>\n" //
                        + "<module name=\"javax.api\"/>\n" //
                        + "<module name=\"" + CUSTOM_SAXON6_MODULE_NAME + "\" services=\"import\"/>\n" //
                        + "</dependencies></deployment></jboss-deployment-structure>"));

        APPS = new WebArchive[] { APP_WITH_OWN_XALAN, DEFAULT_APP, APP_WITH_OWN_SAXON6,
                APP_DEPENDING_ON_WF_XALAN_MODULE, APP_DEPENDING_ON_CUSTOM_XALAN_MODULE,
                APP_DEPENDING_ON_CUSTOM_SAXON6_MODULE };

    }

    private static WebArchive defaultApp(String name) {
        return defaultApp(name, Utils.getJBossDeploymentStructure(DEPENDENCY_MODULE_NAME));
    }

    private static WebArchive defaultApp(String name, Asset jbossDeploymentStructure) {
        JavaArchive sameLoaderJar = ShrinkWrap.create(JavaArchive.class) //
                .addClass(SameModule.class);

        WebArchive war = ShrinkWrap.create(WebArchive.class, name) //
                .addAsManifestResource(jbossDeploymentStructure, "jboss-deployment-structure.xml") //
                .addClass(TransformerServlet.class) //
                .addClass(FileUtils.class).addPackage(Assert.class.getPackage())
                .addAsResource(XsltClassLoadingTestCase.class.getResource("transform.xsl"), "transform.xsl") //
                .addAsResource(XsltClassLoadingTestCase.class.getResource("input.xml"), "input.xml") //
                .addAsResource(XsltClassLoadingTestCase.class.getResource("output.xml"), "output.xml") //
                .addAsLibrary(sameLoaderJar);
        return war;
    }

    @Deployment // needed because otherwise Arquillian does not call the DeploymentsSetupTask
    public static Archive<?> dummy() {
        return ShrinkWrap.create(WebArchive.class);
    }

    private static File[] gavsToFiles(String[][] gavs, Random rnd) {
        File[] result = new File[gavs.length];
        Path libsDir = Paths.get("target/libs").toAbsolutePath();
        try {
            Files.createDirectories(libsDir);
            for (int i = 0; i < gavs.length; i++) {
                String[] gav = gavs[i];
                URL url = new URL("http://repo2.maven.org/maven2/" + gav[0] + "/" + gav[1] + "/" + gav[2] + "/" + gav[1]
                        + "-" + gav[2] + ".jar");
                Path out = libsDir.resolve(gav[1] + "-" + rnd.nextInt(Integer.MAX_VALUE) + ".jar");
                try (InputStream in = url.openStream()) {
                    Files.copy(in, out);
                }
                result[i] = out.toFile();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    private static String get(String appName, String factoryClass) throws IOException, TransformerException {
        try (final CloseableHttpClient httpClient = HttpClients.createDefault()) {
            final String requestURL = TestSuiteEnvironment.getHttpUrl().toString() + "/" + appName + "/transformer"
                    + (factoryClass == null ? "" : "?factory=" + factoryClass);
            log.infof("get " + requestURL);
            final HttpGet request = new HttpGet(requestURL);
            final HttpResponse response = httpClient.execute(request);
            final int statusCode = response.getStatusLine().getStatusCode();
            Assert.assertEquals(200, statusCode);
            final HttpEntity entity = response.getEntity();
            Assert.assertNotNull("Response message from servlet was null", entity);
            return EntityUtils.toString(entity);
        }
    }

    @Test
    @RunAsClient
    @InSequence(1)
    public void default_() throws IOException, TransformerException {
        final String actual = get(DEFAULT_APP.getName(), null);
        log.debugf("Hoping for a containers's default transformer, got %s", actual);
        Assert.assertEquals("org.apache.xalan.xsltc.trax.TransformerImpl", actual);
    }

    @Test
    @RunAsClient
    @InSequence(5)
    public void dependingOnCustomSaxon6Module() throws IOException, TransformerException {
        final String actual = get(APP_DEPENDING_ON_CUSTOM_SAXON6_MODULE.getName(), null);
        log.debugf("Hoping for a Saxon transformer, got %s", actual);
        Assert.assertEquals("com.icl.saxon.Controller", actual);
    }

    @Test
    @RunAsClient
    @InSequence(6)
    public void dependingOnCustomSaxon6ModuleWithExplicitFactory() throws IOException, TransformerException {
        final String actual = get(APP_DEPENDING_ON_CUSTOM_SAXON6_MODULE.getName(),
                "com.icl.saxon.TransformerFactoryImpl");
        log.debugf("Hoping for a Saxon transformer, got %s", actual);
        Assert.assertEquals("com.icl.saxon.Controller", actual);
    }

    @Test
    @RunAsClient
    @InSequence(4)
    public void dependingOnCustomXalanModule() throws IOException, TransformerException {
        final String actual = get(APP_DEPENDING_ON_CUSTOM_XALAN_MODULE.getName(), null);
        log.debugf("Hoping for a Xalan transformer, got %s", actual);

        // The transformatio is expected to fail because of https://issues.apache.org/jira/browse/XALANJ-2535
        Assert.assertTrue("Expected 'javax.xml.transform.TransformerException: null ...', but found " + actual,
                actual.startsWith("javax.xml.transform.TransformerException: null"));
    }

    @Test
    @RunAsClient
    @InSequence(3)
    public void dependingOnWfXalanModule() throws IOException, TransformerException {
        final String actual = get(APP_DEPENDING_ON_WF_XALAN_MODULE.getName(), null);
        log.debugf("Hoping for a Xalan transformer, got %s", actual);
        Assert.assertEquals("org.apache.xalan.xsltc.trax.TransformerImpl", actual);
    }

    @Test
    @RunAsClient
    @InSequence(2)
    public void ownSaxon6() throws IOException, TransformerException {
        final String actual = get(APP_WITH_OWN_SAXON6.getName(), null);
        log.debugf("Hoping for a Saxon transformer, got %s", actual);
        Assert.assertEquals("com.icl.saxon.Controller", actual);
    }

    @Test
    @RunAsClient
    @InSequence(0)
    public void ownXalan() throws IOException, TransformerException {
        final String actual = get(APP_WITH_OWN_XALAN.getName(), null);
        log.debugf("Hoping for a Xalan transformer, got %s", actual);
        Assert.assertEquals("org.apache.xalan.transformer.TransformerImpl", actual);
    }

}
