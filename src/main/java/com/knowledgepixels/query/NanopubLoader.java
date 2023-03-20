package com.knowledgepixels.query;

import org.nanopub.Nanopub;
import org.nanopub.extra.server.GetNanopub;

public class NanopubLoader {

	public static void load(String uri) {
		Nanopub np = GetNanopub.get(uri);
		System.err.println("NP: " + np.getUri());
	}

}
