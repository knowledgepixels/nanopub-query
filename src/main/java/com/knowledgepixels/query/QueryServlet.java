package com.knowledgepixels.query;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class QueryServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	//private Logger logger = LoggerFactory.getLogger(this.getClass());

	static {
		System.err.println("Loading Query Servlet...");
		LocalListLoader.load();
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		try {
			setGeneralHeaders(resp);
			resp.getWriter().write("Nanopub Query");
//			ServerRequest r = new ServerRequest(req);
//			if (r.isEmpty()) {
//				MainPage.show(r, resp);
//			} else if (r.getFullRequest().equals("/style/plain.css")) {
//				ResourcePage.show(r, resp, "style.css", "text/css");
//			}
		} finally {
			resp.getOutputStream().close();
			req.getInputStream().close();
		}
	}

	private void setGeneralHeaders(HttpServletResponse resp) {
		resp.setHeader("Access-Control-Allow-Origin", "*");
	}

}