package com.knowledgepixels.query;

import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpStatus;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;

import com.github.jsonldjava.shaded.com.google.common.base.Charsets;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.PoolOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.proxy.handler.ProxyHandler;
import io.vertx.httpproxy.HttpProxy;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyRequest;
import io.vertx.httpproxy.ProxyResponse;

public class MainVerticle extends AbstractVerticle {

	private boolean server1Started = false;
	private boolean server2Started = false;

	private static String css = null;

	private boolean allServersStarted() {
		return server1Started && server2Started;
	}

	@Override
	public void start(Promise<Void> startPromise) throws Exception {
		HttpClient httpClient = vertx.createHttpClient(
				new HttpClientOptions().setConnectTimeout(1000).setIdleTimeoutUnit(TimeUnit.SECONDS).setIdleTimeout(60).setReadIdleTimeout(60).setWriteIdleTimeout(60),
				new PoolOptions().setHttp1MaxSize(200).setHttp2MaxSize(200)
			);

		HttpServer proxyServer = vertx.createHttpServer();
		Router proxyRouter = Router.router(vertx);

		// ----------
		// This part is only used if the redirection is not done through Nginx.
		// See nginx.conf and this bug report: https://github.com/eclipse-rdf4j/rdf4j/discussions/5120
		HttpProxy rdf4jProxy = HttpProxy.reverseProxy(httpClient);
		rdf4jProxy.origin(8080, "rdf4j");

		rdf4jProxy.addInterceptor(new ProxyInterceptor() {

			@Override
			public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
				ProxyRequest request = context.request();
				request.setURI(request.getURI().replaceAll("/", "_").replaceFirst("^_repo_", "/rdf4j-server/repositories/"));
				// For later to try to get HTML tables out:
//				if (request.headers().get("Accept") == null) {
//					request.putHeader("Accept", "text/html");
//				}
//				request.putHeader("Accept", "application/json");
				return ProxyInterceptor.super.handleProxyRequest(context);
			}

			@Override
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

		proxyRouter.route(HttpMethod.GET, "/repo").handler(req -> {
			handleRedirect(req, "/repo");
		});
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
							+ "  yasqe:{sparql:{endpoint:'/repo/" + repo + "'}}\n"
							+ "});\n"
							+ "</script>\n"
							+ "</body>\n"
							+ "</html>");
			} else {
				req.response()
					.putHeader("content-type", "text/plain")
					.setStatusCode(404)
					.end("not found");
			}
		});
		proxyRouter.route(HttpMethod.GET, "/page").handler(req -> {
			handleRedirect(req, "/page");
		});
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
							+ "<p>YASGUI: <a href=\"/tools/" + repo + "/yasgui.html\">/tools/" + repo + "/yasgui.hml</a></p>"
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
			String repos = "";
			List<String> repoList = new ArrayList<>(TripleStore.get().getRepositoryNames());
			Collections.sort(repoList);
			for (String s : repoList) {
				if (s.startsWith("pubkey_") || s.startsWith("type_")) continue;
				repos += "<li><code><a href=\"/page/" + s + "\">" + s + "</a></code></li>";
			}
			req.response()
			.putHeader("content-type", "text/html")
			.end("<!DOCTYPE html>\n"
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
					+ "</body>\n"
					+ "</html>");
		});
		proxyRouter.route(HttpMethod.GET, "/pubkeys").handler(req -> {
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
			req.response()
			.putHeader("content-type", "text/html")
			.end("<!DOCTYPE html>\n"
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
					+ "</html>");
		});
		proxyRouter.route(HttpMethod.GET, "/types").handler(req -> {
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
			req.response()
			.putHeader("content-type", "text/html")
			.end("<!DOCTYPE html>\n"
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
					+ "</html>");
		});
		proxyRouter.route(HttpMethod.GET, "/style.css").handler(req -> {
			if (css == null) {
				css = getResourceAsString("style.css");
			}
			req.response().end(css);
		});

		proxyRouter.route(HttpMethod.GET, "/grlc-spec/*").handler(req -> {
			GrlcSpecPage gsp = new GrlcSpecPage(req.normalizedPath(), req.queryParams());
			String spec = gsp.getSpec();
			if (spec == null) {
				req.response().setStatusCode(404).end("query definition not found / not valid");
			} else {
				req.response().putHeader("content-type", "text/plain").end(spec);
			}
		});

		proxyRouter.route("/api/*").handler(req -> {
			final String apiPattern = "^/api/(RA[a-zA-Z0-9-_]{43})/([a-zA-Z0-9-_]+)$";
			if (req.normalizedPath().matches(apiPattern)) {
				// TODO Make this an internal proxy pass instead of a 307 redirect

				String artifactCode = req.normalizedPath().replaceFirst(apiPattern, "$1");
				String queryName = req.normalizedPath().replaceFirst(apiPattern, "$2");
				String grlcUrlParams = "";
				String grlcSpecUrlParams = "";
				MultiMap pm = req.queryParams();
				for (Entry<String,String> e : pm) {
					if (e.getKey().equals("api-version")) {
						grlcSpecUrlParams += "&" + e.getKey() + "=" + URLEncoder.encode(e.getValue(), Charsets.UTF_8);
					} else {
						grlcUrlParams += "&" + e.getKey() + "=" + URLEncoder.encode(e.getValue(), Charsets.UTF_8);
					}
				}
				String url = GrlcSpecPage.nanopubQueryUrl + "api-url/" + queryName +
					"?specUrl=" +  URLEncoder.encode(GrlcSpecPage.nanopubQueryUrl + "grlc-spec/" + artifactCode + "/?" +
					grlcSpecUrlParams, Charsets.UTF_8) + grlcUrlParams;
				req.response().putHeader("Location", url).setStatusCode(307).end();
			}
		});

		proxyServer.requestHandler(proxyRouter);
		proxyServer.listen(9393);

		HttpProxy grlcProxy = HttpProxy.reverseProxy(httpClient);
		grlcProxy.origin(80, "grlc");
		proxyRouter.route("/api-url/*").handler(ProxyHandler.create(grlcProxy));
		proxyRouter.route("/static/*").handler(ProxyHandler.create(grlcProxy));

		vertx.createHttpServer().requestHandler(req -> {
			try {
				final StringBuilder payload = new StringBuilder();
				req.handler(data -> {
					payload.append(data.toString("UTF-8"));
				});
				req.endHandler(handler -> {
					final String dataString = payload.toString();
					try {
						Nanopub np = new NanopubImpl(dataString, RDFFormat.TRIG);
						NanopubLoader.load(np);
					} catch (MalformedNanopubException ex) {
						req.response().setStatusCode(HttpStatus.SC_BAD_REQUEST)
							.setStatusMessage(Arrays.toString(ex.getStackTrace()))
							.end();
						ex.printStackTrace();
						return;
					};
					req.response()
						.setStatusCode(HttpStatus.SC_OK)
						.end();
				});
			} catch (Exception ex) {
				req.response().setStatusCode(HttpStatus.SC_BAD_REQUEST)
					.setStatusMessage(Arrays.toString(ex.getStackTrace()))
					.end();
			}
		}).listen(9300).onComplete(http -> {
			if (http.succeeded()) {
				server2Started = true;
				if (allServersStarted()) startPromise.complete();
				System.out.println("HTTP server started on port 9300");
			} else {
				startPromise.fail(http.cause());
			}
		});

		vertx.executeBlocking(() -> {
			LocalNanopubLoader.init();
			return null;
		}).onComplete(res -> {
		  System.err.println("Local nanopublication loading finished");
		});

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				System.err.println("Gracefully shutting down...");
				TripleStore.get().shutdownRepositories();
				vertx.close() .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
				System.err.println("Graceful shutdown completed");
			} catch (Exception ex) {
				System.err.println("Graceful shutdown failed");
				ex.printStackTrace();
			}
		}));
	}

	public String getResourceAsString(String file) {
		InputStream is = getClass().getClassLoader().getResourceAsStream("com/knowledgepixels/query/" + file);
		try (Scanner s = new Scanner(is).useDelimiter("\\A")) {
			String fileContent = s.hasNext() ? s.next() : "";
			return fileContent;
		}
	}

	public static void handleRedirect(RoutingContext req, String path) {
		String queryString = "";
		if (!req.queryParam("query").isEmpty()) queryString = "?query=" + URLEncoder.encode(req.queryParam("query").get(0), Charsets.UTF_8);
		if (req.queryParam("for-type").size() == 1) {
			String type = req.queryParam("for-type").get(0);
			req.response().putHeader("location", path + "/type/" + Utils.createHash(type) + queryString);
			req.response().setStatusCode(301).end();
		} else if (req.queryParam("for-pubkey").size() == 1) {
			String type = req.queryParam("for-pubkey").get(0);
			req.response().putHeader("location", path + "/pubkey/" + Utils.createHash(type) + queryString);
			req.response().setStatusCode(301).end();
		} else if (req.queryParam("for-user").size() == 1) {
			String type = req.queryParam("for-user").get(0);
			req.response().putHeader("location", path + "/user/" + Utils.createHash(type) + queryString);
			req.response().setStatusCode(301).end();
		}
	}

}
