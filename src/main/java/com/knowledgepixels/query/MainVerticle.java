package com.knowledgepixels.query;

import java.util.Arrays;

import org.apache.http.HttpStatus;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;

public class MainVerticle extends AbstractVerticle {

	private boolean server1Started = false;
	private boolean server2Started = false;

	private boolean allServersStarted() {
		return server1Started && server2Started;
	}

	static {
		System.err.println("Loading Nanopub Query verticle...");
		LocalListLoader.load();
	}

	@Override
	public void start(Promise<Void> startPromise) throws Exception {
		vertx.createHttpServer().requestHandler(req -> {
			req.response()
				.putHeader("content-type", "text/plain")
				.end("Hello from Vert.x 9393!");
		}).listen(9393, http -> {
			if (http.succeeded()) {
				server1Started = true;
				if (allServersStarted()) startPromise.complete();
				System.out.println("HTTP server started on port 9393");
			} else {
				startPromise.fail(http.cause());
			}
		});
		vertx.createHttpServer().requestHandler(req -> {
			try {
				req.setExpectMultipart(true);
				req.handler(data -> {
					System.err.println("POST: " + data.toString("UTF-8"));
				});
				req.response()
					.setStatusCode(HttpStatus.SC_OK)
					.end();
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
