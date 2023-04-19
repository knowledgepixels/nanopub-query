package com.knowledgepixels.query;

import java.util.Arrays;

import org.apache.http.HttpStatus;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.proxy.handler.ProxyHandler;
import io.vertx.httpproxy.HttpProxy;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyRequest;
import io.vertx.httpproxy.ProxyResponse;

public class MainVerticle extends AbstractVerticle {

	private boolean server1Started = false;
	private boolean server2Started = false;

	private boolean allServersStarted() {
		return server1Started && server2Started;
	}

	static {
		System.err.println("Loading Nanopub Query verticle...");
		QueryApplication.triggerInit();
	}

	@Override
	public void start(Promise<Void> startPromise) throws Exception {
//		vertx.createHttpServer().requestHandler(req -> {
//			req.response()
//				.putHeader("content-type", "text/plain")
//				.end("Hello from Vert.x 9393!");
//		}).listen(9393, http -> {
//			if (http.succeeded()) {
//				server1Started = true;
//				if (allServersStarted()) startPromise.complete();
//				System.out.println("HTTP server started on port 9393");
//			} else {
//				startPromise.fail(http.cause());
//			}
//		});

		HttpProxy rdf4jProxy = HttpProxy.reverseProxy(vertx.createHttpClient());
		rdf4jProxy.origin(8080, "rdf4j");

		rdf4jProxy.addInterceptor(new ProxyInterceptor() {

			@Override
			public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
				ProxyRequest request = context.request();
				request.setURI(request.getURI().replaceFirst("^/repo/", "/rdf4j-server/repositories/"));
				return ProxyInterceptor.super.handleProxyRequest(context);
			}

			@Override
			public Future<Void> handleProxyResponse(ProxyContext context) {
				ProxyResponse resp = context.response();
				resp.putHeader("Access-Control-Allow-Origin", "*");
				resp.putHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
				return ProxyInterceptor.super.handleProxyResponse(context);
			}

		});

		HttpProxy nginxProxy = HttpProxy.reverseProxy(vertx.createHttpClient());
		nginxProxy.origin(80, "nginx");

		HttpServer proxyServer = vertx.createHttpServer();
		Router proxyRouter = Router.router(vertx);
		proxyRouter.route(HttpMethod.GET, "/repo/*").handler(ProxyHandler.create(rdf4jProxy));
		proxyRouter.route(HttpMethod.POST, "/repo/*").handler(ProxyHandler.create(rdf4jProxy));
		proxyRouter.route(HttpMethod.HEAD, "/repo/*").handler(ProxyHandler.create(rdf4jProxy));
		proxyRouter.route(HttpMethod.OPTIONS, "/repo/*").handler(ProxyHandler.create(rdf4jProxy));
		proxyRouter.route(HttpMethod.GET, "/tools/*").handler(req -> {
			if (req.normalizedPath().matches("^/tools/([a-zA-z0-9\\-_]+)/yasgui.html$")) {
				String repo = req.normalizedPath().replaceFirst("^/tools/([^/]+)/.*$", "$1");
				req.response()
					.putHeader("content-type", "text/html")
					.end("<!DOCTYPE html>\n"
							+ "<html lang=\"en\">\n"
							+ "<head>\n"
							+ "<meta charset=\"utf-8\">\n"
							+ "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">\n"
							+ "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
							+ "<title>Nanopub Query SPARQL Editor for repository: " + repo + "</title>\n"
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
		proxyRouter.route(HttpMethod.GET, "/").handler(req -> {
			req.response()
			.putHeader("content-type", "text/html")
			.end("<!DOCTYPE html>\n"
					+ "<html lang='en'>\n"
					+ "<head>\n"
					+ "<title>Nanopub Query</title>\n"
					+ "<meta charset='utf-8'>\n"
					+ "</head>\n"
					+ "<body>\n"
					+ "This is a test"
					+ "</body>\n"
					+ "</html>");
		});
		proxyServer.requestHandler(proxyRouter);
		proxyServer.listen(9393);

		vertx.createHttpServer().requestHandler(req -> {
			try {
				req.setExpectMultipart(true);
				final StringBuilder payload = new StringBuilder();
				req.handler(data -> {
					payload.append(data.toString("UTF-8"));
				});
				req.endHandler(handler -> {
					final String dataString = payload.toString();
					RepositoryConnection c = QueryApplication.get().getRepositoryConnection();
					if (c == null) {
						req.response().setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR)
							.setStatusMessage("Triple store connection not found")
							.end();
						System.err.println("Triple store connection not found");
						return;
					}
					try {
						Nanopub np = new NanopubImpl(dataString, RDFFormat.TRIG);
						NanopubLoader.load(c, np);
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
		}).listen(9300, http -> {
			if (http.succeeded()) {
				server2Started = true;
				if (allServersStarted()) startPromise.complete();
				System.out.println("HTTP server started on port 9300");
			} else {
				startPromise.fail(http.cause());
			}
		});
	}
}
