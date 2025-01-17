package io.quarkiverse.web.bundler.deployment;

import static io.quarkiverse.web.bundler.deployment.util.PathUtils.join;
import static io.quarkiverse.web.bundler.deployment.util.PathUtils.prefixWithSlash;
import static java.util.function.Predicate.not;

import java.nio.charset.Charset;
import java.util.*;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.runtime.annotations.ConfigDocDefault;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import io.smallrye.config.WithParentName;

@ConfigMapping(prefix = "quarkus.web-bundler")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface WebBundlerConfig {

    String MAIN_ENTRYPOINT_KEY = "main";
    String APP_KEY = "app";

    /**
     * The directory in the resources which serves as root for the web assets
     */
    @WithDefault("web")
    String webRoot();

    default String prefixWithWebRoot(String dir) {
        return join(webRoot(), dir);
    }

    /**
     * The directory in the project root dir which contains web assets (relative to the project root)
     */
    @WithDefault("web")
    Optional<String> projectWebDir();

    /**
     * Bundle entry points config for scripts and styles
     */
    @ConfigDocDefault("if not overridden, by default, 'app' directory will be bundled with 'main' as entry point key")
    Map<String, EntryPointConfig> bundle();

    default Map<String, EntryPointConfig> bundleWithDefault() {
        if (bundle().containsKey(APP_KEY)) {
            return bundle();
        }
        Map<String, EntryPointConfig> conf = new HashMap<>(bundle());
        conf.put(APP_KEY, new ConfiguredEntryPoint(APP_KEY, APP_KEY, MAIN_ENTRYPOINT_KEY));
        return conf;
    }

    /**
     * Resources located in {quarkus.web-bundler.web-root}/{quarkus.web-bundler.static} will be served by Quarkus.
     * This directory path is also used as prefix for serving
     * (e.g. {quarkus.web-bundler.web-root}/static/foo.png will be served on {quarkus.http.root-path}/static/foo.png)
     */
    @WithName("static")
    @WithDefault("static")
    String staticDir();

    /**
     * When configured with an internal path (e.g. 'foo/bar'), Bundle files will be served on this path by Quarkus (prefixed by
     * {quarkus.http.root-path}).
     * When configured with an external URL (e.g. 'https://my.cdn.org/'), Bundle files will NOT be served by Quarkus
     * and all resolved paths in the bundle and mapping will automatically point to this url (a CDN for example).
     */
    @WithDefault("static/bundle")
    String bundlePath();

    /**
     * Configure bundling options
     */
    BundlingConfig bundling();

    /**
     * Configure how dependencies are collected
     */
    WebDependenciesConfig dependencies();

    /**
     * Configure browser live-reload in dev-mode.
     * With live-reload, changes in the web-app are automatically
     * triggering a reload of the page in the browser.
     * When changing styles, the changes is applied directly (without reload).
     */
    @WithDefault("true")
    boolean browserLiveReload();

    /**
     * When enabled, Quarkus will create redirections from {bundlePath}/{entryPointKey}.{js,css} to the corresponding file
     * containing the unique hash.
     * This is useful for fixed external access to the bundle files (fullstack microservices).
     */
    @WithDefault("false")
    Boolean bundleRedirect();

    /**
     * The default charset
     */
    @WithDefault("UTF-8")
    Charset charset();

    default String httpRootPath() {
        Config allConfig = ConfigProvider.getConfig();
        final String rootPath = allConfig.getOptionalValue("quarkus.http.root-path", String.class)
                .orElse("/");
        return prefixWithSlash(rootPath);
    }

    default String publicBundlePath() {
        return isExternalBundlePath() ? bundlePath() : join(httpRootPath(), bundlePath());
    }

    default boolean isExternalBundlePath() {
        return bundlePath().matches("^https?://.*");
    }

    default boolean shouldQuarkusServeBundle() {
        return !isExternalBundlePath();
    }

    interface BundlingConfig {
        /**
         * Enable or disable bundle splitting (https://esbuild.github.io/api/#splitting)
         * Code shared between multiple entry points is split off into a separate shared file (chunk) that both entry points
         * import
         */
        @WithDefault("true")
        Boolean splitting();

        /**
         * The config for esbuild loaders https://esbuild.github.io/content-types/
         */
        LoadersConfig loaders();

        /**
         * This defines the list of external paths for esbuild (https://esbuild.github.io/api/#external).
         * Instead of being bundled, the import will be preserved.
         */
        @ConfigDocDefault("{quarkus.http.root-path}static/*")
        Optional<List<String>> external();

        /**
         * Configuration for source-map generation (https://esbuild.github.io/api/#sourcemap)
         */
        @WithDefault("linked")
        String sourceMap();

        /**
         * List of environments for the bundle
         */
        Map<String, String> envs();

        default Map<String, String> safeEnvs() {
            if (envs().isEmpty()) {
                return Map.of();
            }
            return envs()
                    .entrySet()
                    .stream()
                    .collect(Collectors.toMap(e -> safeKey(e.getKey()), e -> "'" + safeValue(e.getValue()) + "'"));
        }

        static String safeKey(String v) {
            return v.replaceAll("^[^a-zA-Z_$]|[^0-9a-zA-Z_$]", "_");
        }

        static String safeValue(String v) {
            if (v.contains("'")) {
                throw new IllegalArgumentException("Single quote ' must not be used in bundling environment values");
            }
            return v;
        }

        default boolean sourceMapEnabled() {
            return "linked".equalsIgnoreCase(sourceMap())
                    || "true".equalsIgnoreCase(sourceMap())
                    || "yes".equalsIgnoreCase(sourceMap());
        }

        static boolean isEqual(BundlingConfig c1, BundlingConfig c2) {

            if (c1 == c2) {
                return true;
            }
            if (c1 == null || c2 == null) {
                return false;
            }

            return Objects.equals(c1.splitting(), c2.splitting())
                    && LoadersConfig.isEqual(c1.loaders(), c2.loaders())
                    && Objects.equals(c1.external(), c2.external())
                    && Objects.equals(c1.envs(), c2.envs())
                    && Objects.equals(c1.sourceMap(), c2.sourceMap());
        }

    }

    interface WebDependenciesConfig {

        /**
         * Path to the node_modules directory (relative to the project root).
         */
        @ConfigDocDefault("node_modules will be in the build/target directory")
        Optional<String> nodeModules();

        /**
         * Disable this option to allow using runtime web dependencies.
         * When a runtime scope web dependency is used, the dependency will be present in the target app and served at runtime.
         * When a compile only scope web dependency is used, the dependency will only be used at build time and will not be
         * present in the target app.
         * <p>
         * WARNING: Maven compile scope is considered as a runtime scope, use 'provided' for compile only. On Gradle,
         * 'compileOnly' is compile only.
         */
        @WithDefault("true")
        boolean compileOnly();

        /**
         * Auto import configuration
         */
        AutoImportConfig autoImport();

        static boolean isEqual(WebDependenciesConfig c1, WebDependenciesConfig c2) {

            if (c1 == c2) {
                return true;
            }
            if (c1 == null || c2 == null) {
                return false;
            }

            return Objects.equals(c1.nodeModules(), c2.nodeModules())
                    && Objects.equals(c1.compileOnly(), c2.compileOnly())
                    && AutoImportConfig.isEqual(c1.autoImport(), c2.autoImport());
        }

    }

    interface AutoImportConfig {

        enum Mode {
            ALL,
            STYLES,
            NONE
        }

        /**
         * Enable or disable auto-import of web dependencies:
         * all: auto-import all web dependencies (scripts and styles)
         * styles: auto-import only styles web dependencies (scss, sass, css)
         * none: disable auto-import
         * <p>
         * ** Only direct dependencies are auto-imported, not transitive ones.**
         * <p>
         * This is using the dependencies package.json (module, main, style, scss, saas fields) to detect the presence of source
         * scripts and styles:
         * - For all libraries enriching your html experience (htmx, hypercript, lazyload, ...), you don't necessarily need a
         * script, auto-import is a comfort.
         * - For styling libraries (Bootstrap, Semantic, ...), you most likely want to always auto-import the styles.
         * - For other web libraries (React, Vue, Lit, ...), you will import everything manually in your app scripts.
         */
        @WithDefault("none")
        @WithParentName
        Mode mode();

        default boolean isEnabled() {
            return mode() != Mode.NONE;
        }

        static boolean isEqual(AutoImportConfig c1, AutoImportConfig c2) {

            if (c1 == c2) {
                return true;
            }
            if (c1 == null || c2 == null) {
                return false;
            }

            return Objects.equals(c1.mode(), c2.mode());
        }
    }

    interface LoadersConfig {

        /**
         * Configure the file extensions using the js loader: https://esbuild.github.io/content-types/#javascript
         */
        @WithDefault("js,cjs,mjs")
        Optional<Set<String>> js();

        /**
         * Configure the file extensions using the jsx loader: https://esbuild.github.io/content-types/#jsx
         */
        @WithDefault("jsx")
        Optional<Set<String>> jsx();

        /**
         * Configure the file extensions using the tsx loader: https://esbuild.github.io/content-types/#jsx
         */
        @WithDefault("tsx")
        Optional<Set<String>> tsx();

        /**
         * Configure the file extensions using the ts loader: https://esbuild.github.io/content-types/#typescript
         */
        @WithDefault("ts,mts,cts")
        Optional<Set<String>> ts();

        /**
         * Configure the file extensions using the css loader: https://esbuild.github.io/content-types/#css
         */
        @WithDefault("css")
        Optional<Set<String>> css();

        /**
         * Configure the file extensions using the local-css loader: https://esbuild.github.io/content-types/#css
         */
        @WithDefault(".module.css")
        Optional<Set<String>> localCss();

        /**
         * Configure the file extensions using the global-css loader: https://esbuild.github.io/content-types/#css
         */
        Optional<Set<String>> globalCss();

        /**
         * Configure the file extensions using the file loader: https://esbuild.github.io/content-types/#file
         * This loader will copy the file to the output directory and embed the file name into the bundle as a string.
         */
        @WithDefault("aac,abw,arc,avif,avi,azw,bin,bmp,bz,bz2,cda,csv,yaml,yml,doc,docx,eot,epub,gz,gif,htm,html,ico,ics,jar,jpeg,jpg,jsonld,mid,midi,mp3,mp4,mpeg,mpkg,odp,ods,odt,oga,ogv,ogx,opus,otf,png,pdf,ppt,pptx,rar,rtf,svg,tar,tif,tiff,ttf,vsd,wav,weba,webm,webp,woff,woff2,xhtml,xls,xlsx,xml,xul,zip,3gp,3g2,7z")
        Optional<Set<String>> file();

        /**
         * Configure the file extensions using the copy loader: https://esbuild.github.io/content-types/#copy
         */
        Optional<Set<String>> copy();

        /**
         * Configure the file extensions using the base64 loader: https://esbuild.github.io/content-types/#base64
         */
        Optional<Set<String>> base64();

        /**
         * Configure the file extensions using the binary loader: https://esbuild.github.io/content-types/#binary
         */
        Optional<Set<String>> binary();

        /**
         * Configure the file extensions using the dataurl loader: https://esbuild.github.io/content-types/#data-url
         */
        Optional<Set<String>> dataUrl();

        /**
         * Configure the file extensions using the empty loader: https://esbuild.github.io/content-types/#empty-file
         */
        Optional<Set<String>> empty();

        /**
         * Configure the file extensions using the text loader: https://esbuild.github.io/content-types/#text
         */
        @WithDefault("txt")
        Optional<Set<String>> text();

        /**
         * Configure the file extensions using the json loader: https://esbuild.github.io/content-types/#json
         */
        @WithDefault("json")
        Optional<Set<String>> json();

        static boolean isEqual(LoadersConfig c1, LoadersConfig c2) {

            if (c1 == c2) {
                return true;
            }
            if (c1 == null || c2 == null) {
                return false;
            }

            return Objects.equals(c1.js(), c2.js())
                    && Objects.equals(c1.jsx(), c2.jsx())
                    && Objects.equals(c1.tsx(), c2.tsx())
                    && Objects.equals(c1.ts(), c2.ts())
                    && Objects.equals(c1.css(), c2.css())
                    && Objects.equals(c1.localCss(), c2.localCss())
                    && Objects.equals(c1.globalCss(), c2.globalCss())
                    && Objects.equals(c1.file(), c2.file())
                    && Objects.equals(c1.copy(), c2.copy())
                    && Objects.equals(c1.base64(), c2.base64())
                    && Objects.equals(c1.binary(), c2.binary())
                    && Objects.equals(c1.dataUrl(), c2.dataUrl())
                    && Objects.equals(c1.empty(), c2.empty())
                    && Objects.equals(c1.text(), c2.text())
                    && Objects.equals(c1.json(), c2.json());
        }

    }

    interface EntryPointConfig {

        /**
         * Enable or disable this entry point.
         * You can use this to use the map key as key and dir for this entry point.
         */
        @WithParentName
        @WithDefault("true")
        boolean enabled();

        /**
         * The directory for this entry point under the web root.
         */
        @ConfigDocDefault("the bundle map key")
        Optional<String> dir();

        /**
         * The key for this entry point (use the same key as another to bundle them together).
         */
        @ConfigDocDefault("the bundle map key")
        Optional<String> key();

        /**
         * Indicate if this directory contains qute tags (as .html files)
         * This is only available if the Quarkus Qute extension is in the project.
         */
        @WithDefault("false")
        boolean quteTags();

        default String effectiveDir(String mapKey) {
            return dir().filter(not(String::isBlank)).orElse(mapKey);
        }

        default String effectiveKey(String mapKey) {
            return key().filter(not(String::isBlank)).orElse(mapKey);
        }

        static boolean isEqual(EntryPointConfig c1, EntryPointConfig c2) {

            if (c1 == c2) {
                return true;
            }
            if (c1 == null || c2 == null) {
                return false;
            }

            return Objects.equals(c1.enabled(), c2.enabled())
                    && Objects.equals(c1.dir(), c2.dir())
                    && Objects.equals(c1.key(), c2.key())
                    && Objects.equals(c1.quteTags(), c2.quteTags());
        }

    }

    static boolean isEqual(WebBundlerConfig c1, WebBundlerConfig c2) {

        if (c1 == c2) {
            return true;
        }
        if (c1 == null || c2 == null) {
            return false;
        }

        for (Map.Entry<String, EntryPointConfig> entry : c1.bundle().entrySet()) {
            if (!EntryPointConfig.isEqual(entry.getValue(), c2.bundle().get(entry.getKey()))) {
                return false;
            }
        }

        return Objects.equals(c1.webRoot(), c2.webRoot())
                && Objects.equals(c1.staticDir(), c2.staticDir())
                && Objects.equals(c1.bundlePath(), c2.bundlePath())
                && BundlingConfig.isEqual(c1.bundling(), c2.bundling())
                && WebDependenciesConfig.isEqual(c1.dependencies(), c2.dependencies())
                && Objects.equals(c1.bundleRedirect(), c2.bundleRedirect())
                && Objects.equals(c1.charset(), c2.charset());
    }

}
