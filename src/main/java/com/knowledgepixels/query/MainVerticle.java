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
				//ProxyRequest request = context.request();
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
		proxyRouter.route(HttpMethod.GET, "/rdf4j-workbench/*").handler(ProxyHandler.create(rdf4jProxy));
		proxyRouter.route(HttpMethod.GET, "/rdf4j-server/*").handler(ProxyHandler.create(rdf4jProxy));
		proxyRouter.route(HttpMethod.POST, "/rdf4j-server/*").handler(ProxyHandler.create(rdf4jProxy));
		proxyRouter.route(HttpMethod.OPTIONS, "/rdf4j-server/*").handler(ProxyHandler.create(rdf4jProxy));
		proxyRouter.route(HttpMethod.GET, "/tools/*").handler(ProxyHandler.create(nginxProxy));
		proxyRouter.route(HttpMethod.GET, "/test/*").handler(req -> {
			req.response()
			.putHeader("content-type", "text/plain")
			.end("Hello from Vert.x 9393!");
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
