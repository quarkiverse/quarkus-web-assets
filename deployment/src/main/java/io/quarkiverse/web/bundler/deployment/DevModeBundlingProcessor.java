package io.quarkiverse.web.bundler.deployment;

import static io.quarkiverse.web.bundler.deployment.BundlingProcessor.handleBundleDistDir;
import static io.quarkiverse.web.bundler.deployment.BundlingProcessor.processGeneratedEntryPoints;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.logging.Logger;

import io.mvnpm.esbuild.BundleException;
import io.mvnpm.esbuild.Bundler;
import io.mvnpm.esbuild.Watch;
import io.mvnpm.esbuild.model.BundleOptions;
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
    private static final AtomicReference<CountDownLatch> WAITER = new AtomicReference<>();
    private static volatile long lastBundling = 0;

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
        if (isLiveReload && devService != null) {
            boolean shouldShutdownTheBroker = bundlesBuildContext == null
                    || watch == null
                    || !watch.isAlive()
                    || !Objects.equals(config, bundlesBuildContext.config());
            if (!shouldShutdownTheBroker) {
                try {
                    if (readyForBundling.started() == null) {
                        watch.updateEntries(readyForBundling.bundleOptions().entries());
                        // We should just wait for the change to happen
                        waitForBundling(readyForBundling);
                    }
                    devServices.produce(devService.toBuildItem());
                    final BundlesBuildContext newBundlesBuildContext = new BundlesBuildContext(readyForBundling.bundleOptions(),
                            config, watch.dist());
                    liveReload.setContextObject(BundlesBuildContext.class, newBundlesBuildContext);
                    handleBundleDistDir(config, generatedBundleProducer, staticResourceProducer, watch.dist(),
                            readyForBundling.started());
                    processGeneratedEntryPoints(config, readyForBundling.bundleOptions().workDir(),
                            generatedEntryPointProducer);
                } catch (IOException e) {
                    shutdownDevService();
                    liveReload.setContextObject(BundlesBuildContext.class, new BundlesBuildContext());
                    throw new UncheckedIOException(e);
                } catch (Exception e) {
                    shutdownDevService();
                    liveReload.setContextObject(BundlesBuildContext.class, new BundlesBuildContext());
                    throw e;
                }
                return;
            }
            shutdownDevService();
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
                lastBundling = Instant.now().toEpochMilli();
                if (!r.isSuccess()) {
                    bundleExceptionRef.set(r.bundleException());
                    RuntimeUpdatesProcessor.INSTANCE.setRemoteProblem(r.bundleException());
                } else {
                    resetRemoteProblem();
                    bundleExceptionRef.set(null);
                }
                if (!watchRef.get().isAlive()) {
                    shutdownDevService();
                }
                callNoRestartChangesConsumers(r.isSuccess());
                final CountDownLatch countDownLatch = WAITER.get();
                if (countDownLatch != null) {
                    countDownLatch.countDown();
                }
            }, false);
            watchRef.set(watch);
            devService = new DevServicesResultBuildItem.RunningDevService(
                    DEV_SERVICE_NAME, null, watch::stop, new HashMap<>());
            devServices.produce(devService.toBuildItem());
            if (!watch.firstBuildResult().isSuccess()) {
                throw watch.firstBuildResult().bundleException();
            }
            final BundlesBuildContext newBundlesBuildContext = new BundlesBuildContext(readyForBundling.bundleOptions(),
                    config, watch.dist());
            liveReload.setContextObject(BundlesBuildContext.class, newBundlesBuildContext);
            handleBundleDistDir(config, generatedBundleProducer, staticResourceProducer, watch.dist(),
                    readyForBundling.started());
            processGeneratedEntryPoints(config, readyForBundling.bundleOptions().workDir(), generatedEntryPointProducer);

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
        LOGGER.debug("Web Bundler: shutdownDevService");
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

    private void waitForBundling(ReadyForBundlingBuildItem readyForBundling) {
        if (readyForBundling.started() != null) {
            if (lastBundling > readyForBundling.started()) {
                LOGGER.debug("Bundling done, no need to wait");
            } else {
                final CountDownLatch latch = new CountDownLatch(1);
                final CountDownLatch existingLatch = WAITER.getAndSet(latch);
                WAITER.set(latch);
                LOGGER.info("Bundling started...");
                try {
                    latch.await();
                    LOGGER.debug("Bundling completed!");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    shutdownDevService();
                    throw new RuntimeException(e);
                } finally {
                    if (existingLatch != null) {
                        // this will always countdown the previous latch,
                        // it has no effect if it was already done
                        existingLatch.countDown();
                    }
                }
            }
        }

        final BundleException bundleException = bundleExceptionRef.get();
        if (bundleException != null) {
            shutdownDevService();
            throw bundleException;
        }

    }

    record BundlesBuildContext(BundleOptions bundleOptions,
            WebBundlerConfig config,
            Path bundleDistDir) {

        public BundlesBuildContext() {
            this(null, null, null);
        }
    }
}
