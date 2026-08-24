package com.knowledgepixels.query;

import com.github.jsonldjava.shaded.com.google.common.base.Charsets;
import com.knowledgepixels.query.GrlcSpec.InvalidGrlcSpecException;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.proxy.handler.ProxyHandler;
import io.vertx.httpproxy.*;
import io.vertx.micrometer.PrometheusScrapingHandler;
import io.vertx.micrometer.backends.BackendRegistries;
import org.eclipse.rdf4j.model.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Main verticle that coordinates the incoming HTTP requests.
 */
@GeneratedFlagForDependentElements
public class MainVerticle extends AbstractVerticle {

    private static String css = null;

    private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);

    /**
     * Start the main verticle.
     *
     * @param startPromise the promise to complete when the verticle is started
     * @throws Exception if an error occurs during startup
     */
    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        if (!FeatureFlags.trustStateEnabled()) {
            logger.warn("Trust state feature disabled via NANOPUB_QUERY_ENABLE_TRUST_STATE=false — "
                    + "no trust snapshots will be fetched or materialised, and the 'trust' repo will not be auto-created.");
        }
        if (!FeatureFlags.spacesEnabled()) {
            logger.warn("Spaces feature disabled via NANOPUB_QUERY_ENABLE_SPACES=false — "
                    + "no space-relevant nanopubs will be extracted into npa:spacesGraph, "
                    + "and the 'spaces' repo will not be auto-created.");
        }
        if (!FeatureFlags.fullRepoEnabled()) {
            logger.warn("Writes to the 'full' repo disabled via NANOPUB_QUERY_ENABLE_FULL_REPO=false — "
                    + "generic SPARQL queries against /repo/full will return an empty store.");
        }
        if (!FeatureFlags.textRepoEnabled()) {
            logger.warn("Writes to the 'text' repo disabled via NANOPUB_QUERY_ENABLE_TEXT_REPO=false — "
                    + "full-text search via /repo/text will return nothing.");
        }
        if (!FeatureFlags.last30dRepoEnabled()) {
            logger.warn("Writes to the 'last30d' repo disabled via NANOPUB_QUERY_ENABLE_LAST30D_REPO=false — "
                    + "the /repo/last30d endpoint will be empty; rewrite queries against /repo/full with a date filter.");
        }
        if (!FeatureFlags.reconciliationEnabled()) {
            logger.warn("Shard reconciliation disabled via NANOPUB_QUERY_ENABLE_RECONCILIATION=false — "
                    + "nanopubs silently missing from individual shard repos (issue #139) will not be detected or repaired.");
        }
        if (FeatureFlags.localInstance()) {
            logger.warn("Instance declared local/private via NANOPUB_QUERY_LOCAL_INSTANCE=true — "
                    + "nanopubs typed npx:ProtectedNanopub will be loaded and served. Never use this setting on a public instance.");
        }
        HttpClient httpClient = vertx.createHttpClient(
                new HttpClientOptions()
                        .setConnectTimeout(Utils.getEnvInt("NANOPUB_QUERY_VERTX_CONNECT_TIMEOUT", 1000))
                        .setIdleTimeoutUnit(TimeUnit.SECONDS)
                        .setIdleTimeout(Utils.getEnvInt("NANOPUB_QUERY_VERTX_IDLE_TIMEOUT", 60))
                        .setReadIdleTimeout(Utils.getEnvInt("NANOPUB_QUERY_VERTX_IDLE_TIMEOUT", 60))
                        .setWriteIdleTimeout(Utils.getEnvInt("NANOPUB_QUERY_VERTX_IDLE_TIMEOUT", 60)),
                new PoolOptions().setHttp1MaxSize(200).setHttp2MaxSize(200)
        );

        // Idle timeout on the *server* side, which was previously unset ("wait forever").
        // SERVICE clauses in /api queries loop back into this server via
        // NANOPUB_QUERY_INTERNAL_URL, so the RDF4J server holds a pooled connection here
        // for every federated evaluation. When RDF4J leaks such a lease (the ValueStore
        // ReadTxn leak, eclipse-rdf4j/rdf4j#5970), nothing on this end ever closes the
        // socket: it stays ESTABLISHED, Apache's evictExpiredConnections never sees it as
        // stale, and the slot is gone until Tomcat restarts. Observed on 2026-08-11:
        // 100/100 connections to this port held ESTABLISHED while all 157 Tomcat worker
        // threads sat idle in their task queue, i.e. a fully leased pool with zero work in
        // flight, and every federated query failing with "Timeout waiting for connection
        // from pool" until the store was restarted by hand.
        //
        // Closing idle connections turns that permanent wedge into a self-healing one: a
        // leaked lease is closed by this end, goes stale, and is evicted from RDF4J's pool.
        // The default is deliberately far above any legitimate idle period on that route —
        // RDF4J's own client uses a 10 s socket timeout for SERVICE evaluation — while
        // still bounding the leak. Note this counts *any* inactivity, including a slow
        // query that has not started streaming its response, so do not lower it below the
        // slowest query this instance is expected to serve.
        HttpServer proxyServer = vertx.createHttpServer(
                new HttpServerOptions()
                        .setMaxInitialLineLength(65536)
                        .setIdleTimeoutUnit(TimeUnit.SECONDS)
                        .setIdleTimeout(Utils.getEnvInt("NANOPUB_QUERY_VERTX_SERVER_IDLE_TIMEOUT", 300))
        );
        Router proxyRouter = Router.router(vertx);
        proxyRouter.route().handler(CorsHandler.create().addRelativeOrigin(".*"));

        // Metrics
        final var metricsHttpServer = vertx.createHttpServer();
        final var metricsRouter = Router.router(vertx);
        metricsHttpServer.requestHandler(metricsRouter).listen(9394);

        final var metricsRegistry = (PrometheusMeterRegistry) BackendRegistries.getDefaultNow();
        final var collector = new MetricsCollector(metricsRegistry);
        metricsRouter.route("/metrics").handler(PrometheusScrapingHandler.create(metricsRegistry));
        // ----------
        // This part is only used if the redirection is not done through Nginx.
        // See nginx.conf and this bug report: https://github.com/eclipse-rdf4j/rdf4j/discussions/5120
        HttpProxy rdf4jProxy = HttpProxy.reverseProxy(httpClient);
        String proxy = Utils.getEnvString("RDF4J_PROXY_HOST", "rdf4j");
        int proxyPort = Utils.getEnvInt("RDF4J_PROXY_PORT", 8080);
        rdf4jProxy.origin(proxyPort, proxy);

        // Server-side query evaluation limit injected into every proxied rdf4j request
        // (see Utils.appendQueryTimeout). The public edge cuts clients at 10s, but that
        // never stops the server-side evaluation; this does. Kept well above the edge
        // timeout so aborts stay rare: interrupted evaluations concurrent with writes
        // are a suspected LMDB corruption trigger upstream (rdf4j#5960/#4806).
        int queryTimeoutSeconds = Utils.getEnvInt("RDF4J_QUERY_TIMEOUT_SECONDS", 60);

        rdf4jProxy.addInterceptor(new ProxyInterceptor() {

            @Override
            @GeneratedFlagForDependentElements
            public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
                ProxyRequest request = context.request();
                request.setURI(request.getURI().replaceAll("/", "_").replaceFirst("^_repo_", "/rdf4j-server/repositories/"));
                request.setURI(Utils.appendQueryTimeout(request.getURI(), queryTimeoutSeconds));
                // For later to try to get HTML tables out:
//				if (request.headers().get("Accept") == null) {
//					request.putHeader("Accept", "text/html");
//				}
//				request.putHeader("Accept", "application/json");
                return ProxyInterceptor.super.handleProxyRequest(context);
            }

            @Override
            @GeneratedFlagForDependentElements
            public Future<Void> handleProxyResponse(ProxyContext context) {
                ProxyResponse resp = context.response();
                resp.putHeader("Access-Control-Allow-Origin", "*");
                resp.putHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
                // For later to try to get HTML tables out:
//				String acceptHeader = context.request().headers().get("Accept");
//				if (acceptHeader != null && acceptHeader.contains("text/html")) {
//					resp.putHeader("Content-Type", "text/html");
//					resp.setBody(Body.body(Buffer.buffer("<html><body><strong>test</strong></body></html>")));
//				}
                return ProxyInterceptor.super.handleProxyResponse(context);
            }

        });
        // ----------

        proxyRouter.route(HttpMethod.GET, "/repo").handler(req -> handleRedirect(req, "/repo"));
        proxyRouter.route(HttpMethod.GET, "/repo/*").handler(ProxyHandler.create(rdf4jProxy));
        proxyRouter.route(HttpMethod.POST, "/repo/*").handler(ProxyHandler.create(rdf4jProxy));
        proxyRouter.route(HttpMethod.HEAD, "/repo/*").handler(ProxyHandler.create(rdf4jProxy));
        proxyRouter.route(HttpMethod.OPTIONS, "/repo/*").handler(ProxyHandler.create(rdf4jProxy));
        proxyRouter.route(HttpMethod.GET, "/tools/*").handler(req -> {
            final String yasguiPattern = "^/tools/([a-zA-Z0-9-_]+)(/([a-zA-Z0-9-_]+))?/yasgui\\.html$";
            if (req.normalizedPath().matches(yasguiPattern)) {
                String repo = req.normalizedPath().replaceFirst(yasguiPattern, "$1$2");
                req.response()
                        .putHeader("content-type", "text/html")
                        .end("<!DOCTYPE html>\n"
                             + "<html lang=\"en\">\n"
                             + "<head>\n"
                             + "<meta charset=\"utf-8\">\n"
                             + "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">\n"
                             + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                             + "<title>Nanopub Query SPARQL Editor for repository: " + repo + "</title>\n"
                             + "<link rel=\"stylesheet\" href=\"/style.css\">\n"
                             + "<link href='https://cdn.jsdelivr.net/yasgui/2.6.1/yasgui.min.css' rel='stylesheet' type='text/css'/>\n"
                             + "<style>.yasgui .endpointText {display:none !important;}</style>\n"
                             + "<script type=\"text/javascript\">localStorage.clear();</script>\n"
                             + "</head>\n"
                             + "<body>\n"
                             + "<h3>Nanopub Query SPARQL Editor for repository: " + repo + "</h3>\n"
                             + "<div id='yasgui'></div>\n"
                             + "<script src='https://cdn.jsdelivr.net/yasgui/2.6.1/yasgui.min.js'></script>\n"
                             + "<script type=\"text/javascript\">\n"
                             + "var yasgui = YASGUI(document.getElementById(\"yasgui\"), {\n"
                             + "  yasqe:{sparql:{endpoint:'/repo/" + repo + "'},value:'" + Utils.defaultQuery.replaceAll("\n", "\\\\n") + "'}\n"
                             + "});\n"
                             + "</script>\n"
                             + "</body>\n"
                             + "</html>");
            } else if (req.normalizedPath().matches(SparqlEditorRoute.PATH_PATTERN)) {
                // SIB's sparql-editor, offered alongside the plain YASGUI page above
                // rather than instead of it (issue #51).
                req.response()
                        .putHeader("content-type", "text/html")
                        .end(SparqlEditorRoute.renderHtml(SparqlEditorRoute.repoFromPath(req.normalizedPath())));
            } else {
                req.response()
                        .putHeader("content-type", "text/plain")
                        .setStatusCode(404)
                        .end("not found");
            }
        });
        proxyRouter.route(HttpMethod.GET, "/page").handler(req -> handleRedirect(req, "/page"));
        proxyRouter.route(HttpMethod.GET, "/page/*").handler(req -> {
            final String pagePattern = "^/page/([a-zA-Z0-9-_]+)(/([a-zA-Z0-9-_]+))?$";
            if (req.normalizedPath().matches(pagePattern)) {
                String repo = req.normalizedPath().replaceFirst(pagePattern, "$1$2");
                req.response()
                        .putHeader("content-type", "text/html")
                        .end("<!DOCTYPE html>\n"
                             + "<html lang=\"en\">\n"
                             + "<head>\n"
                             + "<meta charset=\"utf-8\">\n"
                             + "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">\n"
                             + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                             + "<title>Nanopub Query repo: " + repo + "</title>\n"
                             + "<link rel=\"stylesheet\" href=\"/style.css\">\n"
                             + "</head>\n"
                             + "<body>\n"
                             + "<h3>Nanopub Query repo: " + repo + "</h3>\n"
                             + "<p>Endpoint: <a href=\"/repo/" + repo + "\">/repo/" + repo + "</a></p>"
                             + "<p>YASGUI: <a href=\"/tools/" + repo + "/yasgui.html\">/tools/" + repo + "/yasgui.html</a></p>"
                             + "<p>SPARQL editor: <a href=\"/tools/" + repo + "/sparql-editor.html\">/tools/" + repo + "/sparql-editor.html</a></p>"
                             + "</body>\n"
                             + "</html>");
            } else {
                req.response()
                        .putHeader("content-type", "text/plain")
                        .setStatusCode(404)
                        .end("not found");
            }
        });
        proxyRouter.route(HttpMethod.GET, "/").handler(req -> {
            vertx.<String>executeBlocking(() -> {
                String repos = "";
                List<String> repoList = new ArrayList<>(TripleStore.get().getRepositoryNames());
                Collections.sort(repoList);
                for (String s : repoList) {
                    if (s.startsWith("pubkey_") || s.startsWith("type_")) continue;
                    repos += "<li><code><a href=\"/page/" + s + "\">" + s + "</a></code></li>";
                }
                String pinnedApisValue = Utils.getEnvString("NANOPUB_QUERY_PINNED_APIS", "");
                String[] pinnedApis = pinnedApisValue.split(" ");
                String pinnedApiLinks = "";
                if (!pinnedApisValue.isEmpty()) {
                    for (String s : pinnedApis) {
                        pinnedApiLinks = pinnedApiLinks + "<li><a href=\"openapi/?url=spec/" + s + "%3Fapi-version=latest\">" + s.replaceFirst("^.*/", "") + "</a></li>";
                    }
                    pinnedApiLinks = "<p>Pinned APIs:</p>\n" +
                                     "<ul>\n" +
                                     pinnedApiLinks +
                                     "</ul>\n";
                }
                return "<!DOCTYPE html>\n"
                     + "<html lang='en'>\n"
                     + "<head>\n"
                     + "<title>Nanopub Query</title>\n"
                     + "<meta charset='utf-8'>\n"
                     + "<link rel=\"stylesheet\" href=\"/style.css\">\n"
                     + "</head>\n"
                     + "<body>\n"
                     + "<h1>Nanopub Query</h1>"
                     + "<p>General repos:</p>"
                     + "<ul>" + repos + "</ul>"
                     + "<p>Specific repos:</p>"
                     + "<ul>"
                     + "<li><a href=\"/pubkeys\">Pubkey Repos</a></li>"
                     + "<li><a href=\"/types\">Type Repos</a></li>"
                     + "</ul>"
                     + (FeatureFlags.spacesEnabled()
                             ? "<p>Spaces:</p>"
                               + "<ul><li><a href=\"/spaces\">Spaces</a></li></ul>"
                             : "")
                     + pinnedApiLinks
                     + "</body>\n"
                     + "</html>";
            }, false).onSuccess(html -> {
                req.response().putHeader("content-type", "text/html").end(html);
            }).onFailure(ex -> {
                req.response().setStatusCode(500).end("Error: " + ex.getMessage());
            });
        });
        proxyRouter.route(HttpMethod.GET, "/pubkeys").handler(req -> {
            vertx.<String>executeBlocking(() -> {
                String repos = "";
                List<String> repoList = new ArrayList<>(TripleStore.get().getRepositoryNames());
                Collections.sort(repoList);
                for (String s : repoList) {
                    if (!s.startsWith("pubkey_")) continue;
                    String hash = s.replaceFirst("^([a-zA-Z0-9-]+)_([a-zA-Z0-9-_]+)$", "$2");
                    Value hashObj = Utils.getObjectForHash(hash);
                    String label;
                    if (hashObj == null) {
                        label = "";
                    } else {
                        label = " (" + Utils.getShortPubkeyName(hashObj.stringValue()) + ")";
                    }
                    s = s.replaceFirst("^([a-zA-Z0-9-]+)_([a-zA-Z0-9-_]+)$", "$1/$2");
                    repos += "<li><code><a href=\"/page/" + s + "\">" + s + "</a>" + label + "</code></li>";
                }
                return "<!DOCTYPE html>\n"
                     + "<html lang='en'>\n"
                     + "<head>\n"
                     + "<title>Nanopub Query: Pubkey Repos</title>\n"
                     + "<meta charset='utf-8'>\n"
                     + "<link rel=\"stylesheet\" href=\"/style.css\">\n"
                     + "</head>\n"
                     + "<body>\n"
                     + "<h3>Pubkey Repos</h3>"
                     + "<p>Repos:</p>"
                     + "<ul>" + repos + "</ul>"
                     + "</body>\n"
                     + "</html>";
            }, false).onSuccess(html -> {
                req.response().putHeader("content-type", "text/html").end(html);
            }).onFailure(ex -> {
                req.response().setStatusCode(500).end("Error: " + ex.getMessage());
            });
        });
        proxyRouter.route(HttpMethod.GET, "/types").handler(req -> {
            vertx.<String>executeBlocking(() -> {
                String repos = "";
                List<String> repoList = new ArrayList<>(TripleStore.get().getRepositoryNames());
                Collections.sort(repoList);
                for (String s : repoList) {
                    if (!s.startsWith("type_")) continue;
                    String hash = s.replaceFirst("^([a-zA-Z0-9-]+)_([a-zA-Z0-9-_]+)$", "$2");
                    Value hashObj = Utils.getObjectForHash(hash);
                    String label;
                    if (hashObj == null) {
                        label = "";
                    } else {
                        label = " (" + hashObj.stringValue() + ")";
                    }
                    s = s.replaceFirst("^([a-zA-Z0-9-]+)_([a-zA-Z0-9-_]+)$", "$1/$2");
                    repos += "<li><code><a href=\"/page/" + s + "\">" + s + "</a>" + label + "</code></li>";
                }
                return "<!DOCTYPE html>\n"
                     + "<html lang='en'>\n"
                     + "<head>\n"
                     + "<title>Nanopub Query: Type Repos</title>\n"
                     + "<meta charset='utf-8'>\n"
                     + "<link rel=\"stylesheet\" href=\"/style.css\">\n"
                     + "</head>\n"
                     + "<body>\n"
                     + "<h3>Type Repos</h3>"
                     + "<p>Repos:</p>"
                     + "<ul>" + repos + "</ul>"
                     + "</body>\n"
                     + "</html>";
            }, false).onSuccess(html -> {
                req.response().putHeader("content-type", "text/html").end(html);
            }).onFailure(ex -> {
                req.response().setStatusCode(500).end("Error: " + ex.getMessage());
            });
        });
        io.vertx.core.Handler<RoutingContext> spacesHandler = req -> {
            if (!FeatureFlags.spacesEnabled()) {
                req.response().setStatusCode(404)
                        .putHeader("content-type", "text/plain")
                        .end("Spaces feature is disabled");
                return;
            }
            // Path suffix wins over Accept header so /spaces.json is unambiguous.
            boolean wantJson = req.normalizedPath().endsWith(".json")
                    || "application/json".equalsIgnoreCase(req.request().getHeader("Accept"));
            vertx.<String>executeBlocking(() -> {
                var rows = SpacesListingRoute.fetchRows();
                return wantJson
                        ? SpacesListingRoute.renderJson(rows)
                        : SpacesListingRoute.renderHtml(rows);
            }, false).onSuccess(body -> {
                req.response().putHeader(
                        "content-type",
                        wantJson ? "application/json" : "text/html").end(body);
            }).onFailure(ex -> {
                req.response().setStatusCode(500).end("Error: " + ex.getMessage());
            });
        };
        proxyRouter.route(HttpMethod.GET, "/spaces").handler(spacesHandler);
        proxyRouter.route(HttpMethod.GET, "/spaces.json").handler(spacesHandler);
        proxyRouter.route(HttpMethod.GET, "/style.css").handler(req -> {
            if (css == null) {
                css = getResourceAsString("style.css");
            }
            req.response().end(css);
        });

        // TODO This is no longer needed and can be removed at some point:
        proxyRouter.route(HttpMethod.GET, "/grlc-spec/*").handler(req -> {
            vertx.<String>executeBlocking(() -> {
                GrlcSpec gsp = new GrlcSpec(req.normalizedPath(), req.queryParams());
                return gsp.getSpec();
            }, false).onSuccess(spec -> {
                req.response().putHeader("content-type", "text/yaml").end(spec);
            }).onFailure(ex -> {
                if (ex instanceof InvalidGrlcSpecException) {
                    logger.warn("Bad grlc request for '{}': {}", req.normalizedPath(), ex.getMessage());
                    req.response().setStatusCode(400).end(ex.getMessage());
                } else {
                    logger.error("Unexpected error for grlc request '{}'", req.normalizedPath(), ex);
                    req.response().setStatusCode(500).end("Unexpected error: " + ex.getMessage());
                }
            });
        });

        proxyRouter.route(HttpMethod.GET, "/openapi/spec/*").handler(req -> {
            vertx.<String>executeBlocking(() -> {
                OpenApiSpecPage osp = new OpenApiSpecPage(req.normalizedPath(), req.queryParams());
                return osp.getSpec();
            }, false).onSuccess(spec -> {
                req.response().putHeader("content-type", "text/yaml").end(spec);
            }).onFailure(ex -> {
                if (ex instanceof InvalidGrlcSpecException) {
                    logger.warn("Bad openapi request for '{}': {}", req.normalizedPath(), ex.getMessage());
                    req.response().setStatusCode(400).end("Invalid grlc API definition: " + ex.getMessage());
                } else {
                    logger.error("Unexpected error for openapi request '{}'", req.normalizedPath(), ex);
                    req.response().setStatusCode(500).end("Unexpected error: " + ex.getMessage());
                }
            });
        });

        proxyRouter.route("/openapi/*").handler(StaticHandler.create("com/knowledgepixels/query/swagger"));

        HttpProxy grlcxProxy = HttpProxy.reverseProxy(httpClient);
        grlcxProxy.origin(proxyPort, proxy);

        grlcxProxy.addInterceptor(new ProxyInterceptor() {

            @Override
            @GeneratedFlagForDependentElements
            public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
                final ProxyRequest req = context.request();
                final String apiPattern = "^/api/(RA[a-zA-Z0-9-_]{43})/([a-zA-Z0-9-_]+)([.]csv|[.]json|[.]srx)?([?].*)?$";
                if (req.getURI().matches(apiPattern)) {
                    try {
                        req.setMethod(HttpMethod.POST);
                        if (req.getURI().matches(".*[.]csv([?].*)?$")) {
                            req.putHeader("Accept", "text/csv");
                            req.setURI(req.getURI().replaceFirst("[.]csv([?].*)?$", "$1"));
                        } else if (req.getURI().matches(".*[.]json([?].*)?$")) {
                            req.putHeader("Accept", "application/json");
                            req.setURI(req.getURI().replaceFirst("[.]json([?].*)?$", "$1"));
                        } else if (req.getURI().matches(".*[.]srx([?].*)?$")) {
                            req.putHeader("Accept", "application/xml");
                            req.setURI(req.getURI().replaceFirst("[.]srx([?].*)?$", "$1"));
                        }
                        GrlcSpec grlcSpec = new GrlcSpec(req.getURI(), req.proxiedRequest().params());

                        // Variant 1:
                        req.putHeader("Content-Type", "application/sparql-query");
                        req.setBody(Body.body(Buffer.buffer(grlcSpec.expandQuery())));
                        // Variant 2:
                        //req.putHeader("Content-Type", "application/x-www-form-urlencoded");
                        //req.setBody(Body.body(Buffer.buffer("query=" + URLEncoder.encode(grlcSpec.getExpandedQueryContent(), Charsets.UTF_8))));

                        req.setURI(Utils.appendQueryTimeout(
                                "/rdf4j-server/repositories/" + grlcSpec.getRepoName(), queryTimeoutSeconds));
                        logger.info("Forwarding apix request to /rdf4j-server/repositories/{}", grlcSpec.getRepoName());
                    } catch (InvalidGrlcSpecException ex) {
                        logger.warn("Bad API request for '{}' with params {}: {}", req.getURI(), req.proxiedRequest().params(), ex.getMessage());
                        return Future.succeededFuture(context.request()
                                .response()
                                .setStatusCode(400)
                                .putHeader("Content-Type", "text/plain")
                                .setBody(Body.body(Buffer.buffer("Bad request: " + ex.getMessage()))));
                    } catch (Exception ex) {
                        logger.error("Unexpected error for API request '{}' with params {}", req.getURI(), req.proxiedRequest().params(), ex);
                        return Future.succeededFuture(context.request()
                                .response()
                                .setStatusCode(500)
                                .putHeader("Content-Type", "text/plain")
                                .setBody(Body.body(Buffer.buffer("Unexpected error: " + ex.getMessage()))));
                    }
                }
                return ProxyInterceptor.super.handleProxyRequest(context);
            }

            @Override
            @GeneratedFlagForDependentElements
            public Future<Void> handleProxyResponse(ProxyContext context) {
                logger.info("Receiving api response");
                ProxyResponse resp = context.response();
                resp.putHeader("Access-Control-Allow-Origin", "*");
                resp.putHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
                resp.putHeader("Content-Disposition", "inline");
                return ProxyInterceptor.super.handleProxyResponse(context);
            }

        });
        proxyRouter.route(HttpMethod.GET, "/api/*").handler(ProxyHandler.create(grlcxProxy));

        // Handle HEAD requests for all paths not already covered (e.g. /repo/* has its own HEAD handler).
        // Global headers are applied before routing, so we just end the response with no body.
        proxyRouter.route(HttpMethod.HEAD, "/*").handler(req -> {
            req.response().setStatusCode(200).end();
        });

        proxyServer.requestHandler(req -> {
            applyGlobalHeaders(req.response());
            proxyRouter.handle(req);
        });
        proxyServer.listen(9393);

        // Periodic metrics update. Runs on a dedicated single-thread scheduled executor
        // (not on the Vert.x event loop) because `updateMetrics` can fall through to a
        // synchronous HTTP call in `TripleStore.getRepositoryNames()` when the cache has
        // been invalidated. `scheduleWithFixedDelay` naturally serialises ticks and cannot
        // pile up if the work occasionally runs long. Same pattern as `JellyNanopubLoader.loadUpdates`
        // below.
        Executors.newSingleThreadScheduledExecutor()
                .scheduleWithFixedDelay(collector::updateMetrics, 1, 1, TimeUnit.SECONDS);


        new Thread(() -> {
            try {
                var status = StatusController.get().initialize();
                logger.info("Current state: {}, last committed counter: {}", status.state, status.loadCounter);
                // Restore or fetch the registry setup ID
                Long storedSetupId = StatusController.get().getRegistrySetupId();
                if (storedSetupId != null) {
                    JellyNanopubLoader.setLastKnownSetupId(storedSetupId);
                    logger.info("Restored registry setupId: {}", storedSetupId);
                } else if (status.state == StatusController.State.LAUNCHING
                        || status.state == StatusController.State.LOADING_INITIAL) {
                    // Fresh start or crashed during initial load – safe to adopt the current setupId
                    try {
                        var metadata = JellyNanopubLoader.fetchRegistryMetadata();
                        JellyNanopubLoader.setLastKnownSetupId(metadata.setupId());
                        if (metadata.setupId() != null) {
                            StatusController.get().setRegistrySetupId(metadata.setupId());
                            logger.info("Fetched initial registry setupId: {}", metadata.setupId());
                        }
                    } catch (Exception e) {
                        logger.warn("Could not fetch initial registry setupId", e);
                    }
                } else {
                    // Upgrade from a version without setupId tracking. The DB has data but
                    // we can't verify it matches the current registry state. Leave lastKnownSetupId
                    // as null so that loadUpdates() will trigger a resync.
                    logger.warn("No stored registry setupId but DB has data (state: {}, counter: {}). "
                            + "A resync will be triggered on the first update poll.",
                            status.state, status.loadCounter);
                }
                boolean forceResync = "true".equalsIgnoreCase(
                        Utils.getEnvString("FORCE_RESYNC", "false"));
                if (forceResync && status.state != StatusController.State.LAUNCHING) {
                    logger.warn("FORCE_RESYNC is set. Forcing full re-load from registry.");
                    var metadata = JellyNanopubLoader.fetchRegistryMetadata();
                    JellyNanopubLoader.setLastKnownSetupId(metadata.setupId());
                    if (metadata.setupId() != null) {
                        StatusController.get().setRegistrySetupId(metadata.setupId());
                    }
                    StatusController.get().setResetting();
                    StatusController.get().setLoadingInitial(-1);
                    JellyNanopubLoader.loadInitial(-1);
                    StatusController.get().setReady();
                } else if (status.state == StatusController.State.LAUNCHING || status.state == StatusController.State.LOADING_INITIAL) {
                    // Do the initial nanopublication loading
                    StatusController.get().setLoadingInitial(status.loadCounter);
                    // Fall back to local nanopub loading if the local files are present
                    if (!LocalNanopubLoader.init()) {
                        JellyNanopubLoader.loadInitial(status.loadCounter);
                    } else {
                        logger.info("Local nanopublication loading finished");
                    }
                    StatusController.get().setReady();
                } else {
                    logger.info("Initial load is already done");
                    StatusController.get().setReady();
                }
            } catch (Exception ex) {
                logger.info("Initial load failed, terminating...", ex);
                Runtime.getRuntime().exit(1);
            }

            // Seed the TrustStateRegistry from any persisted pointer before the
            // periodic poll begins, so the first tick doesn't re-materialize state
            // we already have.
            TrustStateLoader.bootstrap();

            // Drop any npass:* graph that isn't the current-pointer target —
            // leftovers from builds interrupted by a crash.
            if (FeatureFlags.spacesEnabled()) {
                AuthorityResolver.get().cleanOrphans();
            }

            // Start periodic nanopub loading
            logger.info("Starting periodic nanopub loading...");
            var executor = Executors.newSingleThreadScheduledExecutor();
            executor.scheduleWithFixedDelay(
                    JellyNanopubLoader::loadUpdates,
                    JellyNanopubLoader.UPDATES_POLL_INTERVAL,
                    JellyNanopubLoader.UPDATES_POLL_INTERVAL,
                    TimeUnit.MILLISECONDS
            );

            // Periodic shard-consistency sweep (issue #139): verifies that recently
            // loaded nanopubs actually landed in every shard repo their metadata
            // implies, and re-loads any that are missing. Own single-threaded
            // executor; scheduleWithFixedDelay serialises ticks. The tick itself
            // no-ops unless the reconciliation flag is on and the state is READY.
            // 5-minute cadence: completed loads (meta stamp present) are verified
            // on the first tick after they land, so this interval — not a grace
            // window — is the repair latency for a dropped shard, and the damage
            // window of a missing shard is exactly the post-publish minutes when
            // its author is actively using it. An idle tick is one bounded query.
            Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(
                    () -> {
                        try {
                            ShardReconciler.tick();
                        } catch (Exception ex) {
                            logger.warn("Shard reconciliation tick failed", ex);
                        }
                    },
                    5, 5, TimeUnit.MINUTES
            );

            // Periodic authority-resolver tick: detects trust-state flips and
            // advances the current space-state graph by an incremental cycle on
            // each load-number delta. Same cadence as the nanopub-loading poll.
            //
            // The same single-threaded executor also runs periodicRebuildTick
            // every 10 min; that's the from-scratch rebuild triggered when an
            // incremental cycle DELETEs a structural derivation and raises the
            // npa:needsFullRebuild flag. Sharing one executor serialises the
            // two ticks naturally — they never overlap.
            if (FeatureFlags.spacesEnabled()) {
                var spacesExecutor = Executors.newSingleThreadScheduledExecutor();
                spacesExecutor.scheduleWithFixedDelay(
                        () -> {
                            try {
                                AuthorityResolver.get().tick();
                            } catch (Exception ex) {
                                logger.warn("AuthorityResolver tick failed", ex);
                            }
                        },
                        JellyNanopubLoader.UPDATES_POLL_INTERVAL,
                        JellyNanopubLoader.UPDATES_POLL_INTERVAL,
                        TimeUnit.MILLISECONDS
                );
                spacesExecutor.scheduleWithFixedDelay(
                        () -> {
                            try {
                                AuthorityResolver.get().periodicRebuildTick();
                            } catch (Exception ex) {
                                logger.warn("AuthorityResolver periodic rebuild failed", ex);
                            }
                        },
                        10, 10, TimeUnit.MINUTES
                );
            }
        }).start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                logger.info("Gracefully shutting down...");
                TripleStore.get().shutdownRepositories();
                vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                logger.info("Graceful shutdown completed");
            } catch (Exception ex) {
                logger.info("Graceful shutdown failed", ex);
            }
        }));
    }

    private String getResourceAsString(String file) {
        InputStream is = getClass().getClassLoader().getResourceAsStream("com/knowledgepixels/query/" + file);
        try (Scanner s = new Scanner(is).useDelimiter("\\A")) {
            String fileContent = s.hasNext() ? s.next() : "";
            return fileContent;
        }
    }

    private static void handleRedirect(RoutingContext req, String path) {
        String queryString = "";
        if (!req.queryParam("query").isEmpty())
            queryString = "?query=" + URLEncoder.encode(req.queryParam("query").getFirst(), Charsets.UTF_8);
        if (req.queryParam("for-type").size() == 1) {
            String type = req.queryParam("for-type").getFirst();
            req.response().putHeader("location", path + "/type/" + Utils.createHash(type) + queryString);
            req.response().setStatusCode(301).end();
        } else if (req.queryParam("for-pubkey").size() == 1) {
            String type = req.queryParam("for-pubkey").getFirst();
            req.response().putHeader("location", path + "/pubkey/" + Utils.createHash(type) + queryString);
            req.response().setStatusCode(301).end();
        } else if (req.queryParam("for-user").size() == 1) {
            String type = req.queryParam("for-user").getFirst();
            req.response().putHeader("location", path + "/user/" + Utils.createHash(type) + queryString);
            req.response().setStatusCode(301).end();
        }
    }

    /**
     * Apply headers to the response that should be present for all requests.
     *
     * @param response The response to which the headers should be applied.
     */
    static void applyGlobalHeaders(HttpServerResponse response) {
        var state = StatusController.get().getState();
        response.putHeader("Nanopub-Query-Version", Utils.getVersion());
        response.putHeader("Nanopub-Query-Status", state.state.toString());
        response.putHeader("Nanopub-Query-Registry-Url", JellyNanopubLoader.registryUrl);
        Long setupId = StatusController.get().getRegistrySetupId();
        response.putHeader("Nanopub-Query-Registry-Setup-Id", setupId == null ? "" : setupId.toString());
        response.putHeader("Nanopub-Query-Load-Counter", String.valueOf(state.loadCounter));
        // Advertised only when set, so consumers can tell that this instance loads
        // npx:ProtectedNanopub nanopubs and must not be treated as a public source.
        if (FeatureFlags.localInstance()) {
            response.putHeader("Nanopub-Query-Local-Instance", "true");
        }
        // Forward registry metadata headers
        String coverageTypes = JellyNanopubLoader.lastCoverageTypes;
        response.putHeader("Nanopub-Query-Registry-Coverage-Types", coverageTypes != null ? coverageTypes : "all");
        String coverageAgents = JellyNanopubLoader.lastCoverageAgents;
        response.putHeader("Nanopub-Query-Registry-Coverage-Agents", coverageAgents != null ? coverageAgents : "viaSetting");
        String testInstance = JellyNanopubLoader.lastTestInstance;
        if (testInstance != null) {
            response.putHeader("Nanopub-Query-Registry-Test-Instance", testInstance);
        }
        String nanopubCount = JellyNanopubLoader.lastNanopubCount;
        if (nanopubCount != null) {
            response.putHeader("Nanopub-Query-Registry-Nanopub-Count", nanopubCount);
        }
        // Cached reads only. This method runs on the Vert.x event loop for every
        // inbound request, and the non-cached accessors fall back to a blocking store
        // read whenever the cache is cold — which it is after every restart. That
        // stalled the event loop on 2026-07-31 (BlockedThreadChecker fired five times
        // right after the 1.24.0 rollout), and with the store unreachable it would
        // block every request for the full 10 s connect timeout, taking the HTTP layer
        // down with RDF4J. NanopubLoader.primeHeaderCaches() keeps these warm from the
        // metrics tick; until it has run, the headers are simply omitted, exactly as
        // they already are before the first load.
        Long loadedCount = NanopubLoader.getCachedLoadedNanopubCount();
        if (loadedCount != null) {
            response.putHeader("Nanopub-Query-Loaded-Nanopub-Count", loadedCount.toString());
        }
        String loadedChecksum = NanopubLoader.getCachedLoadedNanopubChecksum();
        if (loadedChecksum != null) {
            response.putHeader("Nanopub-Query-Loaded-Nanopub-Checksum", loadedChecksum);
        }
        // Loader liveness over HTTP. The same value backs
        // registry.loader.last_successful_batch_age_seconds, but the metrics port is
        // bound to loopback, so anything off-host — nanopub-monitor in particular —
        // cannot read it. Without this, a stalled instance looks identical to a healthy
        // one from outside: it keeps answering queries and keeps reporting READY, which
        // is how the 2026-07-31 stall stayed invisible for hours.
        //
        // Omitted rather than sent as 0 before the first tick completes, so consumers can
        // tell "not started yet" from "just ticked".
        //
        // The value is only stamped after RDF4J has actually answered (see
        // JellyNanopubLoader#lastSuccessfulBatchAtMs), so on a caught-up instance it
        // cycles 0..JellyNanopubLoader.STORE_PROBE_INTERVAL_MS rather than sitting at 0.
        // Alert thresholds must therefore exceed that interval; minutes, not seconds.
        //
        // Initial loads and resyncs stamp it as well, so this reads as "seconds since
        // the loader last moved" in every state rather than only while polling for
        // updates. Interpret it alongside Nanopub-Query-Status: a climbing age under
        // LOADING_INITIAL is a resync that has stopped progressing, not a long one.
        long lastBatchAt = JellyNanopubLoader.lastSuccessfulBatchAtMs;
        if (lastBatchAt != 0L) {
            long ageSeconds = Math.max(0L, (System.currentTimeMillis() - lastBatchAt) / 1000L);
            response.putHeader("Nanopub-Query-Loader-Last-Success-Age-Seconds", String.valueOf(ageSeconds));
        }
    }
}
