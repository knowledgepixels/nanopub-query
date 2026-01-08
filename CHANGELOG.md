## [1.2.4](https://github.com/knowledgepixels/nanopub-query/compare/nanopub-query-1.2.3...nanopub-query-1.2.4) (2026-01-08)

### Bug Fixes

* Reactivate healthtest and make it work again ([8abbf41](https://github.com/knowledgepixels/nanopub-query/commit/8abbf41314d7ab4d209681717955608078d13dab))
* Revert and switch to other rdf4j image, to solve curl problem ([0a10d04](https://github.com/knowledgepixels/nanopub-query/commit/0a10d04afc6a99233d7353b1416d762e50dc88d7))

### General maintenance

* setting next snapshot version [skip ci] ([2c1dd9f](https://github.com/knowledgepixels/nanopub-query/commit/2c1dd9fd449615e4d11f62f11d8ad88cce6a2a6a))

## [1.2.3](https://github.com/knowledgepixels/nanopub-query/compare/nanopub-query-1.2.2...nanopub-query-1.2.3) (2026-01-07)

### Bug Fixes

* **NanopubLoader:** Defer meta loading to fix half-loaded nanopubs ([1e8fca1](https://github.com/knowledgepixels/nanopub-query/commit/1e8fca121a119709f4d7ba84bb9459e10759cdfd))

### General maintenance

* Add nohub.out to .gitignore ([ad0bed4](https://github.com/knowledgepixels/nanopub-query/commit/ad0bed45f2a1db34f000e49679f6a14c19a75341))
* setting next snapshot version [skip ci] ([3b8013f](https://github.com/knowledgepixels/nanopub-query/commit/3b8013ffe32f8987da51126f16ca0f1179721fb1))

## [1.2.2](https://github.com/knowledgepixels/nanopub-query/compare/nanopub-query-1.2.1...nanopub-query-1.2.2) (2026-01-07)

### Dependency updates

* **core-deps:** update eclipse:temurin base image to v25-jre ([2ec3436](https://github.com/knowledgepixels/nanopub-query/commit/2ec34366408f370e0e1041f1dc6f636fc82262bc))
* **deps:** update org.jacoco:jacoco-maven-plugin version to v0.8.14 ([1b4db46](https://github.com/knowledgepixels/nanopub-query/commit/1b4db46016863a02239f74d2b6e37b3a09ff2640))

### Bug Fixes

* **init-dirs.sh:** File permissions caused problems in particular setting ([72e3452](https://github.com/knowledgepixels/nanopub-query/commit/72e34524fc1f86485f12d39f5783221f442352bb))
* Logging works again ([c47e142](https://github.com/knowledgepixels/nanopub-query/commit/c47e1423cd2963e8d1babd7cafce504159fbf2ef))

### Performance improvements

* lucene indexing, reduce idle CPU usage ([8e7abba](https://github.com/knowledgepixels/nanopub-query/commit/8e7abba4ee61557556505661323064bebde23cab)), closes [#53](https://github.com/knowledgepixels/nanopub-query/issues/53)

### Tests

* **deps:** update org.mockito:mockito-core dependency to v5.21.0 ([0471c28](https://github.com/knowledgepixels/nanopub-query/commit/0471c2817484fd68f841644c9072b62654225e20))

### Build and continuous integration

* **deps:** update com.google.cloud.tools:jib-maven-plugin dependency to v3.5.1 ([1639a82](https://github.com/knowledgepixels/nanopub-query/commit/1639a822c95c426069155653688b6ba594266d64))
* **deps:** update org.apache.maven.plugins:maven-javadoc-plugin dependency to v3.12.0 ([849fd26](https://github.com/knowledgepixels/nanopub-query/commit/849fd26d8e0a357d46aece2816e2a036df5b70d8))

### General maintenance

* Add .vscode to .gitignore ([5dc5863](https://github.com/knowledgepixels/nanopub-query/commit/5dc58637448fcf03af024a3524f802ac81d0657d))
* code clean up ([94fceb3](https://github.com/knowledgepixels/nanopub-query/commit/94fceb3c7002361cb4ee466f1908cfb13a11008b))
* **docker:** clean and update related resources ([a67784d](https://github.com/knowledgepixels/nanopub-query/commit/a67784ded8187c926b2f5706032f00d7fecda348))
* setting next snapshot version [skip ci] ([e45f2b4](https://github.com/knowledgepixels/nanopub-query/commit/e45f2b4f2699675ee7f505f5559f5d7ed704bf64))

## [1.2.1](https://github.com/knowledgepixels/nanopub-query/compare/nanopub-query-1.2.0...nanopub-query-1.2.1) (2025-12-19)

### Dependency updates

* **core-deps:** update org.apache.commons:commons-exec dependency to v1.6.0 ([810c6dd](https://github.com/knowledgepixels/nanopub-query/commit/810c6ddf6e33e9abbf3f1cae7d2b34d8c240e651))

### Bug Fixes

* Use 'cp' instead of 'mv' ([f8eebfa](https://github.com/knowledgepixels/nanopub-query/commit/f8eebfac6fe358dfdbea342e5c1ce70f0cae99aa))

### Build and continuous integration

* **pom:** update configuration by using JIB for building the image ([660c5de](https://github.com/knowledgepixels/nanopub-query/commit/660c5def0660c292b99816fa207e616bbfcf5338))
* **release:** remove Maven configuration ([c355a05](https://github.com/knowledgepixels/nanopub-query/commit/c355a051f94bb187107bda50cc2c5c7855f00017))

### General maintenance

* **pom:** use variable for main class in run configuration ([3027447](https://github.com/knowledgepixels/nanopub-query/commit/3027447ece48dfd59155b20e500123b574415898))
* **release:** update config to use a different mavenTarget ([4ceb494](https://github.com/knowledgepixels/nanopub-query/commit/4ceb494c9c13e0d3e2f3479b1fbc61fbcd1ffe9a))
