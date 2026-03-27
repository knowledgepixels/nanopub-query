## [1.6.0](https://github.com/knowledgepixels/nanopub-query/compare/nanopub-query-1.5.0...nanopub-query-1.6.0) (2026-03-27)

### Features

* add informational HTTP headers and HEAD request support ([04fe4bb](https://github.com/knowledgepixels/nanopub-query/commit/04fe4bb5ae53addef093c7163ee701d2eed3202d))

### Bug Fixes

* detect registry reset on upgrade and add FORCE_RESYNC env var ([6a317cd](https://github.com/knowledgepixels/nanopub-query/commit/6a317cdb196181679751a2520074b37ffb6a41d6))

### General maintenance

* copy only the fat JAR in local.Dockerfile ([60f4836](https://github.com/knowledgepixels/nanopub-query/commit/60f4836c490a3a07bd1c4cfbef64177faeb490e3))
* setting next snapshot version [skip ci] ([8d95118](https://github.com/knowledgepixels/nanopub-query/commit/8d95118987e3db996d0b02ee1ef22cfdbb08916e))

## [1.5.0](https://github.com/knowledgepixels/nanopub-query/compare/nanopub-query-1.4.1...nanopub-query-1.5.0) (2026-03-27)

### Features

* allow previewing unpublished nanopub queries via URL parameter ([#60](https://github.com/knowledgepixels/nanopub-query/issues/60)) ([9231d69](https://github.com/knowledgepixels/nanopub-query/commit/9231d6900056b631b1e27c37bb21530205bc2bb8))
* detect and recover from Nanopub Registry resets ([1927e98](https://github.com/knowledgepixels/nanopub-query/commit/1927e98477d03e0298117033a3b1b3e635e89d00))

### Dependency updates

* **core-deps:** update org.nanopub:nanopub dependency to v1.86.1 ([7649559](https://github.com/knowledgepixels/nanopub-query/commit/76495599d73bfbf939367a4519975b9e404a66f6))
* **deps:** update package-lock.json for improved compatibility and security ([55bf37c](https://github.com/knowledgepixels/nanopub-query/commit/55bf37c43b832b8bcf16baae1c9d06d53ab03748))

### Documentation

* add implementation plan for space repositories ([85e5a53](https://github.com/knowledgepixels/nanopub-query/commit/85e5a53d2023e5d8f9a2c966b1004843846b6127))

### Tests

* **deps:** add org.nanopub:nanopub-testsuite-connector dependency v1.0.0 ([58e31f5](https://github.com/knowledgepixels/nanopub-query/commit/58e31f53e6903e1346adb31ca864e935d6b0c19c))

### Build and continuous integration

* **release:** automate main branch update after release ([d9fa804](https://github.com/knowledgepixels/nanopub-query/commit/d9fa80409ea9cc02824308ae90114ceb1f729ef7))

### General maintenance

* remove testsuite submodule ([11f6cd9](https://github.com/knowledgepixels/nanopub-query/commit/11f6cd93cd8135bc0746d672cbb30db3e7409f33))
* remove unused git submodule execution from exec-maven-plugin ([f5c86b1](https://github.com/knowledgepixels/nanopub-query/commit/f5c86b1683b4ef7d02d64e2aa100a6880ca3e745))
* setting next snapshot version [skip ci] ([9c6427a](https://github.com/knowledgepixels/nanopub-query/commit/9c6427a8a7119b223342c40df60e4d5986a04496))
* **vocabulary:** add KPXL_GRLC class for GRLC vocabulary IRIs and update references in GrlcSpec ([4394464](https://github.com/knowledgepixels/nanopub-query/commit/439446407edb6b2defa186d68d1f88327b7ac4eb))

### Refactoring

* **tests:** replace file path loading with TestSuiteEntry for nanopub tests ([45caa04](https://github.com/knowledgepixels/nanopub-query/commit/45caa049761c5a9a06adc30ad833519b511dd31a))

## [1.4.1](https://github.com/knowledgepixels/nanopub-query/compare/nanopub-query-1.4.0...nanopub-query-1.4.1) (2026-03-10)

### Bug Fixes

* **NanopubLoader:** add max retry limit to prevent loading from hanging indefinitely ([fed751a](https://github.com/knowledgepixels/nanopub-query/commit/fed751a0d5d0f6b42d6f37080548acc3552f816b)), closes [#57](https://github.com/knowledgepixels/nanopub-query/issues/57)
* resolve api-version=latest locally instead of via network calls ([ecc13bb](https://github.com/knowledgepixels/nanopub-query/commit/ecc13bbfefccf28c6b1b6e9720e19a5dce8781e8)), closes [#58](https://github.com/knowledgepixels/nanopub-query/issues/58)

### General maintenance

* setting next snapshot version [skip ci] ([bdf0679](https://github.com/knowledgepixels/nanopub-query/commit/bdf0679f43f17a002008929145d9d3294d7bad43))

### Refactoring

* **GrlcSpec:** rename logger variable for consistency and improve logging messages ([c5531c0](https://github.com/knowledgepixels/nanopub-query/commit/c5531c09a0c1356da862c65b2beec97c6ba0edcc))

## [1.4.0](https://github.com/knowledgepixels/nanopub-query/compare/nanopub-query-1.3.0...nanopub-query-1.4.0) (2026-02-24)

### Features

* Adjust Accept header Media types for Construct queries ([2babdf7](https://github.com/knowledgepixels/nanopub-query/commit/2babdf7b5fffa7f6a5ba51f7448bb6d98527d306))

### General maintenance

* setting next snapshot version [skip ci] ([a65280d](https://github.com/knowledgepixels/nanopub-query/commit/a65280d410dae5638e550766b9285641a4cf9030))

## [1.3.0](https://github.com/knowledgepixels/nanopub-query/compare/nanopub-query-1.2.4...nanopub-query-1.3.0) (2026-02-05)

### Features

* File extensions (e.g. '.csv') as alternative to HTTP headers ([66e9f92](https://github.com/knowledgepixels/nanopub-query/commit/66e9f9265b3e86db517ffa52df08f58c65dbf424))
* Support 'application/xml' in OpenApi spec page ([0115956](https://github.com/knowledgepixels/nanopub-query/commit/01159566ecf435badd625f1f3b6f48250baa255e))

### Bug Fixes

* Fix OpenApiSpecPageTest ([a1dbc1d](https://github.com/knowledgepixels/nanopub-query/commit/a1dbc1d7b27460540128035a538b9ca2b30bf212))
* **StatusController:** Report original exception when rollback fails ([5e42c91](https://github.com/knowledgepixels/nanopub-query/commit/5e42c9111518520f715e11bc434f3b29f8c5bfe6))

### General maintenance

* add contributing guidelines ([7267870](https://github.com/knowledgepixels/nanopub-query/commit/7267870ca9d30b57ce6675f09c81841b113d1445))
* setting next snapshot version [skip ci] ([8052bb5](https://github.com/knowledgepixels/nanopub-query/commit/8052bb5fd4cd9b86ef25edecd07f3ef99bf98102))

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
