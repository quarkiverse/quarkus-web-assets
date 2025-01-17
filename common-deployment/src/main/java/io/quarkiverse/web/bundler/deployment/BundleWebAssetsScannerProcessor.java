package io.quarkiverse.web.bundler.deployment;

import static io.quarkiverse.web.bundler.deployment.WebBundlerConfig.MAIN_ENTRYPOINT_KEY;
import static io.quarkiverse.web.bundler.deployment.util.PathUtils.addTrailingSlash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import io.quarkiverse.web.bundler.deployment.WebBundlerConfig.EntryPointConfig;
import io.quarkiverse.web.bundler.deployment.items.*;
import io.quarkiverse.web.bundler.deployment.items.BundleWebAsset.BundleType;
import io.quarkiverse.web.bundler.deployment.items.EntryPointBuildItem.EntryPoint;
import io.quarkiverse.web.bundler.deployment.items.ProjectResourcesScannerBuildItem.Scanner;
import io.quarkiverse.web.bundler.deployment.util.PathUtils;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.*;

class BundleWebAssetsScannerProcessor {

    private static final Logger LOGGER = Logger.getLogger(BundleWebAssetsScannerProcessor.class);
    private static final String FEATURE = "web-bundler";

    public static AtomicBoolean SYMLINK_AVAILABLE = new AtomicBoolean(true);

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void collect(ProjectResourcesScannerBuildItem scanner,
            BuildProducer<EntryPointBuildItem> bundles,
            BuildProducer<BundleConfigAssetsBuildItem> bundleConfigAssets,
            BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles,
            WebBundlerConfig config,
            LiveReloadBuildItem liveReload)
            throws IOException {

        final WebAssetsLookupDevContext devContext = liveReload.getContextObject(WebAssetsLookupDevContext.class);
        if (liveReload.isLiveReload()
                && devContext != null
                && WebBundlerConfig.isEqual(config, devContext.config())
                && !scanner.hasWebStuffChanged(liveReload.getChangedResources())) {
            // Project WebAssets shouldn't be changed even if the file is changed as content is not stored
            LOGGER.debug("Web Bundler scan - Bundles: no change detected");
            produceWebAssets(bundles, bundleConfigAssets, devContext, true);
            return;
        }

        LOGGER.debug("Web Bundler scan - Bundles: start");

        final List<Scanner> bundleConfigAssetsScanners = new ArrayList<>();

        final Map<String, EntryPoint> entryPoints = new HashMap<>();
        for (Map.Entry<String, EntryPointConfig> e : config.bundleWithDefault().entrySet()) {
            if (e.getValue().enabled()) {
                final String entryPointKey = e.getValue().effectiveKey(e.getKey());
                final String dir = e.getValue().effectiveDir(e.getKey());
                entryPoints.putIfAbsent(entryPointKey, new EntryPoint(entryPointKey, dir, new ArrayList<>()));

                // The regex is for all files but .html
                final List<WebAsset> assets = scanner.scan(dir, "regex:^(.(?!\\.html$))*$",
                        config.charset());
                final Optional<WebAsset> entryPoint = assets.stream()
                        .filter(w -> w.relativePath().startsWith(addTrailingSlash(dir) + "index."))
                        .findAny();
                for (WebAsset webAsset : assets) {
                    BundleType bundleType = entryPoint
                            // If it's not the entry point we consider it as a manual asset (imported by the entry point)
                            .map(ep -> webAsset.equals(ep) ? BundleType.INDEX : BundleType.MANUAL)
                            // When there is no entry point we consider it as a auto asset unless it's a sass import file (_*.sass)
                            .orElse(isImportSassFile(webAsset.relativePath()) ? BundleType.MANUAL : BundleType.AUTO);
                    entryPoints.get(entryPointKey).assets().add(new BundleWebAsset(webAsset, bundleType));
                }
            }
        }

        if (entryPoints.size() == 1 && entryPoints.get(MAIN_ENTRYPOINT_KEY) != null
                && entryPoints.get(MAIN_ENTRYPOINT_KEY).assets().isEmpty()) {
            // Let's create an empty app javascript when there is nothing provided
            entryPoints.get(MAIN_ENTRYPOINT_KEY)
                    .assets().add(new BundleWebAsset(new ContentWebAsset(PathUtils.join(config.webRoot(), "app/app.js"),
                            "".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8), BundleType.AUTO));
        }

        bundleConfigAssetsScanners.add(new Scanner("glob:tsconfig.json", config.charset()));

        final WebAssetsLookupDevContext context = new WebAssetsLookupDevContext(
                config,
                entryPoints,
                scanner.scan(bundleConfigAssetsScanners));
        produceWebAssets(bundles, bundleConfigAssets, context, false);
        liveReload.setContextObject(WebAssetsLookupDevContext.class, context);
        LOGGER.debugf("Web Bundler scan - Bundles: %d entrypoints found", entryPoints.size());
    }

    private static boolean isImportSassFile(String resourceName) {
        final String fileName = Path.of(resourceName).getFileName().toString();
        return fileName.startsWith("_") && isSassFile(fileName);
    }

    private static boolean isSassFile(String fileName) {
        String lc = fileName.toLowerCase();
        return lc.endsWith(".scss") || lc.endsWith(".sass");
    }

    void produceWebAssets(BuildProducer<EntryPointBuildItem> bundles,
            BuildProducer<BundleConfigAssetsBuildItem> bundleConfigAssets,
            WebAssetsLookupDevContext context,
            boolean checkIfExists) {
        for (EntryPoint e : context.entryPoints().values()) {
            produceWebAssetsWithCheck(checkIfExists, e.assets(),
                    webAssets -> {
                        bundles.produce(new EntryPointBuildItem(new EntryPoint(e.key(), e.dir(), webAssets)));
                    });
        }

        produceWebAssetsWithCheck(checkIfExists, context.bundleConfigWebAssets(),
                webAssets -> bundleConfigAssets.produce(new BundleConfigAssetsBuildItem(webAssets)));

    }

    private static <T extends WebAsset> void produceWebAssetsWithCheck(boolean checkIfExists, List<T> e,
            Consumer<List<T>> consumer) {
        final List<T> webAssets = checkIfExists ? checkWebAssets(e) : e;
        if (!webAssets.isEmpty()) {
            consumer.accept(webAssets);
        }
    }

    private static <T extends WebAsset> List<T> checkWebAssets(List<T> webAssets) {
        return webAssets.stream().filter(w -> w.path().isPresent() && Files.isRegularFile(w.path().get()))
                .collect(
                        Collectors.toList());
    }

    record WebAssetsLookupDevContext(WebBundlerConfig config, Map<String, EntryPoint> entryPoints,
            List<WebAsset> bundleConfigWebAssets) {

    }

}
