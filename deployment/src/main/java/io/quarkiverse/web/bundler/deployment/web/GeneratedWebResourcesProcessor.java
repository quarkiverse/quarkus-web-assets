package io.quarkiverse.web.bundler.deployment.web;

import static io.quarkus.deployment.annotations.ExecutionTime.RUNTIME_INIT;

import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import io.quarkiverse.web.bundler.deployment.WebBundlerConfig;
import io.quarkiverse.web.bundler.deployment.items.ReadyForBundlingBuildItem;
import io.quarkiverse.web.bundler.deployment.items.WebBundlerTargetDirBuildItem;
import io.quarkiverse.web.bundler.deployment.util.PathUtils;
import io.quarkiverse.web.bundler.runtime.WebBundlerResourceRecorder;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.vertx.http.deployment.RouteBuildItem;
import io.quarkus.vertx.http.deployment.spi.GeneratedStaticResourceBuildItem;
import io.quarkus.vertx.http.runtime.HttpBuildTimeConfig;

public class GeneratedWebResourcesProcessor {
    private static final Logger LOGGER = Logger.getLogger(GeneratedWebResourcesProcessor.class);
    public static final String WEB_BUNDLER_LIVE_RELOAD_PATH = "/web-bundler/live";

    @BuildStep
    public void processStaticFiles(
            List<GeneratedWebResourceBuildItem> staticResources,
            BuildProducer<GeneratedStaticResourceBuildItem> generatedStaticResourceProducer) {
        if (staticResources.isEmpty()) {
            return;
        }

        for (GeneratedWebResourceBuildItem staticResource : staticResources) {
            if (staticResource.path() != null) {
                if (Files.isRegularFile(staticResource.path())) {
                    generatedStaticResourceProducer.produce(
                            new GeneratedStaticResourceBuildItem(staticResource.publicPath(), staticResource.path()));
                }
            } else {
                generatedStaticResourceProducer.produce(
                        new GeneratedStaticResourceBuildItem(staticResource.publicPath(), staticResource.content()));
            }
        }
    }

    @BuildStep(onlyIf = IsDevelopment.class)
    @Record(RUNTIME_INIT)
    public void initChangeEventHandler(
            WebBundlerConfig config,
            WebBundlerTargetDirBuildItem targetDir,
            WebBundlerResourceRecorder recorder,
            ReadyForBundlingBuildItem readyForBundling,
            List<GeneratedWebResourceBuildItem> staticResources,
            ShutdownContextBuildItem shutdownContext,
            BuildProducer<RouteBuildItem> routes) {
        if (config.browserLiveReload() && readyForBundling != null) {
            routes.produce(RouteBuildItem.builder().route(WEB_BUNDLER_LIVE_RELOAD_PATH)
                    .handler(recorder.createChangeEventHandler(targetDir.dist().toAbsolutePath().toString(),
                            config.webRoot(),
                            staticResources.stream()
                                    .map(GeneratedWebResourceBuildItem::publicPath)
                                    .collect(Collectors.toSet()),
                            shutdownContext))
                    .build());
        }
    }

    public static String resolveFromRootPath(HttpBuildTimeConfig httpConfig, String path) {
        return PathUtils.join(httpConfig.rootPath, path);
    }
}
