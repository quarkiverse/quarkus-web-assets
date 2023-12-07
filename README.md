# Quarkus Web Bundler

[![Version](https://img.shields.io/maven-central/v/io.quarkiverse.web-bundler/quarkus-web-bundler?logo=apache-maven&style=flat-square)](https://search.maven.org/artifact/io.quarkiverse.web-bundler/quarkus-web-bundler)

Create full-stack web apps and components with this Quarkus extension. It offers zero-configuration bundling and minification (with source-map) for your web app scripts (JS, JSX, TS, TSX), dependencies (jQuery, htmx, Bootstrap, Lit etc.), and styles (CSS, SCSS, SASS).

No need to install NodeJs, it relies on a Java wrapped version of [esbuild](https://esbuild.github.io/). For libraries, all the NPM catalog is accessible through Maven or Gradle dependencies.

* [x] Production build
* [x] Awesome Dev experience
* [x] Integrated with NPM dependencies through [mvnpm](https://docs.quarkiverse.io/quarkus-web-bundler/dev/advanced-guides.html#mvnpm) or [webjars](https://docs.quarkiverse.io/quarkus-web-bundler/dev/advanced-guides.html#webjars).
* [x] Build-time index.html rendering with bundled scripts and styles
* [x] Server Side Qute Components (Qute template + Script + Style)

All the information you need to use Quarkus Web Bundler is in the [user documentation](https://docs.quarkiverse.io/quarkus-web-bundler/dev/).


