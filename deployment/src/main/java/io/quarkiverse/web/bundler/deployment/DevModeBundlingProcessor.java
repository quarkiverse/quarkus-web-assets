package io.quarkiverse.web.bundler.deployment;

import static io.quarkiverse.web.bundler.deployment.BundlingProcessor.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.logging.Logger;

import io.mvnpm.esbuild.BundleException;
import io.mvnpm.esbuild.Bundler;
import io.mvnpm.esbuild.Watch;
import io.mvnpm.esbuild.model.BundleOptions;
import io.mvnpm.esbuild.model.BundleResult;
import io.quarkiverse.web.bundler.deployment.items.GeneratedBundleBuildItem;
import io.quarkiverse.web.bundler.deployment.items.GeneratedEntryPointBuildItem;
import io.quarkiverse.web.bundler.deployment.items.ReadyForBundlingBuildItem;
import io.quarkiverse.web.bundler.deployment.web.GeneratedWebResourceBuildItem;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CuratedApplicationShutdownBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;
import io.quarkus.deployment.dev.RuntimeUpdatesProcessor;

public class DevModeBundlingProcessor {

    private static final Logger LOGGER = Logger.getLogger(DevModeBundlingProcessor.class);

    private static final String DEV_SERVICE_NAME = "web-bundler-dev";
    private static volatile DevServicesResultBuildItem.RunningDevService devService;
    private static final AtomicReference<Watch> watchRef = new AtomicReference<>();

    private static final AtomicReference<BundleException> bundleExceptionRef = new AtomicReference<>();

    @BuildStep(onlyIf = IsDevelopment.class)
    void watch(WebBundlerConfig config,
            ReadyForBundlingBuildItem readyForBundling,
            BuildProducer<GeneratedWebResourceBuildItem> staticResourceProducer,
            BuildProducer<GeneratedBundleBuildItem> generatedBundleProducer,
            BuildProducer<DevServicesResultBuildItem> devServices,
            BuildProducer<GeneratedEntryPointBuildItem> generatedEntryPointProducer,
            LiveReloadBuildItem liveReload,
            CuratedApplicationShutdownBuildItem shutdown) {
        if (readyForBundling == null) {
            return;
        }
        final BundlesBuildContext bundlesBuildContext = liveReload.getContextObject(BundlesBuildContext.class);
        final boolean isLiveReload = liveReload.isLiveReload();
        Watch watch = DevModeBundlingProcessor.watchRef.get();
        if (readyForBundling.started() == null) {
            // no changes
            boolean isRestartWatchNeeded = readyForBundling.useEsbuildWatch() && (watch == null || !watch.isAlive());
            if (!isRestartWatchNeeded) {
                if (watch != null && watch.isAlive()) {
                    devServices.produce(devService.toBuildItem());
                }
                final BundlesBuildContext newBundlesBuildContext = new BundlesBuildContext(readyForBundling.bundleOptions(),
                        bundlesBuildContext.bundleDistDir());
                liveReload.setContextObject(BundlesBuildContext.class, newBundlesBuildContext);
                handleBundleDistDir(config, generatedBundleProducer, staticResourceProducer,
                        bundlesBuildContext.bundleDistDir(),
                        readyForBundling.started());
                processGeneratedEntryPoints(readyForBundling.bundleOptions().workDir(),
                        generatedEntryPointProducer);
                return;
            }
        }

        if (watch != null) {
            shutdownDevService();
        }

        if (!readyForBundling.useEsbuildWatch()) {
            // We use normal bundling when esbuild watch is disabled
            final BundleResult bundleResult = bundleAndProcess(config, readyForBundling, staticResourceProducer,
                    generatedBundleProducer,
                    generatedEntryPointProducer);
            final BundlesBuildContext newBundlesBuildContext = new BundlesBuildContext(readyForBundling.bundleOptions(),
                    bundleResult.dist());
            liveReload.setContextObject(BundlesBuildContext.class, newBundlesBuildContext);
            return;
        }

        if (!isLiveReload) {
            // Only the first time
            Runnable closeTask = () -> {
                if (devService != null) {
                    shutdownDevService();
                }
            };
            shutdown.addCloseTask(closeTask, true);
        }

        try {
            watch = Bundler.watch(readyForBundling.bundleOptions(), (r) -> {
                if (watchRef.get() == null) {
                    LOGGER.error("Received a bundling event without a watchRef");
                    return;
                }
                LOGGER.debugf("New bundling event received: %s", r);
                if (!r.isSuccess()) {
                    bundleExceptionRef.set(r.bundleException());
                    if (RuntimeUpdatesProcessor.INSTANCE.getCompileProblem() != null) {
                        RuntimeUpdatesProcessor.INSTANCE.setRemoteProblem(r.bundleException());
                    }

                } else {
                    resetRemoteProblem();
                    bundleExceptionRef.set(null);
                }
                if (!watchRef.get().isAlive()) {
                    shutdownDevService();
                }
                callNoRestartChangesConsumers(r.isSuccess());
            }, false);
            watchRef.set(watch);
            devService = new DevServicesResultBuildItem.RunningDevService(
                    DEV_SERVICE_NAME, null, watch::close, new HashMap<>());
            devServices.produce(devService.toBuildItem());
            if (!watch.firstBuildResult().isSuccess()) {
                throw watch.firstBuildResult().bundleException();
            }
            final BundlesBuildContext newBundlesBuildContext = new BundlesBuildContext(readyForBundling.bundleOptions(),
                    watch.dist());
            liveReload.setContextObject(BundlesBuildContext.class, newBundlesBuildContext);
            handleBundleDistDir(config, generatedBundleProducer, staticResourceProducer, watch.dist(),
                    readyForBundling.started());
            processGeneratedEntryPoints(readyForBundling.bundleOptions().workDir(), generatedEntryPointProducer);

        } catch (IOException e) {
            shutdownDevService();
            liveReload.setContextObject(BundlesBuildContext.class, new BundlesBuildContext());
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            shutdownDevService();
            liveReload.setContextObject(BundlesBuildContext.class, new BundlesBuildContext());
            throw e;
        }
    }

    private void callNoRestartChangesConsumers(boolean isSuccess) {
        RuntimeUpdatesProcessor.INSTANCE
                .notifyExtensions(Set.of(isSuccess ? "web-bundler/build-success" : "web-bundler/build-error"));
    }

    private static void resetRemoteProblem() {
        if (RuntimeUpdatesProcessor.INSTANCE.getCompileProblem() instanceof BundleException) {
            RuntimeUpdatesProcessor.INSTANCE.setRemoteProblem(null);
        }
    }

    private void shutdownDevService() {
        LOGGER.debug("Web Bundler: shutdown Esbuild watch");
        try {
            if (devService != null) {
                devService.close();
            }
        } catch (Throwable e) {
            LOGGER.error("Failed to stop Web Bundler bundling process", e);
        } finally {
            devService = null;
            watchRef.set(null);
            bundleExceptionRef.set(null);
        }
    }

    record BundlesBuildContext(BundleOptions bundleOptions,
            Path bundleDistDir) {

        public BundlesBuildContext() {
            this(null, null);
        }
    }
}
