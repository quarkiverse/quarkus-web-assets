package io.quarkiverse.web.bundler.deployment;

import static io.quarkiverse.web.bundler.deployment.util.PathUtils.addTrailingSlash;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import io.quarkiverse.web.bundler.deployment.WebBundlerConfig.EntryPointConfig;
import io.quarkiverse.web.bundler.deployment.items.BundleConfigAssetsBuildItem;
import io.quarkiverse.web.bundler.deployment.items.BundleWebAsset;
import io.quarkiverse.web.bundler.deployment.items.BundleWebAsset.BundleType;
import io.quarkiverse.web.bundler.deployment.items.EntryPointBuildItem;
import io.quarkiverse.web.bundler.deployment.items.ProjectResourcesScannerBuildItem;
import io.quarkiverse.web.bundler.deployment.items.ProjectResourcesScannerBuildItem.Scanner;
import io.quarkiverse.web.bundler.deployment.items.QuteTagsBuildItem;
import io.quarkiverse.web.bundler.deployment.items.WebAsset;
import io.quarkus.deployment.ApplicationArchive;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.maven.dependency.ResolvedDependency;

class BundleWebAssetsScannerProcessor {

    private static final Logger LOGGER = Logger.getLogger(BundleWebAssetsScannerProcessor.class);

    private static final String FEATURE = "web-bundler";
    public static final String MAIN_ENTRYPOINT_KEY = "main";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    ProjectResourcesScannerBuildItem initScanner(ApplicationArchivesBuildItem applicationArchives,
            CurateOutcomeBuildItem curateOutcome) {
        Set<ApplicationArchive> allApplicationArchives = applicationArchives.getAllApplicationArchives();
        List<ResolvedDependency> extensionArtifacts = curateOutcome.getApplicationModel().getDependencies().stream()
                .filter(Dependency::isRuntimeExtensionArtifact).collect(Collectors.toList());
        return new ProjectResourcesScannerBuildItem(allApplicationArchives, extensionArtifacts);
    }

    @BuildStep
    void collect(ProjectResourcesScannerBuildItem scanner,
            BuildProducer<EntryPointBuildItem> bundles,
            BuildProducer<QuteTagsBuildItem> quteTagsAssets,
            BuildProducer<BundleConfigAssetsBuildItem> bundleConfigAssets,
            BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles,
            WebBundlerConfig config,
            LiveReloadBuildItem liveReload)
            throws IOException {
        watchedFiles.produce(HotDeploymentWatchedFileBuildItem.builder()
                .setLocationPredicate(s -> s.startsWith(config.webRoot())).setRestartNeeded(true).build());
        Map<String, EntryPointConfig> entryPointsConfig = new HashMap<>(config.bundle());
        if (!config.bundle().containsKey("app")) {
            entryPointsConfig.put("app", new ConfiguredEntryPoint("app", "app", MAIN_ENTRYPOINT_KEY));
        }
        final WebAssetsLookupDevContext devContext = liveReload.getContextObject(WebAssetsLookupDevContext.class);
        if (liveReload.isLiveReload()
                && devContext != null
                && !hasChanged(config, liveReload, c -> isBundleFile(config, c, entryPointsConfig))) {
            // Project WebAssets shouldn't be changed even if the file is changed as content is not stored
            // WebAsset from dependencies means we should do a new scan
            LOGGER.debug("Web bundler scan not needed for live reload");
            produceWebAssets(bundles, quteTagsAssets, bundleConfigAssets, devContext, true);
            return;
        }
        LOGGER.debug("Web bundler scan started");
        final List<Scanner> quteTagsAssetsScanners = new ArrayList<>();
        final List<Scanner> bundleConfigAssetsScanners = new ArrayList<>();

        final Map<String, List<BundleWebAsset>> bundleAssets = new HashMap<>();
        for (Map.Entry<String, EntryPointConfig> e : entryPointsConfig.entrySet()) {
            if (e.getValue().enabled()) {
                final String entryPointKey = e.getValue().effectiveKey(e.getKey());
                bundleAssets.putIfAbsent(entryPointKey, new ArrayList<>());
                final String dirFromWebRoot = config.fromWebRoot(e.getValue().effectiveDir(e.getKey()));

                if (e.getValue().quteTags()) {
                    quteTagsAssetsScanners.add(new Scanner(dirFromWebRoot,
                            "glob:**.html", config.charset()));
                }
                // The regex is for all files but .html
                final List<WebAsset> assets = scanner.scan(dirFromWebRoot, "regex:^(.(?!\\.html$))*$",
                        config.charset());
                final Optional<WebAsset> entryPoint = assets.stream()
                        .filter(w -> w.resourceName().startsWith(addTrailingSlash(dirFromWebRoot) + "index."))
                        .findAny();
                for (WebAsset webAsset : assets) {
                    BundleType bundleType = entryPoint
                            .map(ep -> webAsset.equals(ep) ? BundleType.ENTRYPOINT : BundleType.MANUAL)
                            .orElse(BundleType.AUTO);
                    bundleAssets.get(entryPointKey).add(new BundleWebAsset(webAsset, bundleType));
                }
            }
        }

        bundleConfigAssetsScanners.add(new Scanner(config.webRoot(), "glob:tsconfig.json", config.charset()));

        final WebAssetsLookupDevContext context = new WebAssetsLookupDevContext(
                bundleAssets,
                scanner.scan(quteTagsAssetsScanners),
                scanner.scan(bundleConfigAssetsScanners));
        produceWebAssets(bundles, quteTagsAssets, bundleConfigAssets, context, false);
        liveReload.setContextObject(WebAssetsLookupDevContext.class, context);
    }

    private static boolean isBundleFile(WebBundlerConfig config, String changedResource,
            Map<String, EntryPointConfig> entryPointsConfig) {
        return config.fromWebRoot("tsconfig.json").equals(changedResource)
                || entryPointsConfig.entrySet().stream().map(e -> config.fromWebRoot(e.getValue().effectiveDir(e.getKey())))
                        .anyMatch(changedResource::startsWith);
    }

    static boolean hasChanged(WebBundlerConfig config,
            LiveReloadBuildItem liveReload,
            Predicate<String> predicate) {
        // Let's just check if the file changed is in the webRoot
        return liveReload.getChangedResources().stream().anyMatch(c -> c.startsWith(config.webRoot()) && predicate.test(c));
    }

    void produceWebAssets(BuildProducer<EntryPointBuildItem> bundles,
            BuildProducer<QuteTagsBuildItem> quteTagsAssets,
            BuildProducer<BundleConfigAssetsBuildItem> bundleConfigAssets,
            WebAssetsLookupDevContext context,
            boolean checkIfExists) {
        for (Map.Entry<String, List<BundleWebAsset>> e : context.bundleAssets().entrySet()) {
            produceWebAssetsWithCheck(checkIfExists, e.getValue(),
                    webAssets -> bundles.produce(new EntryPointBuildItem(e.getKey(), webAssets)));
        }

        produceWebAssetsWithCheck(checkIfExists, context.bundleConfigWebAssets(),
                webAssets -> bundleConfigAssets.produce(new BundleConfigAssetsBuildItem(webAssets)));

        produceWebAssetsWithCheck(checkIfExists, context.quteWebAssets(),
                webAssets -> quteTagsAssets.produce(new QuteTagsBuildItem(webAssets)));
    }

    private static <T extends WebAsset> void produceWebAssetsWithCheck(boolean checkIfExists, List<T> e,
            Consumer<List<T>> consumer) {
        final List<T> webAssets = checkIfExists ? checkWebAssets(e) : e;
        if (!webAssets.isEmpty()) {
            consumer.accept(webAssets);
        }
    }

    private static <T extends WebAsset> List<T> checkWebAssets(List<T> webAssets) {
        return webAssets.stream().filter(w -> w.filePath().isPresent() && Files.isRegularFile(w.filePath().get()))
                .collect(
                        Collectors.toList());
    }

    record WebAssetsLookupDevContext(Map<String, List<BundleWebAsset>> bundleAssets,
            List<WebAsset> quteWebAssets, List<WebAsset> bundleConfigWebAssets) {

        public List<WebAsset> allWebAssets() {
            final ArrayList<WebAsset> all = new ArrayList<>();
            all.addAll(quteWebAssets);
            all.addAll(bundleConfigWebAssets);
            bundleAssets.values().forEach(all::addAll);
            return all;
        }
    }

}
